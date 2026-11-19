package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconAlphabet
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * Mesure du coût par frappe du cœur adaptatif (spec §11.3) sur un lexique réel : calcul des poids,
 * solveur de disposition et décodage du tap, séparément puis ensemble.
 *
 * Programme manuel, non exécuté par `./gradlew test` (ce n'est pas une classe `*Test`).
 * Depuis la racine du dépôt, après `./gradlew :tools:buildLexicons :engine:testClasses` :
 *
 * ```sh
 * java -cp "engine/build/classes/kotlin/main;engine/build/classes/kotlin/test;<kotlin-stdlib.jar>" \
 *     nextglyph.engine.adapt.AdaptationBenchmarkKt [tools/build/lexicons/fr.nglx]
 * ```
 *
 * (séparateur `:` au lieu de `;` hors de Windows ; le JAR `kotlin-stdlib` est dans le cache Gradle.)
 * Les préfixes sont ceux de mots tirés selon leur fréquence (marche aléatoire dans la
 * distribution du lexique) ; les préfixes « avec faute » remplacent une lettre par une voisine.
 * Résultats de référence dans `docs/DECISIONS.md`.
 */
fun main(args: Array<String>) {
    val path: Path = Paths.get(args.firstOrNull() ?: "tools/build/lexicons/fr.nglx")
    val lexicon = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        Lexicon.load(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
    }
    println("Lexique ${lexicon.languageTag} : ${lexicon.keyCount} clés, ${lexicon.sizeInBytes} octets")

    val random = Random(11)
    val words = List(20_000) { sampleWord(lexicon, random) }
    val prefixes = words.flatMap { w -> (0 until w.length).map { w.substring(0, it) to w[it] } }
    val typos = prefixes.filter { it.first.length >= 2 }.shuffled(random).take(20_000).map { (p, next) ->
        val i = random.nextInt(p.length)
        val replaced = NEIGHBORS[p[i]]?.random(random) ?: 'x'
        (p.substring(0, i) + replaced + p.substring(i + 1)) to next
    }
    println(
        "${prefixes.size} frappes sur ${words.size} mots, ${typos.size} préfixes avec faute " +
            "(${typos.count { lexicon.distribution(it.first).isUnknownPrefix }} inconnus)",
    )

    for (mode in listOf(AdaptationMode.CALM_REINFORCED, AdaptationMode.ELASTIC)) {
        val bench = Pipeline(lexicon, AdaptationConfig(mode = mode))
        repeat(2) { bench.run(prefixes.take(50_000), random) } // préchauffage JIT
        println("— Mode $mode, préfixes corrects")
        bench.run(prefixes, random).print()
        println("— Mode $mode, préfixes avec faute")
        repeat(2) { bench.run(typos.take(5_000), random) }
        bench.run(typos, random).print()
    }
}

/** Chaîne complète d'une frappe, avec ses tampons réutilisés. */
private class Pipeline(private val lexicon: Lexicon, config: AdaptationConfig) {
    private val layout = KeyboardLayouts.AZERTY
    private val calculator = KeyWeightCalculator(layout, config)
    private val solver = LayoutSolver(layout, config)
    private val decoder = TapDecoder(layout, config.decoder)
    private val weights = KeyWeights(layout)
    private val adapted = AdaptedLayout(layout)
    private val decoding = TapDecoding(layout)
    private val rest = KeyboardGeometry.compute(layout, 1080f, 120f, 12f)

    fun run(keystrokes: List<Pair<String, Char>>, random: Random): Timings {
        val t = Timings(keystrokes.size)
        var sink = 0
        for ((n, keystroke) in keystrokes.withIndex()) {
            val (prefix, next) = keystroke
            val t0 = System.nanoTime()
            calculator.compute(prefix, lexicon, weights)
            val t1 = System.nanoTime()
            solver.solve(weights, adapted)
            val t2 = System.nanoTime()
            val displayed = KeyboardGeometry.compute(layout, 1080f, 120f, 12f, adapted.widthsByRow())
            val t3 = System.nanoTime()
            val target = displayed.rows.flatten().firstOrNull { it.key.kind == KeyKind.LETTER && it.key.label[0] == next }
                ?: displayed.rows[2][7]
            val x = target.centerX + (random.nextFloat() - 0.5f) * target.width
            val y = target.centerY + (random.nextFloat() - 0.5f) * target.height
            val t4 = System.nanoTime()
            decoder.decode(x, y, displayed, rest, weights, decoding)
            val t5 = System.nanoTime()
            if (decoding.key?.label?.firstOrNull() == next) sink++
            t.weights[n] = t1 - t0
            t.solver[n] = t2 - t1
            t.geometry[n] = t3 - t2
            t.decoder[n] = t5 - t4
            t.total[n] = (t2 - t0) + (t5 - t4)
        }
        t.accuracy = sink.toDouble() / keystrokes.size
        return t
    }
}

private class Timings(size: Int) {
    val weights = LongArray(size)
    val solver = LongArray(size)
    val geometry = LongArray(size)
    val decoder = LongArray(size)
    val total = LongArray(size)
    var accuracy = 0.0

    fun print() {
        line("poids", weights)
        line("solveur", solver)
        line("décodage", decoder)
        line("total poids + solveur + décodage", total)
        line("(géométrie KeyboardGeometry.compute, hors budget)", geometry)
        println("  taps décodés sur la lettre visée (bruit uniforme ±½ touche) : ${"%.1f".format(accuracy * 100)} %")
    }

    private fun line(label: String, times: LongArray) {
        val sorted = times.sortedArray()
        println(
            "  $label : moyenne ${us(sorted.average())} µs, médiane ${us(sorted[sorted.size / 2].toDouble())} µs, " +
                "p99 ${us(sorted[sorted.size * 99 / 100].toDouble())} µs, p99,9 ${us(sorted[sorted.size * 999 / 1000].toDouble())} µs, " +
                "max ${us(sorted.last().toDouble())} µs",
        )
    }

    private fun us(nanos: Double) = "%.2f".format(nanos / 1000)
}

/** Mot tiré selon les fréquences du lexique (marche aléatoire dans les distributions). */
private fun sampleWord(lexicon: Lexicon, random: Random): String {
    val word = StringBuilder()
    while (word.length < 24) {
        val d = lexicon.distribution(word.toString())
        var u = random.nextDouble()
        if (word.isNotEmpty() && u < d.endProbability) break
        u -= d.endProbability
        var chosen = LexiconAlphabet.CHARS.last()
        for (c in LexiconAlphabet.CHARS) {
            u -= d.probability(c)
            if (u < 0) {
                chosen = c
                break
            }
        }
        word.append(chosen)
    }
    return word.toString()
}

/** Voisines AZERTY approximatives, pour fabriquer des fautes de frappe. */
private val NEIGHBORS: Map<Char, String> = mapOf(
    'a' to "zq", 'z' to "aes", 'e' to "zrd", 'r' to "etf", 't' to "ryg", 'y' to "tuh", 'u' to "yij", 'i' to "uok",
    'o' to "ipl", 'p' to "om", 'q' to "asw", 's' to "qdz", 'd' to "sfe", 'f' to "dgr", 'g' to "fht", 'h' to "gjy",
    'j' to "hku", 'k' to "jli", 'l' to "kmo", 'm' to "lp", 'w' to "xq", 'x' to "wcs", 'c' to "xvd", 'v' to "cbf",
    'b' to "vng", 'n' to "bh",
)
