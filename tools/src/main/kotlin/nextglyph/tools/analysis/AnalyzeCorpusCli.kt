package nextglyph.tools.analysis

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
 * Arguments : `<dossier wordlists> <dossier corpus> <fichier de sortie> [pourcentage d'échantillon]`.
 */
object AnalyzeCorpusCli {

    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1
    const val EXIT_USAGE = 2

    fun run(args: Array<String>, out: PrintStream, err: PrintStream, config: AnalysisConfig = AnalysisConfig()): Int {
        val sample = args.getOrNull(3)?.toIntOrNull()
        if (args.size !in 3..4 || (args.size == 4 && (sample == null || sample !in 1..100))) {
            err.println("Usage : analyzeCorpus <dossier wordlists> <dossier corpus> <fichier de sortie> [échantillon en % (1-100)]")
            return EXIT_USAGE
        }
        val wordLists = Paths.get(args[0])
        val corpus = Paths.get(args[1])
        val output = Paths.get(args[2])
        return try {
            val started = System.nanoTime()
            val fr = input(Language.FR, KeyboardLayout.AZERTY, wordLists, corpus, out)
            val en = input(Language.EN, KeyboardLayout.QWERTY, wordLists, corpus, out)
            val mixed = lines(corpus.resolve("mixed-test.txt"))
            val labels = lines(corpus.resolve("mixed-test-languages.txt")).mapIndexed { i, code ->
                Language.entries.firstOrNull { it.code == code }
                    ?: throw AnalysisException("Étiquette de langue inconnue ligne ${i + 1} : « $code ».")
            }
            val effective = if (sample == null) config else config.copy(samplePercent = sample)
            val report = CorpusAnalysis(effective) { out.println("[${(System.nanoTime() - started) / NANOS_PER_SECOND} s] $it") }.run(fr, en, mixed, labels)
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

    private fun input(language: Language, layout: KeyboardLayout, wordLists: Path, corpus: Path, out: PrintStream): LanguageInput {
        out.println("Lexique ${language.code} : construction en mémoire")
        val lexicon = Files.newBufferedReader(wordLists.resolve("${language.code}.tsv")).useLines {
            WordListLoader.load(it, language.code)
        }
        return LanguageInput(language, lexicon, layout, lines(corpus.resolve("${language.code}-test.txt")))
    }

    private fun lines(path: Path): List<String> = Files.readAllLines(path)

    private const val NANOS_PER_SECOND = 1_000_000_000L
}
