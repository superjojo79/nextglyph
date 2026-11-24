package nextglyph.tools.analysis

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.predict.ConfusionConfig
import nextglyph.engine.predict.ConfusionSet
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.PredictionConfig
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.Prudence
import nextglyph.engine.text.WordPrefix
import nextglyph.tools.analysis.CorpusAnalysis.Companion.f
import nextglyph.tools.ngrams.ClauseSplitter
import nextglyph.tools.ngrams.SentenceSplitter
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.SentencePartition
import nextglyph.tools.wordlists.Tokenizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Paramètres de l'analyse du contexte de la correction (issue #55, `docs/analysis/M55-context.md`).
 *
 * @property sentences nombre de phrases du corpus de test analysées par langue (échantillon déterministe).
 * @property prudences réglages de prudence mesurés.
 * @property cacheSize taille du cache de candidats du moteur, par langue.
 * @property threads fils d'exécution (une expérience par fil).
 * @property examples nombre d'exemples de fausses corrections listés par langue.
 * @property engine configuration du moteur mesuré (ensembles de confusion compris).
 */
data class ContextAnalysisConfig(
    val sentences: Int = 20_000,
    val prudences: List<Prudence> = Prudence.entries,
    val cacheSize: Int = 200_000,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val examples: Int = 10,
    val engine: PredictionConfig = PredictionConfig(),
) {
    init {
        require(sentences >= 1) { "Au moins une phrase est nécessaire ($sentences)." }
        require(prudences.isNotEmpty()) { "Au moins une prudence est nécessaire." }
        require(threads >= 1) { "Au moins un fil est nécessaire ($threads)." }
        require(examples >= 0) { "Le nombre d'exemples ne peut pas être négatif ($examples)." }
    }
}

/** Issues d'une confusion injectée : corrigée, proposée en premier dans la barre, laissée, ou changée à tort. */
class ConfusionCounters {
    var total = 0L; private set
    var fixed = 0L; private set
    var offered = 0L; private set
    var left = 0L; private set
    val wrong: Long get() = total - fixed - offered - left

    fun add(outcome: ConfusionOutcome) {
        total++
        when (outcome) {
            ConfusionOutcome.FIXED -> fixed++
            ConfusionOutcome.OFFERED -> offered++
            ConfusionOutcome.LEFT -> left++
            ConfusionOutcome.WRONG -> Unit
        }
    }
}

/** Issue d'une confusion injectée. */
enum class ConfusionOutcome { FIXED, OFFERED, LEFT, WRONG }

/** Fausses corrections sur du texte juste, pour une forme tapée sans accent d'un ensemble. */
class FalseChangeCounters {
    var occurrences = 0L; private set
    var changed = 0L; private set

    fun add(wasChanged: Boolean) {
        occurrences++
        if (wasChanged) changed++
    }
}

/** Résultats d'une expérience (une langue, une prudence). */
class ContextExperiment(val language: Language, val prudence: Prudence) {
    /** Mots validés du texte juste, et ceux que la règle du contexte a fait changer. */
    var words = 0L
    var ruleChanged = 0L

    /** Formes des ensembles de confusion tapées sans accent dans le texte juste, par forme. */
    val falseChanges = java.util.TreeMap<String, FalseChangeCounters>()

    /** Confusions injectées, par paire `typée → voulue`. */
    val injected = java.util.TreeMap<String, ConfusionCounters>()

    /** Mots tapés en majuscules (verrouillage) : validés, changés sans la protection, changés avec. */
    var capitalWords = 0L
    var capitalChangedUnprotected = 0L
    var capitalChangedProtected = 0L

    val examples = HashMap<String, Long>()
}

/**
 * Mesure du contexte de la correction (issue #55) sur le corpus de **test** : fausses corrections des formes
 * confondues sur du texte juste, taux de correction de confusions injectées (`à → a`, `où → ou`, `là → la`,
 * `dû → du`, `ses ↔ ces`, `est ↔ et`), et effet de la protection des sigles. Le moteur est celui de l'application
 * ([Predictor.predict], avec le texte avant le curseur reconstitué) ; les décisions sont comparées à celles du même
 * moteur et du même modèle sans ensemble de confusion.
 */
