package nextglyph.tools.analysis

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.LanguageState
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.Prudence
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.WordTaps
import nextglyph.tools.analysis.CorpusAnalysis.Companion.f
import nextglyph.tools.analysis.CorpusAnalysis.Companion.pct
import nextglyph.tools.analysis.CorpusAnalysis.Companion.percent
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.SentencePartition
import nextglyph.tools.wordlists.Tokenizer
import java.util.Locale
import kotlin.random.Random

/**
 * Paramètres de l'analyse M2 (validation par espace rejouée avec le moteur).
 *
 * @property samplePercent part des phrases de test monolingues analysées (échantillon déterministe,
 *   même hachage que l'analyse M0).
 * @property mixedSamplePercent part des phrases du corpus mixte analysées.
 * @property cacheSize taille du cache de candidats du moteur, par langue.
 * @property spatialSigmas écarts-types σu (fraction de touche) du modèle de frappe gaussien de la section
 *   « Score spatial » ; liste vide : section omise.
 * @property spatialSamplePercent part des phrases de test analysées pour cette section (le cache du
 *   moteur est contourné avec les frappes : elle est plus lente), par défaut [samplePercent].
 */
data class EngineAnalysisConfig(
    val prudences: List<Prudence> = Prudence.entries,
    val errorRates: List<Double> = listOf(0.0, 0.02, 0.05),
    val mixedErrorRates: List<Double> = listOf(0.0, 0.02),
    val samplePercent: Int = 5,
    val mixedSamplePercent: Int = 5,
    val cacheSize: Int = 400_000,
    val seed: Long = 20261119L,
    val spatialSigmas: List<Double> = listOf(0.20, 0.25),
    val spatialSamplePercent: Int = samplePercent,
) {
    init {
        require(prudences.isNotEmpty() && errorRates.isNotEmpty()) { "Les prudences et taux de fautes ne doivent pas être vides." }
        require(spatialSamplePercent in 1..100 && spatialSigmas.all { it.isFinite() && it >= 0.0 }) {
            "L'échantillon spatial doit être compris entre 1 et 100 % et les écarts-types positifs ou nuls."
        }
        require(samplePercent in 1..100 && mixedSamplePercent in 1..100) { "Les échantillons doivent être compris entre 1 et 100 %." }
    }
}

/** Issues de la restitution des séparateurs ou des accents, pondérées par les occurrences. */
class RestorationCounters {
    var words = 0L; private set
    var restored = 0L; private set
    var wrong = 0L; private set

    /** Texte inséré tel quel (mot ambigu, ou pas de candidat dominant). */
    val literal: Long get() = words - restored - wrong

    fun add(outcome: Restoration, count: Long) {
        words += count
        when (outcome) {
            Restoration.RESTORED -> restored += count
            Restoration.WRONG -> wrong += count
            Restoration.LITERAL -> Unit
        }
    }
}

/** Issue d'un mot dont la forme insérée diffère du texte tapé (apostrophe omise, accent absent). */
enum class Restoration { RESTORED, WRONG, LITERAL }

/**
 * Analyse M2 (`docs/analysis/M2-engine.md`) : les simulations de la partie 2 et de la partie 3 de
 * l'analyse M0, mais avec la décision du moteur (`nextglyph.engine.predict.Predictor`), plus la
 * restitution des apostrophes, traits d'union et accents. La référence M0 (`CandidateSource` des
 * outils, correction des préfixes connus) est rejouée sur le même échantillon.
 */
class EngineAnalysis(private val config: EngineAnalysisConfig = EngineAnalysisConfig(), private val log: (String) -> Unit = {}) {

    fun run(fr: LanguageInput, en: LanguageInput, mixed: List<String>, mixedLanguages: List<Language>): String {
        require(mixed.size == mixedLanguages.size) {
            "Le corpus mixte (${mixed.size} phrases) et ses étiquettes (${mixedLanguages.size}) ne correspondent pas."
        }
        val out = StringBuilder()
        out.appendLine("# Résultats bruts de `:tools:analyzeEngine` (M2)").appendLine()
        out.appendLine("Configuration : $config").appendLine()
        val sets = mapOf(Language.FR to fr.set, Language.EN to en.set)
        out.appendLine("Lexiques : ${sets.values.joinToString(" ; ") { "${it.language} ${it.members.size} fichier(s)" }}").appendLine()
        for (input in listOf(fr, en)) {
            val sample = input.test.filterIndexed { i, _ -> keep(i, config.samplePercent) }
            val words = CorpusWords.count(sample.asSequence(), Tokenizer(input.language))
            out.append(space(input, sets.getValue(input.language), words))
            out.append(separators(input, sets.getValue(input.language), sample))
            if (config.spatialSigmas.isNotEmpty()) {
                val spatialSample = input.test.filterIndexed { i, _ -> keep(i, config.spatialSamplePercent) }
                out.append(spatial(input, sets.getValue(input.language), CorpusWords.count(spatialSample.asSequence(), Tokenizer(input.language))))
            }
        }
        out.append(accents(fr, sets, fr.test.filterIndexed { i, _ -> keep(i, config.samplePercent) }))
        // Corpus mixte : début du corpus (blocs de phrases contigus), une liste de mots par phrase.
        val kept = (mixed.size.toLong() * config.mixedSamplePercent / 100).toInt()
        val sentences = (0 until kept).map { CorpusAnalysis.mixedWords(listOf(mixed[it]), listOf(mixedLanguages[it])) }
        out.append(bilingual(sets, sentences))
        return out.toString()
    }

