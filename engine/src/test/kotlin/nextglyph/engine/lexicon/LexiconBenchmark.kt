package nextglyph.engine.lexicon

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * Mesure de taille et de temps du lexique sur 500 000 clés synthétiques (spec §11.3).
 *
 * Programme manuel, non exécuté par `./gradlew test` (ce n'est pas une classe `*Test`).
 * Depuis la racine du dépôt, après `./gradlew :engine:testClasses` :
 *
 * ```sh
 * java -Xmx2g -cp "engine/build/classes/kotlin/main:engine/build/classes/kotlin/test:<kotlin-stdlib.jar>" \
 *     nextglyph.engine.lexicon.LexiconBenchmarkKt
 * ```
 *
 * (séparateur `;` au lieu de `:` sous Windows ; le JAR `kotlin-stdlib` est dans le cache Gradle.)
 * Résultats de référence dans `docs/DECISIONS.md`.
 */
fun main() {
    val keyCount = 500_000
    val entries = SyntheticLexicon.entries(keyCount)

    var start = System.nanoTime()
    val builder = LexiconBuilder("fr")
    for ((form, frequency) in entries) builder.add(form, frequency)
    val bytes = builder.toByteArray()
    println("Construction : ${ms(System.nanoTime() - start)} ms pour ${entries.size} formes")

    val file = Files.createTempFile("nextglyph-lexique", ".bin")
    Files.write(file, bytes)
    start = System.nanoTime()
    val lexicon = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        Lexicon.load(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
    }
    println("Chargement mmap : ${ms(System.nanoTime() - start)} ms")
    println(
        "Taille : ${bytes.size} octets (${"%.2f".format(bytes.size / 1_048_576.0)} Mio) pour " +
            "${lexicon.keyCount} clés et ${lexicon.formCount} formes, " +
            "${"%.1f".format(bytes.size.toDouble() / lexicon.keyCount)} octets par clé, " +
            "${entries.count { it.first.any { c -> c !in 'a'..'z' } }} formes accentuées",
    )

    val random = Random(7)
    val keys = entries.map { it.first }
    val prefixes = List(200_000) {
        val key = nextglyph.engine.text.TextNormalizer.normalize(keys.random(random))
        key.substring(0, random.nextInt(key.length + 1))
    }
    repeat(3) { measure("distribution", prefixes) { lexicon.distribution(it) } }
    repeat(3) { measure("completions(5)", prefixes.take(50_000)) { lexicon.completions(it, 5) } }
    for (prefix in listOf("", "c", "m")) {
        measure("completions(5) sur « $prefix » seul", List(20_000) { prefix }) { lexicon.completions(it, 5) }
    }
    runCatching { Files.deleteIfExists(file) }
}

private fun measure(label: String, prefixes: List<String>, query: (String) -> Any) {
    val times = LongArray(prefixes.size)
    var sink = 0
    var slowest = 0
    for (i in prefixes.indices) {
        val start = System.nanoTime()
        sink += query(prefixes[i]).hashCode()
        times[i] = System.nanoTime() - start
        if (times[i] > times[slowest]) slowest = i
    }
    val slowestPrefix = prefixes[slowest]
    times.sort()
    println(
        "$label : moyenne ${us(times.average())} µs, médiane ${us(times[times.size / 2].toDouble())} µs, " +
            "p99 ${us(times[times.size * 99 / 100].toDouble())} µs, max ${us(times.last().toDouble())} µs " +
            "(préfixe « $slowestPrefix », ${prefixes.size} requêtes, contrôle $sink)",
    )
}

private fun ms(nanos: Long) = nanos / 1_000_000

private fun us(nanos: Double) = "%.2f".format(nanos / 1000)
