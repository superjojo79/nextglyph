package nextglyph.tools.lexicons

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconFile
import nextglyph.engine.lexicon.LexiconFormatException
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.tools.wordlists.Language
import java.io.IOException
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * `buildLexicons <listes> <corpus> <sortie>` : pour chaque fichier embarqué
 * (`LexiconLanguage.ALL_FILES` : `fr-lexique`, `fr-dicollecte`, `en`), construit
 * `<sortie>/<nom>.nglx` à partir de `<listes>/<nom>.tsv`, le recharge par `mmap`, mesure
 * `distribution` sur les préfixes de `<corpus>/<langue>-test.txt` et écrit `<sortie>/report.md`.
 * Le français est découpé par licence (un fichier par source, `docs/DATA.md`).
 *
 * Code de sortie : 0 ; 1 si une entrée est invalide ou si les fichiers d'une langue dépassent
 * ensemble [sizeBudget] (le rapport est alors écrit quand même).
 */
class BuildLexiconsCli(
    private val sizeBudget: Long = SIZE_BUDGET_BYTES,
    private val maxPrefixes: Int = MAX_PREFIXES,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        if (args.size != 3) {
            err.println("Usage : buildLexicons <dossier des listes> <dossier du corpus> <dossier de sortie>")
            return EXIT_FAILURE
        }
        val wordlists = Paths.get(args[0])
        val corpus = Paths.get(args[1])
        val outDir = Paths.get(args[2])
        return try {
            Files.createDirectories(outDir)
            val measures = LexiconLanguage.ALL_FILES.map { build(it, wordlists, corpus, outDir, out) }
            Files.writeString(outDir.resolve(REPORT_NAME), LexiconReport.render(measures, sizeBudget))
            val tooBig = LexiconReport.sizesByLanguage(measures).filter { it.value > sizeBudget }
            tooBig.forEach { (language, size) -> err.println("Lexiques $language : $size octets, au-delà du budget de $sizeBudget octets.") }
            if (tooBig.isEmpty()) EXIT_OK else EXIT_FAILURE
        } catch (e: LexiconToolException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: LexiconFormatException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: IOException) {
            err.println("Lecture ou écriture impossible : ${e.message}")
            EXIT_FAILURE
        }
    }

    private fun build(file: LexiconFile, wordlists: Path, corpus: Path, outDir: Path, out: PrintStream): LexiconMeasure {
        val language = Language.entries.first { it.code == file.language }
        val tsv = wordlists.resolve("${file.baseName}.tsv")
        val testFile = corpus.resolve("${language.code}-test.txt")
        out.println("Lexique ${file.baseName} : lecture de $tsv")
        val entries = WordListTsv.parse(Files.readAllLines(tsv), tsv.fileName.toString())
        val compiled = LexiconCompiler.compile(language.code, entries)
        val target = outDir.resolve(file.name)
        Files.write(target, compiled.bytes)

        val loadStart = nanoTime()
        val loaded = FileChannel.open(target, StandardOpenOption.READ).use { channel ->
            Lexicon.load(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
        }
        val loadNanos = nanoTime() - loadStart

        val prefixes = PrefixSampler.sample(Files.readAllLines(testFile), language, maxPrefixes)
        val latency = if (prefixes.isEmpty()) null else LexiconBenchmark.distributionLatency(loaded, prefixes, nanoTime)
        out.println("  ${loaded.keyCount} clés, ${loaded.formCount} formes, ${loaded.sizeInBytes} octets, ${compiled.skippedForms.size} formes ignorées")
        return LexiconMeasure(
            languageTag = language.code,
            fileName = target.fileName.toString(),
            keyCount = loaded.keyCount,
            formCount = loaded.formCount,
            sizeBytes = loaded.sizeInBytes.toLong(),
            skippedForms = compiled.skippedForms,
            loadNanos = loadNanos,
            prefixCount = prefixes.size,
            distribution = latency,
        )
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FAILURE = 1
        const val REPORT_NAME = "report.md"
        const val MAX_PREFIXES = 200_000
    }
}
