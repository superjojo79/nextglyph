package nextglyph.tools.ngrams

import nextglyph.engine.ngram.NgramFormatException
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramPruning
import nextglyph.tools.lexicons.LexiconToolException
import nextglyph.tools.lexicons.WordListTsv
import nextglyph.tools.wordlists.Language
import java.io.IOException
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * `buildNgrams <listes> <corpus> <sortie>` : pour chaque langue, construit `<sortie>/<langue>.ngrm`
 * (modèle de mot suivant, issue #51) à partir des phrases de `<corpus>/<langue>-train.txt` et de la
 * liste de mots `<listes>/<langue>.tsv`, le recharge par `mmap`, mesure les taux de réussite sur
 * `<corpus>/<langue>-test.txt` et écrit `<sortie>/ngram-report.md`.
 *
 * Code de sortie : 0 ; 1 si une entrée est invalide ou si un modèle dépasse [sizeBudget] (le rapport
 * est alors écrit quand même).
 */
class BuildNgramsCli(
    private val sizeBudget: Long = NGRAM_SIZE_BUDGET_BYTES,
    private val pruning: NgramPruning = NgramTrainer.DEFAULT_PRUNING,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        if (args.size != 3) {
            err.println("Usage : buildNgrams <dossier des listes> <dossier du corpus> <dossier de sortie>")
            return EXIT_FAILURE
        }
        val wordlists = Paths.get(args[0])
        val corpus = Paths.get(args[1])
        val outDir = Paths.get(args[2])
        return try {
            Files.createDirectories(outDir)
            val measures = Language.entries.map { build(it, wordlists, corpus, outDir, out) }
            Files.writeString(outDir.resolve(REPORT_NAME), NgramReport.render(measures, sizeBudget))
            val tooBig = measures.filter { it.sizeBytes > sizeBudget }
            tooBig.forEach { err.println("Modèle ${it.languageTag} : ${it.sizeBytes} octets, au-delà du budget de $sizeBudget octets.") }
            if (tooBig.isEmpty()) EXIT_OK else EXIT_FAILURE
        } catch (e: LexiconToolException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: NgramFormatException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: IOException) {
            err.println("Lecture ou écriture impossible : ${e.message}")
            EXIT_FAILURE
        }
    }

    private fun build(language: Language, wordlists: Path, corpus: Path, outDir: Path, out: PrintStream): NgramMeasure {
        val tsv = wordlists.resolve("${language.code}.tsv")
        out.println("Mot suivant ${language.code} : lecture de $tsv")
        val vocabulary = NgramVocabulary(language, WordListTsv.parse(Files.readAllLines(tsv), tsv.fileName.toString()))

        val trainer = NgramTrainer(language, vocabulary, pruning)
        Files.newBufferedReader(corpus.resolve("${language.code}-train.txt")).useLines { lines -> lines.forEach(trainer::add) }
        val target = outDir.resolve("${language.code}.ngrm")
        Files.write(target, trainer.build())

        val loadStart = nanoTime()
        val model = FileChannel.open(target, StandardOpenOption.READ).use { channel ->
            NgramModel.load(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
        }
        val loadNanos = nanoTime() - loadStart

        val evaluator = NgramEvaluator(model, vocabulary, language)
        Files.newBufferedReader(corpus.resolve("${language.code}-test.txt")).useLines { lines -> lines.forEach(evaluator::add) }
        val hits = evaluator.result()
        out.println(
            "  ${model.vocabularySize} mots, ${model.bigramCount} bigrammes, ${model.trigramCount} trigrammes, " +
                "${model.sizeInBytes} octets, top-1 ${"%.1f".format(hits.top1OfEligible * 100)} %, top-3 ${"%.1f".format(hits.top3OfEligible * 100)} %",
        )
        return NgramMeasure(
            languageTag = language.code,
            fileName = target.fileName.toString(),
            sizeBytes = model.sizeInBytes.toLong(),
            vocabulary = model.vocabularySize,
            bigrams = model.bigramCount,
            trigramContexts = model.trigramContextCount,
            trigrams = model.trigramCount,
            trainSentences = trainer.sentences,
            trainTokens = trainer.tokens,
            loadNanos = loadNanos,
            hits = hits,
        )
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FAILURE = 1
        const val REPORT_NAME = "ngram-report.md"
    }
}