    /** Validation par espace, utilisateur attentif : moteur pour chaque prudence, et référence M0. */
    private fun space(input: LanguageInput, set: LexiconSet, words: WordCounts): String = buildString {
        val layout = KeyboardLayouts.of(layoutKind(input.layout))
        val predictor = Predictor(mapOf(input.language.code to set), layout, cacheSize = config.cacheSize)
        val state = predictor.initialLanguageState(input.language.code)
        val m0 = CandidateSource(input.lexicon, input.layout, correctKnownPrefixes = true)
        appendLine("## Validation par espace — ${input.language.code} (${input.layout}), ${words.analyzed} mots").appendLine()
        appendLine("| Fautes | Décision | Complétées/corrigées (% mots) | dont corrections | Fausses (% auto) | KSPC | Fautes non corrigées (% mots) |")
        appendLine("|---|---|---|---|---|---|---|")
        for (rate in config.errorRates) {
            val typos = if (rate == 0.0) null else TypoModel(input.layout, rate)
            for (prudence in config.prudences) {
                log("M2 ${input.language.code} : fautes ${percent(rate)}, $prudence")
                val settings = PredictionSettings(prudence = prudence)
                val counter = simulate(words.counts, engineDecider(predictor, state, settings), typos)
                appendLine(row(rate, "moteur, $prudence (${f(prudence.minProb, 2)})", counter))
                val reference = simulate(
                    words.counts,
                    { t: String -> CandidateMixer.combine(listOf(1.0 to m0.lookup(t))) },
                    typos,
                    Thresholds(prudence.minProb, prudence.minRatio),
                )
                appendLine(row(rate, "M0, minProb ${f(prudence.minProb, 2)}", reference))
            }
            m0.clearCache()
        }
        appendLine()
    }

    /**
     * Score spatial (issue #53) : l'utilisateur attentif tape chaque occurrence avec le modèle gaussien
     * [GaussianTapModel] ; le moteur décide (a) sans les frappes (poids binaires) et (b) avec les `k`
     * premières frappes pour le préfixe de longueur `k`. Mêmes tirages pour tous les réglages. Chaque
     * occurrence est tirée (les frappes changent les poids même sans lettre fausse).
     */
    private fun spatial(input: LanguageInput, set: LexiconSet, words: WordCounts): String = buildString {
        val layout = KeyboardLayouts.of(layoutKind(input.layout))
        val predictor = Predictor(mapOf(input.language.code to set), layout, cacheSize = config.cacheSize)
        val state = predictor.initialLanguageState(input.language.code)
        appendLine("## Score spatial (frappes simulées) — ${input.language.code} (${input.layout}), ${words.analyzed} mots").appendLine()
        appendLine("| σu | Lettres fausses | Prudence | Moteur | Complétées/corrigées (% mots) | dont corrections | Fausses (% auto) | Fautes non corrigées (% mots) |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (sigma in config.spatialSigmas) {
            val started = System.nanoTime()
            val model = GaussianTapModel(layout, sigma)
            val random = Random(config.seed)
            val settings = config.prudences.map { PredictionSettings(prudence = it) }
            val binary = settings.map { SpaceSimulation(listOf(ENGINE_THRESHOLDS)) }
            val spatial = settings.map { SpaceSimulation(listOf(ENGINE_THRESHOLDS)) }
            var letters = 0L
            var wrongLetters = 0L
            for ((word, count) in words.counts) {
                repeat(count.toInt()) {
                    val tapped = model.type(word, random)
                    letters += word.length
                    wrongLetters += word.indices.count { word[it] != tapped.typed[it] }
                    for (i in settings.indices) {
                        binary[i].record(word, tapped.typed, 1, engineDecider(predictor, state, settings[i]))
                        spatial[i].record(word, tapped.typed, 1) { prefix ->
                            decision(predictor.decide(prefix, prefix, state, settings[i], WordTaps(tapped.taps.subList(0, prefix.length))))
                        }
                    }
                }
            }
            for ((i, prudence) in config.prudences.withIndex()) {
                appendLine(spatialRow(sigma, wrongLetters, letters, prudence, "binaire (sans frappes)", binary[i].counters.single()))
                appendLine(spatialRow(sigma, wrongLetters, letters, prudence, "spatial", spatial[i].counters.single()))
            }
            log("M2 ${input.language.code} : score spatial σu ${f(sigma, 2)} en ${(System.nanoTime() - started) / 1_000_000_000} s")
        }
        appendLine()
    }

