package nextglyph.tools.analysis

import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.tools.wordlists.Language
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `:tools:analyzeCorpus` : lit les sorties de `buildWordLists` (listes de mots et corpus de
 * **test** uniquement, jamais l'entraînement) et écrit les résultats en Markdown.
 *
 * Arguments : `[--engine | --engine-combined] <dossier wordlists> <dossier corpus> <fichier de sortie> [pourcentage d'échantillon]`.
 * Avec `--engine`, l'analyse M2 ([EngineAnalysis]) remplace celle de M0 : validation par espace
 * rejouée avec le moteur (`nextglyph.engine.predict`) ; l'échantillon s'applique alors aux phrases
 * monolingues et au corpus mixte. Le moteur lit alors les lexiques comme l'application : le
 * français en deux fichiers fusionnés (`fr-lexique.tsv` sans casse, `fr-dicollecte.tsv`), s'ils
 * existent. `--engine-combined` lit la liste française unique `fr.tsv` (comparaison).
 */
object AnalyzeCorpusCli {

    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1
    const val EXIT_USAGE = 2

    fun run(
        arguments: Array<String>,
        out: PrintStream,
        err: PrintStream,
        config: AnalysisConfig = AnalysisConfig(),
        engineConfig: EngineAnalysisConfig = EngineAnalysisConfig(),
    ): Int {
        val combined = arguments.firstOrNull() == ENGINE_COMBINED_FLAG
        val engine = combined || arguments.firstOrNull() == ENGINE_FLAG
        val args = if (engine) arguments.copyOfRange(1, arguments.size) else arguments
        val sample = args.getOrNull(3)?.toIntOrNull()
        if (args.size !in 3..4 || (args.size == 4 && (sample == null || sample !in 1..100))) {
            err.println("Usage : analyzeCorpus [$ENGINE_FLAG] <dossier wordlists> <dossier corpus> <fichier de sortie> [échantillon en % (1-100)]")
            return EXIT_USAGE
        }
        val wordLists = Paths.get(args[0])
        val corpus = Paths.get(args[1])
        val output = Paths.get(args[2])
        return try {
            val started = System.nanoTime()
            val fr = input(Language.FR, KeyboardLayout.AZERTY, wordLists, corpus, out, split = engine && !combined)
            val en = input(Language.EN, KeyboardLayout.QWERTY, wordLists, corpus, out, split = engine && !combined)
            val mixed = lines(corpus.resolve("mixed-test.txt"))
            val labels = lines(corpus.resolve("mixed-test-languages.txt")).mapIndexed { i, code ->
                Language.entries.firstOrNull { it.code == code }
                    ?: throw AnalysisException("Étiquette de langue inconnue ligne ${i + 1} : « $code ».")
            }
            val progress = { line: String -> out.println("[${(System.nanoTime() - started) / NANOS_PER_SECOND} s] $line") }
            val report = if (engine) {
                val effective = if (sample == null) engineConfig else engineConfig.copy(samplePercent = sample, mixedSamplePercent = sample)
                EngineAnalysis(effective, progress).run(fr, en, mixed, labels)
            } else {
                val effective = if (sample == null) config else config.copy(samplePercent = sample)
                CorpusAnalysis(effective, progress).run(fr, en, mixed, labels)
            }
            val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
            output.parent?.let(Files::createDirectories)
            Files.writeString(output, report + "Durée de l'analyse : $seconds s.\n")
            out.println("Résultats écrits dans $output ($seconds s)")
            EXIT_OK
        } catch (e: AnalysisException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: IOException) {
            err.println("Lecture ou écriture impossible : ${e.message} (lancer :tools:buildWordLists)")
            EXIT_FAILURE
        }
    }

    private fun input(language: Language, layout: KeyboardLayout, wordLists: Path, corpus: Path, out: PrintStream, split: Boolean): LanguageInput {
        out.println("Lexique ${language.code} : construction en mémoire")
        val lexicon = load(wordLists.resolve("${language.code}.tsv"), language.code)
        val test = lines(corpus.resolve("${language.code}-test.txt"))
        val files = LexiconLanguage.files(language.code).map { wordLists.resolve("${it.baseName}.tsv") }
        if (!split || files.size < 2 || !files.all(Files::isRegularFile)) return LanguageInput(language, lexicon, layout, test)
        out.println("Lexique ${language.code} : fichiers séparés ${files.map { it.fileName }}")
        val set = LexiconLanguage.assemble(language.code, files.map { load(it, language.code) })
        return LanguageInput(language, lexicon, layout, test, set)
    }

    private fun load(path: Path, tag: String) = Files.newBufferedReader(path).useLines { WordListLoader.load(it, tag) }

    private fun lines(path: Path): List<String> = Files.readAllLines(path)

    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** Premier argument facultatif : analyse M2 avec le moteur. */
    const val ENGINE_FLAG: String = "--engine"

    /** Variante de [ENGINE_FLAG] avec la liste française unique `fr.tsv`. */
    const val ENGINE_COMBINED_FLAG: String = "--engine-combined"
}
