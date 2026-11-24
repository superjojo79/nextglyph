package nextglyph.tools.analysis

import nextglyph.engine.ngram.NgramFormatException
import nextglyph.tools.wordlists.Language
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * `:tools:analyzeContext` : mesure du contexte de la correction (issue #55, [ContextAnalysis]) sur les corpus de
 * **test** de `buildWordLists`, avec les lexiques de `wordlists` et les modèles de mot suivant `<langue>.ngrm` de
 * `buildLexicons` (comptes de confusion compris).
 *
 * Arguments : `<dossier wordlists> <dossier corpus> <dossier lexicons> <fichier de sortie> [phrases par langue]`.
 */
object AnalyzeContextCli {

    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1
    const val EXIT_USAGE = 2

    fun run(arguments: Array<String>, out: PrintStream, err: PrintStream, config: ContextAnalysisConfig = ContextAnalysisConfig()): Int {
        val sentences = arguments.getOrNull(4)?.toIntOrNull()
        if (arguments.size !in 4..5 || (arguments.size == 5 && (sentences == null || sentences < 1))) {
            err.println("Usage : analyzeContext <dossier wordlists> <dossier corpus> <dossier lexicons> <fichier de sortie> [phrases par langue]")
            return EXIT_USAGE
        }
        val wordLists = Paths.get(arguments[0])
        val corpus = Paths.get(arguments[1])
        val lexicons = Paths.get(arguments[2])
        val output = Paths.get(arguments[3])
        return try {
            val started = System.nanoTime()
            val inputs = listOf(
                AnalyzeSpacesCli.spaceInput(Language.FR, KeyboardLayout.AZERTY, wordLists, corpus, lexicons, out),
                AnalyzeSpacesCli.spaceInput(Language.EN, KeyboardLayout.QWERTY, wordLists, corpus, lexicons, out),
            )
            val effective = if (sentences == null) config else config.copy(sentences = sentences)
            val report = ContextAnalysis(effective) { line -> out.println("[${(System.nanoTime() - started) / NANOS_PER_SECOND} s] $line") }.run(inputs)
            val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
            output.parent?.let(Files::createDirectories)
            Files.writeString(output, report + "Durée de l'analyse : $seconds s.\n")
            out.println("Résultats écrits dans $output ($seconds s)")
            EXIT_OK
        } catch (e: AnalysisException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: NgramFormatException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: IOException) {
            err.println("Lecture ou écriture impossible : ${e.message} (lancer :tools:buildLexicons)")
            EXIT_FAILURE
        }
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
}