class ContextAnalysis(private val config: ContextAnalysisConfig = ContextAnalysisConfig(), private val log: (String) -> Unit = {}) {

    fun run(inputs: List<SpaceInput>): String {
        require(inputs.isNotEmpty()) { "Au moins une langue est nécessaire." }
        val jobs = ArrayList<Callable<ContextExperiment>>()
        for (input in inputs) for (prudence in config.prudences) jobs += Callable { experiment(input, prudence) }
        val pool = Executors.newFixedThreadPool(minOf(config.threads, jobs.size))
        val results = try {
            pool.invokeAll(jobs).map { it.get() }
        } finally {
            pool.shutdown()
        }
        return report(inputs, results)
    }

    private fun experiment(input: SpaceInput, prudence: Prudence): ContextExperiment {
        val language = input.input.language
        val started = System.nanoTime()
        val layout = KeyboardLayouts.of(EngineAnalysis.layoutKind(input.input.layout))
        val sets = mapOf(language.code to input.input.set)
        fun predictor(engine: PredictionConfig, model: Boolean) =
            Predictor(sets, layout, engine, cacheSize = config.cacheSize).also {
                if (model && input.model != null) it.useNgrams(mapOf(language.code to input.model))
            }
        val with = predictor(config.engine, model = true)
        // Même moteur et même modèle, sans ensemble de confusion : seule la décision par le mot précédent diffère.
        val bare = config.engine.confusion.copy(sets = emptyMap())
        val without = predictor(config.engine.copy(confusion = bare), model = true)
        val unprotected = predictor(config.engine.copy(confusion = bare.copy(protectTechnical = false)), model = true)
        val state = with.initialLanguageState(language.code)
        val settings = PredictionSettings(prudence = prudence)
        val result = ContextExperiment(language, prudence)
        val tokenizer = Tokenizer(language)
        val confusion = config.engine.confusion.sets[language.code].orEmpty()
        var done = 0
        for ((index, line) in input.input.test.withIndex()) {
            if (done >= config.sentences) break
            if (!keep(index, input.input.test.size)) continue
            done++
            for (sentence in SentenceSplitter.split(line)) {
                val clauses = ClauseSplitter.split(sentence).map { tokenizer.tokens(it) }
                val trial = Trial(with, without, unprotected, state, settings, confusion, result)
                trial.sentence(clauses)
            }
        }
        log("M55 ${language.code} : $prudence en ${(System.nanoTime() - started) / NANOS_PER_SECOND} s")
        return result
    }

    /** Échantillon déterministe d'environ [ContextAnalysisConfig.sentences] lignes réparties sur tout le corpus. */
    private fun keep(index: Int, total: Int): Boolean {
        if (total <= config.sentences) return true
        return Math.floorMod(SentencePartition.mix(index.toLong()), total.toLong()) < config.sentences
    }

