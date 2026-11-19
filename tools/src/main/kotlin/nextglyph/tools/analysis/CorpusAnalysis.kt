package nextglyph.tools.analysis

import nextglyph.engine.lexicon.Lexicon
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.SentencePartition
import nextglyph.tools.wordlists.Tokenizer
import java.util.Locale
import kotlin.random.Random

/**
 * Paramètres de l'analyse du §15.1.
 *
 * @property grid seuils testés sur les corpus de test monolingues.
 * @property errorRates taux de fautes par lettre simulés (0 = sans faute).
 * @property correctKnownPrefixes variantes de la correction testées en partie 2 (voir [CandidateSource]).
 * @property windows tailles de fenêtre du score de langue testées (convergence).
 * @property mixedWindows tailles de fenêtre testées pour les fausses validations du corpus mixte.
 * @property mixedCorrectKnownPrefixes variante de la correction utilisée sur le corpus mixte.
 * @property initialFr `π_FR` de départ (langue préférée : français).
 * @property mixedThresholds seuils testés sur le corpus mixte.
 * @property mixedErrorRates taux de fautes simulés sur le corpus mixte.
 * @property samplePercent part des phrases de test monolingues analysées (échantillon
 *   déterministe par hachage du rang de la phrase ; 100 = tout).
 * @property seed graine des fautes simulées.
 */
data class AnalysisConfig(
    val grid: List<Thresholds> = defaultGrid(),
    val errorRates: List<Double> = listOf(0.0, 0.02, 0.05),
    val correctKnownPrefixes: List<Boolean> = listOf(false, true),
    val windows: List<Int> = listOf(2, 3, 4, 6, 8, 12),
    val mixedWindows: List<Int> = listOf(3, 4, 6, 8),
    val mixedCorrectKnownPrefixes: Boolean = true,
    val evidenceModes: List<EvidenceMode> = EvidenceMode.entries,
    val initialFr: Double = 0.75,
    val mixedThresholds: List<Thresholds> = listOf(Thresholds(0.6, 2.0), Thresholds(0.7, 2.0), Thresholds(0.8, 3.0)),
    val mixedErrorRates: List<Double> = listOf(0.0, 0.02),
    val samplePercent: Int = 100,
    val seed: Long = 20261119L,
) {
    init {
        require(grid.isNotEmpty() && mixedThresholds.isNotEmpty() && windows.isNotEmpty() && correctKnownPrefixes.isNotEmpty()) {
            "La grille de seuils et les fenêtres ne doivent pas être vides."
        }
        require(samplePercent in 1..100) { "L'échantillon doit être compris entre 1 et 100 % ($samplePercent)." }
    }

    companion object {
        val MIN_PROBS: List<Double> = listOf(0.4, 0.5, 0.6, 0.7, 0.8, 0.9)
        val MIN_RATIOS: List<Double> = listOf(1.5, 2.0, 3.0, 5.0, 10.0)
        val MIN_LENGTHS: List<Int> = listOf(1, 2, 3)

        fun defaultGrid(): List<Thresholds> =
            MIN_LENGTHS.flatMap { l -> MIN_PROBS.flatMap { p -> MIN_RATIOS.map { r -> Thresholds(p, r, l) } } }
    }
}

/** Données d'une langue : lexique, disposition de l'utilisateur, phrases de test. */
class LanguageInput(val language: Language, val lexicon: Lexicon, val layout: KeyboardLayout, val test: List<String>)

/**
 * Exécute les trois parties du §15.1 et rend les résultats en Markdown. [log] reçoit
 * l'avancement (une ligne par étape).
 */
class CorpusAnalysis(private val config: AnalysisConfig = AnalysisConfig(), private val log: (String) -> Unit = {}) {

    fun run(fr: LanguageInput, en: LanguageInput, mixed: List<String>, mixedLanguages: List<Language>): String {
        require(mixed.size == mixedLanguages.size) {
            "Le corpus mixte (${mixed.size} phrases) et ses étiquettes (${mixedLanguages.size}) ne correspondent pas."
        }
        val out = StringBuilder()
        out.appendLine("# Résultats bruts de `:tools:analyzeCorpus`").appendLine()
        out.appendLine("Configuration : $config").appendLine()
        val counts = listOf(fr, en).associate { input ->
            val sample = input.test.filterIndexed { i, _ -> keep(i) }
            input.language to CorpusWords.count(sample.asSequence(), Tokenizer(input.language))
        }
        for (input in listOf(fr, en)) {
            val words = counts.getValue(input.language)
            log("Partie 1 (${input.language.code}) : ${words.analyzed} mots analysés")
            out.append(renderPrefix(input.language, words, PrefixAnalysis.analyze(input.lexicon, words.counts)))
        }
        for (input in listOf(fr, en)) {
            for (correctKnown in config.correctKnownPrefixes) {
                val source = CandidateSource(input.lexicon, input.layout, correctKnownPrefixes = correctKnown)
                val decide = { t: String -> CandidateMixer.combine(listOf(1.0 to source.lookup(t))) }
                for (rate in config.errorRates) {
                    log("Partie 2 (${input.language.code}) : correction des préfixes connus $correctKnown, fautes ${percent(rate)}")
                    val simulation = SpaceSimulation(config.grid)
                    val typos = if (rate == 0.0) null else TypoModel(input.layout, rate)
                    simulation.run(counts.getValue(input.language).counts, decide, typos, Random(config.seed))
                    out.append(renderSpace(input, rate, correctKnown, simulation))
                }
            }
        }
        out.append(renderBilingual(fr, en, mixedWords(mixed, mixedLanguages)))
        return out.toString()
    }

