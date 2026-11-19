package nextglyph.engine.predict

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.lexicon.LexiconSet
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * Mesure du coût par frappe de la prédiction (spec §11.3, budget de la tâche M2 : décision de
 * validation + suggestions < 2 ms) : `Predictor.predict` après chaque lettre, sans cache, sur les
 * lexiques réels, en français seul (AZERTY), anglais seul (QWERTY) et bilingue (AZERTY).
 *
 * Programme manuel, non exécuté par `./gradlew test` (ce n'est pas une classe `*Test`).
 * Depuis la racine du dépôt, après `./gradlew :tools:buildLexicons :engine:testClasses` :
 *
 * ```sh
 * java -cp "engine/build/classes/kotlin/main;engine/build/classes/kotlin/test;<kotlin-stdlib.jar>" \
 *     nextglyph.engine.predict.PredictionBenchmarkKt [tools/build/lexicons]
 * ```
 *
 * Les mots sont tirés selon leur fréquence (marche aléatoire dans la distribution du lexique) ;
 * les frappes « avec faute » remplacent une lettre du préfixe par une lettre voisine.
 * Résultats de référence dans `docs/analysis/M2-engine.md`.
 */
fun main(args: Array<String>) {
    val dir = Paths.get(args.firstOrNull() ?: "tools/build/lexicons")
    val fr = LexiconSet(load(dir.resolve("fr.nglx").toString()))
    val en = LexiconSet(load(dir.resolve("en.nglx").toString()))
    val cases = listOf(
        Triple("français, AZERTY", Predictor(mapOf("fr" to fr), KeyboardLayouts.AZERTY), fr),
        Triple("anglais, QWERTY", Predictor(mapOf("en" to en), KeyboardLayouts.QWERTY), en),
        Triple("bilingue, AZERTY", Predictor(mapOf("fr" to fr, "en" to en), KeyboardLayouts.AZERTY), fr),
    )
    val settings = PredictionSettings()
    for ((label, predictor, sampleFrom) in cases) {
        val random = Random(7)
        val adjacency = KeyAdjacency(KeyboardLayouts.AZERTY)
        val words = List(4_000) { sampleWord(sampleFrom, random) }.filter { it.isNotEmpty() }
        val clean = words.flatMap { w -> (1..w.length).map { "Il dit ${w.substring(0, it)}" } }
        val typos = clean.shuffled(random).take(10_000).map { text ->
            val i = random.nextInt(7, text.length)
            val neighbors = adjacency.neighbors(text[i])
            if (neighbors.isEmpty()) text else text.substring(0, i) + neighbors.random(random) + text.substring(i + 1)
        }
        val state = predictor.initialLanguageState(predictor.languages.first())
        repeat(2) { run(predictor, clean.take(20_000), state, settings) } // préchauffage JIT
        println("— $label : ${clean.size} frappes sans faute")
        run(predictor, clean, state, settings).print()
        println("— $label : ${typos.size} frappes avec faute")
        run(predictor, typos, state, settings).print()
    }
}

private fun load(path: String): Lexicon = FileChannel.open(Paths.get(path), StandardOpenOption.READ).use { channel ->
    Lexicon.load(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
}

private fun run(predictor: Predictor, texts: List<String>, state: LanguageState, settings: PredictionSettings): Timings {
    val timings = LongArray(texts.size)
    var sink = 0
    for ((i, text) in texts.withIndex()) {
        val t0 = System.nanoTime()
        val prediction = predictor.predict(text, state, settings)
        timings[i] = System.nanoTime() - t0
        sink += prediction.bar.slots.size
    }
    if (sink == -1) println(sink)
    return Timings(timings)
}

private class Timings(private val nanos: LongArray) {
    fun print() {
        val sorted = nanos.sorted()
        fun us(q: Double) = sorted[((sorted.size - 1) * q).toInt()] / 1000.0
        println(
            "   moyenne %.0f µs, médiane %.0f µs, p99 %.0f µs, p99,9 %.0f µs, max %.0f µs, > 2 ms : %d".format(
                sorted.average() / 1000.0, us(0.5), us(0.99), us(0.999), us(1.0), sorted.count { it > 2_000_000 },
            ),
        )
    }
}

/** Mot tiré selon sa fréquence : marche aléatoire dans les distributions du lexique. */
private fun sampleWord(set: LexiconSet, random: Random): String {
    val word = StringBuilder()
    while (word.length < 20) {
        val d = set.distribution(word.toString())
        var r = random.nextDouble()
        if (r < d.endProbability && word.isNotEmpty()) break
        r -= d.endProbability
        var next: Char? = null
        for (c in LexiconAlphabet.CHARS) {
            r -= d.probability(c)
            if (r < 0) {
                next = c
                break
            }
        }
        word.append(next ?: break)
    }
    return word.toString().filter { it in 'a'..'z' }
}