    /** Une phrase : chaque mot est tapé tel qu'il est écrit, puis avec les erreurs injectées, le texte précédent étant juste. */
    private inner class Trial(
        val with: Predictor,
        val without: Predictor,
        val unprotected: Predictor,
        val state: nextglyph.engine.predict.LanguageState,
        val settings: PredictionSettings,
        val sets: List<ConfusionSet>,
        val result: ContextExperiment,
    ) {
        fun sentence(clauses: List<List<String>>) {
            val written = StringBuilder()
            for ((clauseIndex, clause) in clauses.withIndex()) {
                if (clauseIndex > 0) written.append(", ")
                var previous: String? = null
                for (token in clause) {
                    if (!token.endsWith("'")) word(written.toString(), token)
                    written.append(if (previous != null && !previous.endsWith("'")) " " else "").append(token)
                    previous = token
                }
            }
        }

        private fun word(before: String, token: String) {
            val text = window(before, token, previousSpace(before))
            val p = with.predict(text, state, settings)
            if (p.word.isEmpty()) return
            val base = without.predict(text, state, settings)
            result.words++
            val changed = p.inserted != base.inserted
            if (changed) {
                result.ruleChanged++
                result.examples.merge("${contextOf(before)} ${p.word} → ${p.inserted}", 1L, Long::plus)
            }
            val lower = token.lowercase(Locale.ROOT)
            if (isPlain(lower) && sets.any { it.indexOf(lower) >= 0 }) result.falseChanges.getOrPut(lower) { FalseChangeCounters() }.add(changed)
            inject(before, token)
            capitals(before, token)
        }

        /** Injecte la confusion inverse de [token] si c'est une forme d'un ensemble (`à` tapé `a`, `ses` tapé `ces`). */
        private fun inject(before: String, token: String) {
            val lower = token.lowercase(Locale.ROOT)
            for (set in sets) {
                val index = set.indexOf(lower)
                if (index < 0) continue
                for ((typedIndex, typed) in set.forms.withIndex()) {
                    if (typedIndex == index || !isPlain(typed)) continue
                    val text = window(before, matchCase(typed, token), previousSpace(before))
                    val p = with.predict(text, state, settings)
                    result.injected.getOrPut("$typed → $lower") { ConfusionCounters() }.add(outcome(p, typed, lower))
                }
            }
        }

        /** Mot tapé en majuscules, sans accent (verrouillage majuscule, aucune touche longue) : changé par le moteur sans la protection, jamais avec. */
        private fun capitals(before: String, token: String) {
            if (token.count(Char::isLetter) < 2) return
            val typed = capitals(token)
            val text = window(before, typed, previousSpace(before))
            val plain = unprotected.predict(text, state, settings)
            val protected = with.predict(text, state, settings)
            result.capitalWords++
            if (plain.inserted != plain.word) result.capitalChangedUnprotected++
            if (protected.inserted != protected.word) result.capitalChangedProtected++
        }

        private fun outcome(p: Prediction, typed: String, intended: String): ConfusionOutcome = when {
            p.inserted.lowercase(Locale.ROOT) == intended -> ConfusionOutcome.FIXED
            p.inserted.lowercase(Locale.ROOT) != typed -> ConfusionOutcome.WRONG
            p.bar.left?.text?.lowercase(Locale.ROOT) == intended -> ConfusionOutcome.OFFERED
            else -> ConfusionOutcome.LEFT
        }
    }

    private fun report(inputs: List<SpaceInput>, results: List<ContextExperiment>): String {
        val out = StringBuilder()
        out.appendLine("# Résultats bruts de `:tools:analyzeContext` (M55)").appendLine()
        out.appendLine("Configuration : $config").appendLine()
        for (input in inputs) {
            val language = input.input.language
            val mine = results.filter { it.language == language }.sortedBy { it.prudence.ordinal }
            out.appendLine("## ${language.code} (${input.input.layout}), ${if (input.model != null) "avec" else "sans"} modèle de mot suivant").appendLine()
            appendFalseChanges(out, mine)
            appendInjected(out, mine)
            appendCapitals(out, mine)
            appendExamples(out, mine.firstOrNull { it.prudence == Prudence.NORMAL })
        }
        return out.toString()
    }

    private fun appendFalseChanges(out: StringBuilder, results: List<ContextExperiment>) {
        out.appendLine("### Fausses corrections sur texte juste").appendLine()
        out.appendLine("| Prudence | Mots validés | Changés par le contexte | % des mots |")
        out.appendLine("|---|---|---|---|")
        for (r in results) out.appendLine("| ${r.prudence} | ${r.words} | ${r.ruleChanged} | ${pct(ratio(r.ruleChanged, r.words), 4)} |")
        out.appendLine()
        out.appendLine("| Forme tapée sans accent | Prudence | Occurrences | Changées à tort | % des occurrences |")
        out.appendLine("|---|---|---|---|---|")
        val forms = results.flatMap { it.falseChanges.keys }.toSortedSet()
        for (form in forms) for (r in results) {
            val c = r.falseChanges[form] ?: continue
            out.appendLine("| `$form` | ${r.prudence} | ${c.occurrences} | ${c.changed} | ${pct(ratio(c.changed, c.occurrences), 3)} |")
        }
        out.appendLine()
    }