    private fun keep(index: Int): Boolean =
        config.samplePercent == 100 || Math.floorMod(SentencePartition.mix(index.toLong()), 100L) < config.samplePercent

    private fun renderPrefix(language: Language, words: WordCounts, report: PrefixReport): String = buildString {
        appendLine("## Partie 1 — ${language.code} : lettre suivante par position").appendLine()
        appendLine("Mots du test : ${words.tokens} ; hors alphabet (ignorés) : ${words.rejected} ; clés distinctes : ${words.counts.size}.")
        appendLine()
        appendLine("| Position | Poids | Préfixe inconnu | Continuations | Entropie (bits) | P(lettre) | r(lettre) | Rang | Top 3 | Top 5 | Atténuée (r < 0,1) |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (s in report.positions) {
            val label = if (s.position == PrefixAnalysis.MAX_POSITION) "${s.position}+" else "${s.position}"
            if (s.weight == 0L) {
                appendLine("| $label | 0 | – | – | – | – | – | – | – | – | – |")
            } else if (s.weight == s.unknownWeight) {
                appendLine("| $label | ${s.weight} | ${pct(s.unknownShare)} | – | – | – | – | – | – | – | – |")
            } else {
                appendLine(
                    "| $label | ${s.weight} | ${pct(s.unknownShare)} | ${f(s.meanContinuations, 1)} | ${f(s.meanEntropy, 2)} | " +
                        "${f(s.meanProbability, 3)} | ${f(s.meanRelative, 3)} | ${f(s.meanRank, 2)} | ${pct(s.top3Share)} | " +
                        "${pct(s.top5Share)} | ${pct(s.fadedShare)} |",
                )
            }
        }
        appendLine()
        appendLine("P(fin | mot complet) moyen (mots du lexique, ${report.endWeight} occurrences) : ${f(report.meanEndProbability, 3)}.")
        appendLine()
    }

    private fun renderSpace(input: LanguageInput, rate: Double, correctKnown: Boolean, simulation: SpaceSimulation): String = buildString {
        val variant = if (correctKnown) "correction aussi des préfixes connus" else "correction des préfixes inconnus seulement"
        appendLine("## Partie 2 — ${input.language.code} (${input.layout}), fautes ${percent(rate)} par lettre, $variant").appendLine()
        appendLine("Utilisateur attentif (espace dès que la touche espace affiche le mot voulu). « Fausses » = part des validations automatiques qui sont fausses ; « Espace à tout k » = même taux si l'utilisateur appuie sur espace après chaque nombre de lettres.")
        appendLine()
        appendLine("| minLength | minProb | minRatio | Complétées/corrigées (% mots) | dont corrections | Fausses (% auto) | Fausses (% mots) | Fausses sans faute de frappe | KSPC | KSPC avec réparation | Fautes non corrigées (% mots) | Espace à tout k : fausses (% auto) |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (c in simulation.counters) {
            val t = c.thresholds
            val a = c.attentive
            appendLine(
                "| ${t.minLength} | ${f(t.minProb, 1)} | ${f(t.minRatio, 1)} | ${pct(c.correctAutoRate)} | " +
                    "${pct(ThresholdCounters.ratio(a[SpaceOutcome.AUTO_CORRECTION.ordinal], c.words))} | ${pct(c.falseRate)} | " +
                    "${pct(c.falsePerWord)} | ${pct(ThresholdCounters.ratio(a[SpaceOutcome.AUTO_WRONG.ordinal], c.words))} | " +
                    "${f(c.kspc, 3)} | ${f(c.kspcWithRepair, 3)} | " +
                    "${pct(ThresholdCounters.ratio(a[SpaceOutcome.LITERAL_OTHER.ordinal], c.words))} | ${pct(c.everyPrefixFalseRate())} |",
            )
        }
        appendLine()
        appendLine("Détail par longueur du mot (fausses validations, % des mots de cette longueur, utilisateur attentif) et par nombre de lettres tapées avant espace (fausses, % des validations automatiques), pour quelques seuils :")
        appendLine()
        val header = (1..ThresholdCounters.LENGTH_BUCKETS).joinToString(" | ") { if (it == ThresholdCounters.LENGTH_BUCKETS) "$it+" else "$it" }
        appendLine("| Seuils | Mesure | $header |")
        appendLine("|---|---|${"---|".repeat(ThresholdCounters.LENGTH_BUCKETS)}")
        for (c in simulation.counters.filter { it.thresholds in DETAIL || it.thresholds.minLength == 1 && it.thresholds.minRatio == 2.0 }) {
            val byLength = (0 until ThresholdCounters.LENGTH_BUCKETS).joinToString(" | ") { pct(c.falsePerWordByLength(it)) }
            val byPrefix = (0 until ThresholdCounters.LENGTH_BUCKETS).joinToString(" | ") { pct(c.everyPrefixFalseRate(it)) }
            appendLine("| ${c.thresholds} | longueur du mot | $byLength |")
            appendLine("| ${c.thresholds} | k lettres tapées | $byPrefix |")
        }
        appendLine()
    }