    private fun spatialRow(sigma: Double, wrong: Long, letters: Long, prudence: Prudence, label: String, c: ThresholdCounters): String {
        val a = c.attentive
        return "| ${f(sigma, 2)} | ${pct(ThresholdCounters.ratio(wrong, letters))} | $prudence | $label | ${pct(c.correctAutoRate)} | " +
            "${pct(ThresholdCounters.ratio(a[SpaceOutcome.AUTO_CORRECTION.ordinal], c.words))} | ${pct(c.falseRate)} | " +
            "${pct(ThresholdCounters.ratio(a[SpaceOutcome.LITERAL_OTHER.ordinal], c.words))} |"
    }

    private fun simulate(
        counts: Map<String, Long>,
        decide: (String) -> Decision,
        typos: TypoModel?,
        thresholds: Thresholds = ENGINE_THRESHOLDS,
    ): ThresholdCounters {
        val simulation = SpaceSimulation(listOf(thresholds))
        simulation.run(counts, decide, typos, Random(config.seed))
        return simulation.counters.single()
    }

    private fun row(rate: Double, label: String, c: ThresholdCounters): String {
        val a = c.attentive
        return "| ${percent(rate)} | $label | ${pct(c.correctAutoRate)} | " +
            "${pct(ThresholdCounters.ratio(a[SpaceOutcome.AUTO_CORRECTION.ordinal], c.words))} | ${pct(c.falseRate)} | " +
            "${f(c.kspc, 3)} | ${pct(ThresholdCounters.ratio(a[SpaceOutcome.LITERAL_OTHER.ordinal], c.words))} |"
    }

    /**
     * Mots tapés sans apostrophe ni trait d'union (`jai` pour `j'ai`, `dont` pour `don't`,
     * `peutetre` pour `peut-être`) par l'utilisateur attentif, réglage Normal, sans faute de frappe.
     */
    private fun separators(input: LanguageInput, set: LexiconSet, sample: List<String>): String = buildString {
        val layout = KeyboardLayouts.of(layoutKind(input.layout))
        val predictor = Predictor(mapOf(input.language.code to set), layout, cacheSize = config.cacheSize)
        val decide = engineDecider(predictor, predictor.initialLanguageState(input.language.code), PredictionSettings())
        val counters = linkedMapOf("élision" to RestorationCounters(), "apostrophe" to RestorationCounters(), "trait d'union" to RestorationCounters())
        val examples = HashMap<Pair<String, String>, Long>()
        val tokenizer = Tokenizer(input.language)
        for (sentence in sample) {
            val keys = CorpusWords.keys(sentence, tokenizer)
            for ((i, key) in keys.withIndex()) {
                if (key == null) continue
                val next = keys.getOrNull(i + 1)
                val intended = when {
                    isElision(key) && next != null && next.first() in ELISION_INITIALS -> key + next
                    '\'' in key || '-' in key -> key
                    else -> continue
                }
                val kind = when {
                    isElision(key) -> "élision"
                    '\'' in key -> "apostrophe"
                    else -> "trait d'union"
                }
                val typed = intended.replace("'", "").replace("-", "")
                if (typed.isEmpty()) continue
                val outcome = restoration(typed, intended, decide)
                counters.getValue(kind).add(outcome, 1)
                if (outcome != Restoration.RESTORED) examples.merge(intended to outcome.name, 1L, Long::plus)
            }
        }
        appendLine("## Apostrophes et traits d'union omis — ${input.language.code}, réglage Normal, sans faute").appendLine()
        appendLine("| Cas | Occurrences | Restitués | Faux | Laissés tels quels |")
        appendLine("|---|---|---|---|---|")
        for ((kind, c) in counters) {
            if (c.words == 0L) continue
            appendLine(
                "| $kind | ${c.words} | ${pct(ThresholdCounters.ratio(c.restored, c.words))} | " +
                    "${pct(ThresholdCounters.ratio(c.wrong, c.words))} | ${pct(ThresholdCounters.ratio(c.literal, c.words))} |",
            )
        }
        appendLine()
        val top = examples.entries.sortedByDescending { it.value }.take(EXAMPLES)
        if (top.isNotEmpty()) {
            appendLine("Principaux mots non restitués : " + top.joinToString(", ") { "`${it.key.first}` (${it.key.second.lowercase()}, ${it.value})" } + ".")
            appendLine()
        }
    }

