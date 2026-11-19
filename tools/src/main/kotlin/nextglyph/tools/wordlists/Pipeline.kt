package nextglyph.tools.wordlists

import nextglyph.tools.data.ManifestException
import nextglyph.tools.data.ManifestParser
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/** Fichiers sources (téléchargés par `fetchData`) nécessaires à la construction. */
data class WordListInputs(
    val lexiqueZip: Path,
    val dicollecteLexiqueZip: Path,
    val scowlTarGz: Path,
    val tatoebaFr: Path,
    val tatoebaEn: Path,
)

/** Part d'un ensemble couverte : [covered] sur [total]. */
data class Coverage(val covered: Int, val total: Int) {
    val percent: Double get() = if (total == 0) 0.0 else 100.0 * covered / total

    fun render(): String = "$covered / $total (" + String.format(Locale.ROOT, "%.2f", percent) + " %)"
}

/** Couverture de la liste (par clés) sur les phrases de test. */
object CoverageAnalysis {

    const val TOP_WORDS: Int = 1000

    data class Result(val topWords: Coverage, val tokens: Coverage, val rejectedTokens: Int)

    /**
     * - `topWords` : parmi les [TOP_WORDS] clés les plus fréquentes du test (égalités : ordre alphabétique),
     *   combien sont dans la liste ;
     * - `tokens` : part des occurrences (clés valides) présentes dans la liste ;
     * - `rejectedTokens` : mots du test écartés par [WordFilter] (hors alphabet...).
     */
    fun compute(test: List<String>, tokenizer: Tokenizer, listKeys: Set<String>): Result {
        val counts = HashMap<String, Int>()
        var rejected = 0
        for (sentence in test) {
            for (token in tokenizer.tokens(sentence)) {
                when (val verdict = WordFilter.check(token)) {
                    is WordFilter.Verdict.Accepted -> counts.merge(verdict.key, 1, Int::plus)
                    is WordFilter.Verdict.Rejected -> rejected++
                }
            }
        }
        val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_WORDS)
        val topCovered = top.count { it.key in listKeys }
        val tokenTotal = counts.values.sum()
        val tokenCovered = counts.entries.filter { it.key in listKeys }.sumOf { it.value }
        return Result(Coverage(topCovered, top.size), Coverage(tokenCovered, tokenTotal), rejected)
    }
}

data class ListStats(
    val language: Language,
    val entries: Int,
    val distinctKeys: Int,
    val exclusions: Map<String, Int>,
    val counters: Map<String, Int>,
    val trainSentences: Int,
    val testSentences: Int,
    val duplicateSentences: Int,
    val ignoredCorpusLines: Int,
    val coverage: CoverageAnalysis.Result,
    val maxFrequency: Long,
    val minFrequency: Long,
) {
    fun render(): String = buildString {
        appendLine("Liste de mots : ${language.code}")
        appendLine("entrées (formes d'affichage) : $entries")
        appendLine("clés distinctes (TextNormalizer.normalize) : $distinctKeys")
        appendLine("fréquence min / max : $minFrequency / $maxFrequency")
        appendLine("exclusions : ${exclusions.values.sum()}")
        for ((label, count) in exclusions) appendLine("  $label : $count")
        for ((label, count) in counters) appendLine("$label : $count")
        appendLine("phrases d'entraînement : $trainSentences")
        appendLine("phrases de test : $testSentences")
        appendLine("doublons de phrases écartés : $duplicateSentences")
        appendLine("lignes de corpus ignorées (mal formées ou autre langue) : $ignoredCorpusLines")
        appendLine("couverture des ${CoverageAnalysis.TOP_WORDS} mots les plus fréquents du test : ${coverage.topWords.render()}")
        appendLine("couverture des occurrences du test (mots valides) : ${coverage.tokens.render()}")
        appendLine("mots du test hors alphabet (non couverts, non comptés) : ${coverage.rejectedTokens}")
    }
}

/** Orchestration : corpus, listes de mots et statistiques, écrits sous `outputRoot`. */
class WordListPipeline(private val log: (String) -> Unit = {}) {