    private fun renderBilingual(fr: LanguageInput, en: LanguageInput, words: List<MixedWord>): String = buildString {
        appendLine("## Partie 3 — corpus mixte").appendLine()
        appendLine("Mots : ${words.size} ; changements de langue : ${words.zipWithNext().count { it.first.language != it.second.language }}.")
        appendLine()
        appendLine("| Indice | Fenêtre | Langue dominante correcte (% mots) | Changements | Délai moyen (mots) | Médiane | p90 | Non rattrapés |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (mode in config.evidenceModes) {
            val evidence = LanguageEvidence(fr.lexicon, en.lexicon, mode)
            for (window in config.windows) {
                val c = BilingualAnalysis.convergence(words) { LanguageScore(window, config.initialFr, evidence::of) }
                appendLine(
                    "| $mode | $window | ${pct(c.accuracy)} | ${c.switches} | ${f(c.mean, 2)} | ${c.percentile(50)} | " +
                        "${c.percentile(90)} | ${c.censored} |",
                )
            }
        }
        appendLine()
        // Une seule disposition pour l'utilisateur bilingue : AZERTY (francophone qui écrit aussi en anglais, §5.1).
        val layout = fr.layout
        val correctKnown = config.mixedCorrectKnownPrefixes
        val sources = mapOf(
            Language.FR to CandidateSource(fr.lexicon, layout, correctKnownPrefixes = correctKnown),
            Language.EN to CandidateSource(en.lexicon, layout, correctKnownPrefixes = correctKnown),
        )
        appendLine("Fausses validations (utilisateur attentif, disposition $layout, correction des préfixes connus : $correctKnown) : score de langue estimé, comparé à la langue de la phrase connue et seule active (référence).")
        appendLine()
        appendLine("| Fautes | Indice | Fenêtre | Seuils | Auto (% mots) | Fausses (% auto) | Référence : fausses (% auto) | Attribuables à la langue (% des fausses) |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (rate in config.mixedErrorRates) {
            val typos = if (rate == 0.0) null else TypoModel(layout, rate)
            for (mode in config.evidenceModes) {
                val evidence = LanguageEvidence(fr.lexicon, en.lexicon, mode)
                for (window in config.mixedWindows) {
                    log("Partie 3 : fautes ${percent(rate)}, $mode, fenêtre $window")
                    val counters = BilingualAnalysis.falseValidations(
                        words, sources, config.mixedThresholds,
                        { LanguageScore(window, config.initialFr, evidence::of) }, typos, Random(config.seed),
                    )
                    for (c in counters) {
                        appendLine(
                            "| ${percent(rate)} | $mode | $window | ${c.thresholds} | ${pct(ThresholdCounters.ratio(c.automatic, c.words))} | " +
                                "${pct(c.falseRate)} | ${pct(c.referenceFalseRate)} | ${pct(c.attributableShare)} |",
                        )
                    }
                }
            }
        }
        appendLine()
    }

    companion object {
        /** Seuils détaillés par longueur dans le rapport, en plus de ceux de ratio 2 sans longueur minimale. */
        val DETAIL: Set<Thresholds> = setOf(Thresholds(0.8, 3.0, 2), Thresholds(0.9, 5.0, 3), Thresholds(0.8, 3.0, 3))

        fun mixedWords(sentences: List<String>, languages: List<Language>): List<MixedWord> {
            val tokenizers = Language.entries.associateWith(::Tokenizer)
            return sentences.indices.flatMap { i ->
                CorpusWords.keys(sentences[i], tokenizers.getValue(languages[i])).filterNotNull().map { MixedWord(it, languages[i]) }
            }
        }

        fun f(value: Double, decimals: Int): String = String.format(Locale.FRANCE, "%.${decimals}f", value)
        fun pct(value: Double): String = String.format(Locale.FRANCE, "%.2f %%", value * 100)
        fun percent(rate: Double): String = String.format(Locale.FRANCE, "%.0f %%", rate * 100)
    }
}