    private fun appendInjected(out: StringBuilder, results: List<ContextExperiment>) {
        out.appendLine("### Confusions injectées").appendLine()
        out.appendLine("| Confusion (tapé → voulu) | Prudence | Occurrences | Corrigées | Proposées en premier | Laissées | Changées à tort |")
        out.appendLine("|---|---|---|---|---|---|---|")
        val pairs = results.flatMap { it.injected.keys }.toSortedSet()
        for (pair in pairs) for (r in results) {
            val c = r.injected[pair] ?: continue
            out.appendLine(
                "| `$pair` | ${r.prudence} | ${c.total} | ${pct(ratio(c.fixed, c.total))} | ${pct(ratio(c.offered, c.total))} | " +
                    "${pct(ratio(c.left, c.total))} | ${pct(ratio(c.wrong, c.total), 3)} |",
            )
        }
        if (pairs.isEmpty()) out.appendLine("| (aucun ensemble de confusion pour cette langue) | | | | | | |")
        out.appendLine()
    }

    private fun appendCapitals(out: StringBuilder, results: List<ContextExperiment>) {
        out.appendLine("### Mots tapés en majuscules (sigles)").appendLine()
        out.appendLine("| Prudence | Mots | Changés sans la protection | Changés avec la protection |")
        out.appendLine("|---|---|---|---|")
        for (r in results) {
            out.appendLine("| ${r.prudence} | ${r.capitalWords} | ${pct(ratio(r.capitalChangedUnprotected, r.capitalWords))} | ${pct(ratio(r.capitalChangedProtected, r.capitalWords), 3)} |")
        }
        out.appendLine()
    }

    private fun appendExamples(out: StringBuilder, normal: ContextExperiment?) {
        if (normal == null || config.examples == 0 || normal.examples.isEmpty()) return
        val top = normal.examples.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(config.examples)
        out.appendLine("Fausses corrections les plus fréquentes (Normale) : " + top.joinToString(", ") { "`${it.key}` ${it.value}" } + ".").appendLine()
    }

    private fun ratio(part: Long, total: Long): Double = ThresholdCounters.ratio(part, total)

    private fun pct(value: Double, decimals: Int = 2): String = f(value * 100, decimals) + " %"

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Texte vu par le moteur : [before] puis [typed] (séparés par une espace sauf après une élision), fenêtre de fin de texte. */
        internal fun window(before: String, typed: String, space: Boolean): String =
            (before + (if (space) " " else "") + typed).takeLast(WordPrefix.LOOKBACK * 2)

        /** Faux si le texte précédent est vide ou finit par une élision (le mot suit sans espace). */
        internal fun previousSpace(before: String): Boolean = before.isNotEmpty() && !before.endsWith("'") && !before.endsWith(", ")

        /** Dernier mot du texte précédent, pour les exemples. */
        internal fun contextOf(before: String): String = before.takeLast(CONTEXT_EXAMPLE).trim()

        private const val CONTEXT_EXAMPLE = 20

        /** [token] en majuscules et sans accent, tel qu'un doigt le tape en verrouillage majuscule. */
        internal fun capitals(token: String): String =
            java.text.Normalizer.normalize(token, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").uppercase(Locale.ROOT)

        /** Vrai si [form] n'a que des lettres sans accent : seule une forme ainsi tapée peut être corrigée (§5.8). */
        internal fun isPlain(form: String): Boolean = form.isNotEmpty() && form.all { it in 'a'..'z' }

        /** [typed] avec la casse de [token] (initiale majuscule). */
        internal fun matchCase(typed: String, token: String): String =
            if (token.firstOrNull()?.isUpperCase() == true) typed.replaceFirstChar { it.titlecase(Locale.ROOT) } else typed
    }
}