    fun run(inputs: WordListInputs, outputRoot: Path) {
        val corpusDir = outputRoot.resolve("corpus")
        val listDir = outputRoot.resolve("wordlists")
        Files.createDirectories(corpusDir)
        Files.createDirectories(listDir)

        log("Lexique 4.10 et Dicollecte")
        val lexique = Archives.withZipEntryLines(inputs.lexiqueZip, { it.endsWith("/Lexique4.1.tsv") }, LexiqueReader::read)
        val dicollecte = Archives.withZipEntryLines(
            inputs.dicollecteLexiqueZip,
            { it.substringAfterLast('/').let { n -> n.startsWith("lexique-grammalecte") && n.endsWith(".txt") } },
            DicollecteReader::read,
        )
        val frResult = FrenchWordList.build(dicollecte.forms, lexique.perMillion)
        val frCounters = frResult.counters + mapOf(
            "lignes Lexique mal formées" to lexique.malformedRows,
            "lignes Lexique à fréquence nulle" to lexique.zeroFrequencyRows,
            "lignes Dicollecte mal formées" to dicollecte.malformedRows,
        )
        val french = prepareCorpus(inputs.tatoebaFr, Language.FR, corpusDir)
        finish(Language.FR, frResult.copy(counters = frCounters), french, listDir)

        log("SCOWL et corpus anglais")
        val scowl = ScowlReader.read(
            Archives.readTarGz(inputs.scowlTarGz, ScowlReader::isScowlFile),
            EnglishWordList.MAX_LEVEL,
        )
        val english = prepareCorpus(inputs.tatoebaEn, Language.EN, corpusDir)
        val counter = TokenCounter()
        val tokenizer = Tokenizer(Language.EN)
        english.split.train.forEach { counter.addSentence(tokenizer.tokens(it)) }
        val enResult = EnglishWordList.build(scowl.entries, counter)
        val enCounters = enResult.counters + mapOf(
            "formes SCOWL au-dessus du niveau ${EnglishWordList.MAX_LEVEL} (ignorées)" to scowl.skippedAboveLevel,
            "mots comptés dans le corpus d'entraînement" to counter.totalTokens.toInt(),
        )
        finish(Language.EN, enResult.copy(counters = enCounters), english, listDir)

        log("Corpus mixte")
        val mixed = MixedCorpus.interleave(french.split.test, english.split.test)
        writeLines(corpusDir.resolve("mixed-test.txt"), mixed.sentences)
        writeLines(corpusDir.resolve("mixed-test-languages.txt"), mixed.languages.map { it.code })
    }

    private class PreparedCorpus(val split: CorpusSplit, val ignored: Int)

    private fun prepareCorpus(file: Path, language: Language, corpusDir: Path): PreparedCorpus {
        log("Corpus ${language.code} : lecture de ${file.fileName}")
        val read = Archives.withBzip2Lines(file) { TatoebaReader.read(it, language) }
        val split = CorpusSplitter.split(read.sentences)
        writeLines(corpusDir.resolve("${language.code}-train.txt"), split.train)
        writeLines(corpusDir.resolve("${language.code}-test.txt"), split.test)
        return PreparedCorpus(split, read.malformed + read.otherLanguage)
    }

    private fun finish(language: Language, result: WordListResult, corpus: PreparedCorpus, listDir: Path) {
        val sorted = sortedForOutput(result.entries)
        val lines = sorted.map { "${it.display}\t${it.frequency}" }
        writeLines(listDir.resolve("${language.code}.tsv"), lines)
        val keys = sorted.mapTo(HashSet()) { (WordFilter.check(it.display) as WordFilter.Verdict.Accepted).key }
        val stats = ListStats(
            language = language,
            entries = sorted.size,
            distinctKeys = keys.size,
            exclusions = result.exclusions,
            counters = result.counters,
            trainSentences = corpus.split.train.size,
            testSentences = corpus.split.test.size,
            duplicateSentences = corpus.split.duplicates,
            ignoredCorpusLines = corpus.ignored,
            coverage = CoverageAnalysis.compute(corpus.split.test, Tokenizer(language), keys),
            maxFrequency = sorted.firstOrNull()?.frequency ?: 0L,
            minFrequency = sorted.lastOrNull()?.frequency ?: 0L,
        )
        Files.writeString(listDir.resolve("${language.code}-stats.txt"), stats.render())
        log(stats.render())
    }
}

/** Ligne de commande de `:tools:buildWordLists` : `<sources.json> <dossier de données> <dossier de sortie>`. */
object BuildWordListsCli {

    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1
    const val EXIT_USAGE = 2

    private const val LEXIQUE = "lexique-4-10-fr"
    private const val DICOLLECTE_LEXIQUE = "dicollecte-lexique-7-7-fr"
    private const val SCOWL = "scowl-final-lists-2020-12-07-en"
    private const val TATOEBA_FR = "tatoeba-fra-sentences"
    private const val TATOEBA_EN = "tatoeba-eng-sentences"

    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        if (args.size != 3) {
            err.println("Usage : buildWordLists <sources.json> <dossier de données> <dossier de sortie>")
            return EXIT_USAGE
        }
        val manifest = Paths.get(args[0])
        if (!Files.isRegularFile(manifest)) {
            err.println("Manifeste introuvable : $manifest")
            return EXIT_FAILURE
        }
        return try {
            val fileNames = ManifestParser.parse(Files.readString(manifest)).associate { it.id to it.fileName }
            val dataDir = Paths.get(args[1])
            fun input(id: String): Path {
                val name = fileNames[id] ?: throw WordListException("Source « $id » absente du manifeste")
                return dataDir.resolve(name).also {
                    if (!Files.isRegularFile(it)) throw WordListException("Fichier absent : $it (lancer :tools:fetchData)")
                }
            }
            val inputs = WordListInputs(input(LEXIQUE), input(DICOLLECTE_LEXIQUE), input(SCOWL), input(TATOEBA_FR), input(TATOEBA_EN))
            WordListPipeline { out.println(it) }.run(inputs, Paths.get(args[2]))
            EXIT_OK
        } catch (e: ManifestException) {
            err.println("Manifeste invalide ($manifest) : ${e.message}")
            EXIT_FAILURE
        } catch (e: WordListException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: IOException) {
            err.println("Lecture ou écriture impossible : ${e.message}")
            EXIT_FAILURE
        }
    }
}