    /**
     * Accents (§5.8) : chaque mot du test français, tapé sans accent ni majuscule et en entier, avec
     * le français seul puis avec les deux lexiques (score de langue de départ, français préféré).
     */
    private fun accents(fr: LanguageInput, sets: Map<Language, LexiconSet>, sample: List<String>): String = buildString {
        val tokenizer = Tokenizer(Language.FR)
        val forms = HashMap<String, Long>()
        for (sentence in sample) for (token in tokenizer.tokens(sentence)) forms.merge(token.lowercase(Locale.ROOT), 1L, Long::plus)
        appendLine("## Restitution des accents — fr, mots tapés sans accent").appendLine()
        appendLine("| Lexiques | Mots accentués | Restitués | Faux | Laissés sans accent | Mots sans accent accentués à tort |")
        appendLine("|---|---|---|---|---|---|")
        val variants = listOf(
            "français" to mapOf("fr" to sets.getValue(Language.FR)),
            "français + anglais" to mapOf("fr" to sets.getValue(Language.FR), "en" to sets.getValue(Language.EN)),
        )
        val examples = ArrayList<Pair<String, List<Map.Entry<String, Long>>>>()
        for ((label, lexicons) in variants) {
            val predictor = Predictor(lexicons, KeyboardLayouts.of(layoutKind(fr.layout)))
            val state = predictor.initialLanguageState("fr")
            val accented = RestorationCounters()
            val missed = HashMap<String, Long>()
            var plain = 0L
            var plainWrong = 0L
            for ((form, count) in forms) {
                val key = CorpusWords.keyOf(form) ?: continue
                val prediction = predictor.decide(key, key, state, PredictionSettings())
                if (prediction.case != SpaceCase.WORD) continue
                val inserted = prediction.inserted.lowercase(Locale.ROOT)
                if (form == key) {
                    plain += count
                    if (inserted != key) plainWrong += count
                } else {
                    val outcome = when (inserted) {
                        form -> Restoration.RESTORED
                        key -> Restoration.LITERAL
                        else -> Restoration.WRONG
                    }
                    accented.add(outcome, count)
                    if (outcome != Restoration.RESTORED) missed.merge("$form (${outcome.name.lowercase()})", count, Long::plus)
                }
            }
            appendLine(
                "| $label | ${accented.words} | ${pct(ThresholdCounters.ratio(accented.restored, accented.words))} | " +
                    "${pct(ThresholdCounters.ratio(accented.wrong, accented.words))} | ${pct(ThresholdCounters.ratio(accented.literal, accented.words))} | " +
                    "${pct(ThresholdCounters.ratio(plainWrong, plain))} |",
            )
            examples += label to missed.entries.sortedByDescending { it.value }.take(EXAMPLES)
        }
        appendLine()
        for ((label, top) in examples) {
            if (top.isEmpty()) continue
            appendLine("Accents non restitués ($label) : " + top.joinToString(", ") { "`${it.key}` ${it.value}" } + ".")
            appendLine()
        }
    }

    /**
     * Corpus mixte (partie 3 de M0) : deux lexiques actifs, AZERTY, score de langue du moteur alimenté
     * par les mots voulus, phrase par phrase (fin de phrase signalée au score) ; référence : la
     * langue de la phrase seule active.
     */
    private fun bilingual(sets: Map<Language, LexiconSet>, sentences: List<List<MixedWord>>): String = buildString {
        val words = sentences.sumOf { it.size }
        val layout = KeyboardLayouts.AZERTY
        val both = Predictor(mapOf("fr" to sets.getValue(Language.FR), "en" to sets.getValue(Language.EN)), layout, cacheSize = config.cacheSize)
        val alone = Language.entries.associateWith { Predictor(mapOf(it.code to sets.getValue(it)), layout, cacheSize = config.cacheSize) }
        appendLine("## Corpus mixte — ${sentences.size} phrases, $words mots, AZERTY, deux lexiques actifs").appendLine()
        appendLine("| Fautes | Prudence | Auto (% mots) | Fausses (% auto) | Référence : fausses (% auto) | Attribuables à la langue (% des fausses) | Langue dominante correcte | Langue de la phrase correcte |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (rate in config.mixedErrorRates) {
            val typos = if (rate == 0.0) null else TypoModel(KeyboardLayout.AZERTY, rate)
            for (prudence in config.prudences) {
                log("M2 corpus mixte : fautes ${percent(rate)}, $prudence")
                val settings = PredictionSettings(prudence = prudence)
                val counters = MixedCounters(Thresholds(prudence.minProb, prudence.minRatio))
                val random = Random(config.seed)
                var state = both.initialLanguageState("fr")
                var sentenceCorrect = 0L
                var dominantCorrect = 0L
                val referenceStates = alone.mapValues { it.value.initialLanguageState(it.key.code) }
                for (sentence in sentences) {
                    for (word in sentence) {
                        val typed = typos?.apply(word.key, random) ?: word.key
                        if (state.dominant == word.language.code) dominantCorrect++
                        if (state.sentenceLanguage == word.language.code) sentenceCorrect++
                        val estimated = (1..typed.length).map { decision(both.decide(typed.take(it), typed.take(it), state, settings)) }
                        val own = alone.getValue(word.language)
                        val ownState = referenceStates.getValue(word.language)
                        val reference = (1..typed.length).map { decision(own.decide(typed.take(it), typed.take(it), ownState, settings)) }
                        counters.add(
                            SpaceRule.attentive(estimated, typed, word.key, ENGINE_THRESHOLDS).first,
                            SpaceRule.attentive(reference, typed, word.key, ENGINE_THRESHOLDS).first,
                        )
                        state = both.observe(state, word.key)
                    }
                    state = state.endSentence()
                }
                appendLine(
                    "| ${percent(rate)} | $prudence | ${pct(ThresholdCounters.ratio(counters.automatic, counters.words))} | " +
                        "${pct(counters.falseRate)} | ${pct(counters.referenceFalseRate)} | ${pct(counters.attributableShare)} | " +
                        "${pct(ThresholdCounters.ratio(dominantCorrect, counters.words))} | ${pct(ThresholdCounters.ratio(sentenceCorrect, counters.words))} |",
                )
            }
        }
        appendLine()
    }

    private fun keep(index: Int, percent: Int): Boolean =
        percent == 100 || Math.floorMod(SentencePartition.mix(index.toLong()), 100L) < percent

    companion object {
        /** Seuils neutres : la décision de dominance est celle du moteur (voir [decision]). */
        val ENGINE_THRESHOLDS: Thresholds = Thresholds(0.0, 1.0)

        private const val EXAMPLES = 12
        private const val ELISION_INITIALS = "aeiouyh"
        private val ELISIONS = setOf("c'", "d'", "j'", "l'", "m'", "n'", "s'", "t'", "qu'", "jusqu'", "lorsqu'", "puisqu'", "quoiqu'")

        private fun isElision(key: String): Boolean = key in ELISIONS

        /** Décision au format de la simulation M0 : le candidat n'est donné que si le moteur le valide. */
        fun decision(prediction: nextglyph.engine.predict.Prediction): Decision = when (prediction.case) {
            SpaceCase.WORD -> Decision(true, null, 0.0, 0.0)
            SpaceCase.DOMINANT -> Decision(false, prediction.candidates.first().key, 1.0, 0.0)
            else -> Decision(false, null, 0.0, 0.0)
        }

        /** Décideur de la simulation : moteur, texte tapé = clé (minuscules sans accent). */
        fun engineDecider(predictor: Predictor, state: LanguageState, settings: PredictionSettings): (String) -> Decision =
            { typed -> decision(predictor.decide(typed, typed, state, settings)) }

        /** Utilisateur attentif tapant [typed] pour obtenir [intended] (qui peut contenir `'` ou `-`). */
        fun restoration(typed: String, intended: String, decide: (String) -> Decision): Restoration {
            val decisions = (1..typed.length).map { decide(typed.take(it)) }
            val outcome = SpaceRule.attentive(decisions, typed, intended, ENGINE_THRESHOLDS).first
            return when {
                !outcome.isAutomatic -> Restoration.LITERAL
                outcome.isFalse -> Restoration.WRONG
                else -> Restoration.RESTORED
            }
        }

        fun layoutKind(layout: KeyboardLayout): LayoutKind = when (layout) {
            KeyboardLayout.AZERTY -> LayoutKind.AZERTY
            KeyboardLayout.QWERTY -> LayoutKind.QWERTY
        }
    }
}
