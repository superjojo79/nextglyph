package nextglyph.tools.analysis

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.PredictionConfig
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.Prudence
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.TypedTap
import nextglyph.engine.predict.WordTaps
import nextglyph.tools.analysis.CorpusAnalysis.Companion.f
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.SentencePartition
import nextglyph.tools.wordlists.Tokenizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Paramètres de l'analyse des erreurs d'espace (issue #52, `docs/analysis/M52-spaces.md`).
 *
 * @property sentences nombre de phrases du corpus de test analysées par langue (échantillon déterministe).
 * @property prudences réglages de prudence mesurés.
 * @property sigma écart-type de la frappe (fraction de touche) du modèle gaussien qui simule les doigts.
 * @property seed graine : mêmes tirages pour tous les réglages.
 * @property cacheSize taille du cache de candidats du moteur, par langue.
 * @property threads fils d'exécution (une expérience par fil).
 * @property unigramsOnly ajoute une mesure sans modèle de bigrammes (réglage Normal) pour comparer.
 * @property examples nombre d'exemples de fausses corrections listés par langue.
 * @property engine configuration du moteur mesuré.
 */
data class SpaceAnalysisConfig(
    val sentences: Int = 20_000,
    val prudences: List<Prudence> = Prudence.entries,
    val sigma: Double = 0.2,
    val seed: Long = 20261123L,
    val cacheSize: Int = 200_000,
    val threads: Int = Runtime.getRuntime().availableProcessors(),
    val unigramsOnly: Boolean = true,
    val examples: Int = 12,
    val engine: PredictionConfig = PredictionConfig(),
) {
    init {
        require(sentences >= 1) { "Au moins une phrase est nécessaire ($sentences)." }
        require(prudences.isNotEmpty()) { "Au moins une prudence est nécessaire." }
        require(sigma.isFinite() && sigma >= 0.0) { "L'écart-type doit être positif ou nul ($sigma)." }
        require(threads >= 1) { "Au moins un fil est nécessaire ($threads)." }
        require(examples >= 0) { "Le nombre d'exemples ne peut pas être négatif ($examples)." }
    }
}

/** Données d'une langue pour l'analyse des erreurs d'espace. */
class SpaceInput(val input: LanguageInput, val model: NgramModel?)

/** Issues d'une famille d'erreurs injectées : corrigées exactement, laissées telles quelles, ou changées à tort. */
class RecallCounters {
    var total = 0L; private set
    var fixed = 0L; private set
    var left = 0L; private set
    val wrong: Long get() = total - fixed - left

    fun add(outcome: SpaceFix) {
        total++
        when (outcome) {
            SpaceFix.FIXED -> fixed++
            SpaceFix.LEFT -> left++
            SpaceFix.WRONG -> Unit
        }
    }

    fun merge(other: RecallCounters) {
        total += other.total
        fixed += other.fixed
        left += other.left
    }
}

/** Issue d'une erreur d'espace injectée. */
enum class SpaceFix { FIXED, LEFT, WRONG }

/** Fausses corrections sur du texte juste : mots validés et corrections d'espace appliquées, par nature. */
class FalsePositiveCounters {
    var words = 0L; private set
    var notWords = 0L; private set
    var splits = 0L; private set
    var joins = 0L; private set
    val examples = HashMap<String, Long>()

    val applied: Long get() = splits + joins

    fun add(prediction: Prediction) {
        words++
        if (prediction.case != SpaceCase.WORD) notWords++
        val kind = SpaceErrorAnalysis.kindOf(prediction) ?: return
        if (kind == SpaceEditKind.JOIN) joins++ else splits++
        examples.merge("${prediction.span} → ${prediction.inserted}", 1L, Long::plus)
    }

    fun merge(other: FalsePositiveCounters) {
        words += other.words
        notWords += other.notWords
        splits += other.splits
        joins += other.joins
        other.examples.forEach { (k, v) -> examples.merge(k, v, Long::plus) }
    }
}

/** Nature d'une correction d'espace appliquée par la validation. */
enum class SpaceEditKind { SPLIT, JOIN }

/** Résultats d'une expérience (une langue, une prudence, avec ou sans modèle de bigrammes). */
class SpaceExperiment(val language: Language, val prudence: Prudence, val withModel: Boolean) {
    /** Texte juste, frappes dans leur touche. */
    val correct = FalsePositiveCounters()

    /** Texte tapé avec le modèle gaussien (fautes de frappe naturelles). */
    val typos = FalsePositiveCounters()
    var letters = 0L
    var wrongLetters = 0L
    val spaceBar = RecallCounters()
    val glued = RecallCounters()
    val extra = RecallCounters()

    /** Espace en trop dont les deux fragments sont inconnus du lexique : les seuls cas corrigeables. */
    val extraUnknown = RecallCounters()

    /** Espace en trop dont le premier fragment a déjà été remplacé à sa validation (avant la saisie du second). */
    var extraFirstCorrected = 0L

    /** Espaces en trop à fragments inconnus non corrigées : `fragment fragment → texte final`, avec leur nombre. */
    val extraMisses = HashMap<String, Long>()
}

/**
 * Mesure des corrections d'erreurs d'espace (issue #52) sur le corpus de **test** : fausses corrections sur
 * du texte juste, puis taux de correction de trois erreurs injectées (espace tapée sur `n`, `b`, `v` ou `c` près
 * de la barre d'espace, espace oubliée, espace en trop). Le moteur est celui de l'application
 * (`Predictor.decide`), avec les frappes simulées par [GaussianTapModel] et, si disponible, le modèle de bigrammes.
 */
class SpaceErrorAnalysis(private val config: SpaceAnalysisConfig = SpaceAnalysisConfig(), private val log: (String) -> Unit = {}) {

    fun run(inputs: List<SpaceInput>): String {
        require(inputs.isNotEmpty()) { "Au moins une langue est nécessaire." }
        val jobs = ArrayList<Callable<SpaceExperiment>>()
        for (input in inputs) {
            val models = if (input.model != null) listOf(true) else listOf(false)
            for (withModel in models) for (prudence in config.prudences) jobs += Callable { experiment(input, prudence, withModel) }
            if (config.unigramsOnly && input.model != null) jobs += Callable { experiment(input, Prudence.NORMAL, false) }
        }
        val pool = Executors.newFixedThreadPool(minOf(config.threads, jobs.size))
        val results = try {
            pool.invokeAll(jobs).map { it.get() }
        } finally {
            pool.shutdown()
        }
        return report(inputs, results)
    }

    private fun experiment(input: SpaceInput, prudence: Prudence, withModel: Boolean): SpaceExperiment {
        val language = input.input.language
        val started = System.nanoTime()
        val layout = KeyboardLayouts.of(EngineAnalysis.layoutKind(input.input.layout))
        val predictor = Predictor(mapOf(language.code to input.input.set), layout, config.engine, cacheSize = config.cacheSize)
        if (withModel && input.model != null) predictor.useNgrams(mapOf(language.code to input.model))
        val state = predictor.initialLanguageState(language.code)
        val settings = PredictionSettings(prudence = prudence)
        val taps = GaussianTapModel(layout, config.sigma)
        val tokenizer = Tokenizer(language)
        val random = Random(config.seed)
        val result = SpaceExperiment(language, prudence, withModel)
        val trial = Trial(predictor, state, settings, taps, input.input.set, random, result)
        var done = 0
        for ((index, sentence) in input.input.test.withIndex()) {
            if (done >= config.sentences) break
            if (!keep(index, input.input.test.size)) continue
            done++
            trial.sentence(CorpusWords.keys(sentence, tokenizer).filterNotNull())
        }
        log("M52 ${language.code} : $prudence${if (withModel) "" else ", sans bigrammes"} en ${(System.nanoTime() - started) / NANOS_PER_SECOND} s")
        return result
    }

    /** Échantillon déterministe d'environ [SpaceAnalysisConfig.sentences] phrases réparties sur tout le corpus. */
    private fun keep(index: Int, total: Int): Boolean {
        if (total <= config.sentences) return true
        return Math.floorMod(SentencePartition.mix(index.toLong()), total.toLong()) < config.sentences
    }

    /** Une expérience : les trois injections et les deux mesures de fausses corrections, phrase par phrase. */
    private inner class Trial(
        val predictor: Predictor,
        val state: nextglyph.engine.predict.LanguageState,
        val settings: PredictionSettings,
        val model: GaussianTapModel,
        val set: LexiconSet,
        val random: Random,
        val result: SpaceExperiment,
    ) {
        fun sentence(keys: List<String>) {
            correctText(keys)
            typedText(keys)
            for (i in keys.indices) {
                val previous = keys.getOrNull(i - 1)
                if (i + 1 < keys.size && isPlain(keys[i]) && isPlain(keys[i + 1])) {
                    val known = set.containsKey(keys[i]) && set.containsKey(keys[i + 1])
                    if (known) {
                        spaceBar(previous, keys[i], keys[i + 1])
                        glued(previous, keys[i], keys[i + 1])
                    }
                }
                if (isPlain(keys[i]) && keys[i].length >= MIN_EXTRA_LENGTH && set.containsKey(keys[i])) extra(previous, keys[i])
            }
        }

        /** Texte juste : chaque mot est tapé tel quel, chaque frappe dans sa touche. */
        private fun correctText(keys: List<String>) {
            var previous: String? = null
            for (key in keys) {
                val p = decide(key, inKey(key), previous)
                result.correct.add(p)
                previous = lastWord(p.inserted)
            }
        }

        /** Texte tapé avec les fautes naturelles du modèle gaussien. */
        private fun typedText(keys: List<String>) {
            var previous: String? = null
            for (key in keys) {
                val word = model.type(key, random)
                result.letters += key.length
                result.wrongLetters += key.indices.count { key[it] != word.typed[it] }
                val typed = CorpusWords.keyOf(word.typed) ?: continue
                val p = decide(typed, WordTaps(word.taps), previous)
                result.typos.add(p)
                previous = lastWord(p.inserted)
            }
        }

        /** `w1` + `n`/`b`/`v`/`c` + `w2` avec une frappe dans le bas de la touche (espace tapée trop haut). */
        private fun spaceBar(previous: String?, first: String, second: String) {
            val letter = SPACE_BAR_LETTERS[random.nextInt(SPACE_BAR_LETTERS.length)]
            val taps = inKey(first) + TypedTap(letter, random.nextDouble(-0.4, 0.4), random.nextDouble(0.25, 0.5)) + inKey(second)
            val typed = first + letter + second
            val p = decide(typed, taps, previous)
            result.spaceBar.add(outcome(p, typed, "$first $second"))
        }

        /** `w1w2` : espace oubliée. */
        private fun glued(previous: String?, first: String, second: String) {
            val typed = first + second
            val p = decide(typed, inKey(typed), previous)
            result.glued.add(outcome(p, typed, "$first $second"))
        }

        /** Un mot coupé en deux par une espace en trop, à une position intérieure tirée au hasard. */
        private fun extra(previous: String?, word: String) {
            val cut = random.nextInt(1, word.length)
            val head = word.substring(0, cut)
            val tail = word.substring(cut)
            val first = decide(head, inKey(head), previous)
            val before = first.inserted
            val second = decide(tail, inKey(tail), lastWord(before))
            val final = if (second.span != second.word) before.substringBeforeLast(' ', "").let { if (it.isEmpty()) second.inserted else "$it ${second.inserted}" } else "$before ${second.inserted}"
            val finalKey = keyOf(final)
            val fix = when (finalKey) {
                word -> SpaceFix.FIXED
                "$head $tail" -> SpaceFix.LEFT
                else -> SpaceFix.WRONG
            }
            result.extra.add(fix)
            if (first.case == SpaceCase.DOMINANT) result.extraFirstCorrected++
            if (!set.containsKey(head) && !set.containsKey(tail)) {
                result.extraUnknown.add(fix)
                if (fix != SpaceFix.FIXED) result.extraMisses.merge("$head $tail → $final", 1L, Long::plus)
            }
        }

        private fun outcome(p: Prediction, typed: String, intended: String): SpaceFix {
            val final = keyOf(p.inserted)
            return when {
                final == intended -> SpaceFix.FIXED
                final == typed -> SpaceFix.LEFT
                else -> SpaceFix.WRONG
            }
        }

        private fun decide(typed: String, taps: List<TypedTap?>, previous: String?): Prediction =
            decide(typed, WordTaps(taps), previous)

        private fun decide(typed: String, taps: WordTaps, previous: String?): Prediction =
            predictor.decide(typed, typed, state, settings, taps, previous)

        /** Frappes dans la touche voulue : tirées jusqu'à tomber sur la lettre (au centre après [MAX_TRIES] essais). */
        private fun inKey(word: String): List<TypedTap?> = word.map { c ->
            repeat(MAX_TRIES) {
                val tap = model.tap(c, random) ?: return@map null
                if (tap.letter == c) return@map tap
            }
            if (c in 'a'..'z') TypedTap(c, 0.0, 0.0) else null
        }
    }

    private fun report(inputs: List<SpaceInput>, results: List<SpaceExperiment>): String {
        val out = StringBuilder()
        out.appendLine("# Résultats bruts de `:tools:analyzeSpaces` (M52)").appendLine()
        out.appendLine("Configuration : $config").appendLine()
        for (input in inputs) {
            val language = input.input.language
            val mine = results.filter { it.language == language }
            val main = mine.filter { it.withModel == (input.model != null) }.sortedBy { it.prudence.ordinal }
            out.appendLine("## ${language.code} (${input.input.layout}), ${if (input.model != null) "avec" else "sans"} bigrammes").appendLine()
            appendFalsePositives(out, mine)
            appendRecall(out, mine.sortedWith(compareBy({ !it.withModel }, { it.prudence.ordinal })))
            appendExamples(out, main.firstOrNull { it.prudence == Prudence.NORMAL })
        }
        return out.toString()
    }

    private fun appendFalsePositives(out: StringBuilder, results: List<SpaceExperiment>) {
        out.appendLine("### Fausses corrections sur texte juste").appendLine()
        out.appendLine("| Texte | Prudence | Bigrammes | Mots | Non-mots (% mots) | Coupures (% mots) | Espaces supprimées (% mots) | Total (% mots) |")
        out.appendLine("|---|---|---|---|---|---|---|---|")
        for (r in results.sortedWith(compareBy({ !it.withModel }, { it.prudence.ordinal }))) {
            for ((label, c) in listOf("juste" to r.correct, "frappes simulées (σ ${f(config.sigma, 2)}, ${pct(ratio(r.wrongLetters, r.letters))} de lettres fausses)" to r.typos)) {
                out.appendLine(
                    "| $label | ${r.prudence} | ${yesNo(r.withModel)} | ${c.words} | ${pct(ratio(c.notWords, c.words))} | " +
                        "${pct(ratio(c.splits, c.words), 3)} | ${pct(ratio(c.joins, c.words), 3)} | ${pct(ratio(c.applied, c.words), 3)} |",
                )
            }
        }
        out.appendLine()
    }

    private fun appendRecall(out: StringBuilder, results: List<SpaceExperiment>) {
        out.appendLine("### Erreurs d'espace injectées").appendLine()
        out.appendLine("| Erreur | Prudence | Occurrences | Corrigées exactement | Laissées telles quelles | Changées à tort | Bigrammes |")
        out.appendLine("|---|---|---|---|---|---|---|")
        for (r in results) {
            for ((label, c) in listOf(
                "espace tapée sur n, b, v ou c" to r.spaceBar,
                "espace oubliée" to r.glued,
                "espace en trop" to r.extra,
                "espace en trop, deux fragments inconnus" to r.extraUnknown,
            )) {
                out.appendLine(
                    "| $label | ${r.prudence} | ${c.total} | ${pct(ratio(c.fixed, c.total))} | ${pct(ratio(c.left, c.total))} | ${pct(ratio(c.wrong, c.total))} | ${yesNo(r.withModel)} |",
                )
            }
            out.appendLine("| espace en trop, premier fragment déjà corrigé | ${r.prudence} | ${r.extraFirstCorrected} | | | | ${yesNo(r.withModel)} |")
        }
        out.appendLine()
    }

    private fun appendExamples(out: StringBuilder, normal: SpaceExperiment?) {
        if (normal == null || config.examples == 0) return
        val misses = normal.extraMisses.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(config.examples)
        if (misses.isNotEmpty()) {
            out.appendLine("Espaces en trop non corrigées, fragments inconnus (Normale) : " + misses.joinToString(", ") { "`${it.key}` ${it.value}" } + ".").appendLine()
        }
        for ((label, c) in listOf("texte juste" to normal.correct, "frappes simulées" to normal.typos)) {
            val top = c.examples.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(config.examples)
            if (top.isEmpty()) continue
            out.appendLine("Fausses corrections les plus fréquentes, $label (Normale) : " + top.joinToString(", ") { "`${it.key}` ${it.value}" } + ".").appendLine()
        }
    }

    private fun yesNo(value: Boolean): String = if (value) "oui" else "non"

    private fun ratio(part: Long, total: Long): Double = ThresholdCounters.ratio(part, total)

    private fun pct(value: Double, decimals: Int = 2): String = f(value * 100, decimals) + " %"

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MAX_TRIES = 20
        private const val MIN_EXTRA_LENGTH = 4
        private const val SPACE_BAR_LETTERS = "nbvc"

        /** Mot de lettres `a`–`z` seules (ni apostrophe ni trait d'union). */
        private fun isPlain(key: String): Boolean = key.isNotEmpty() && key.all { it in 'a'..'z' }

        /** Dernier mot de [text] (après la dernière espace). */
        fun lastWord(text: String): String = text.substringAfterLast(' ')

        /** Clés des mots de [text] séparés par des espaces, ou `?` si l'un d'eux n'a pas de clé. */
        fun keyOf(text: String): String = text.split(' ').joinToString(" ") { CorpusWords.keyOf(it) ?: "?" }

        /**
         * Nature de la correction d'espace appliquée par [prediction] (`null` : aucune). Une coupure insère deux
         * mots (`je suis`) ; une espace supprimée remplace aussi le mot précédent ([Prediction.span]).
         */
        fun kindOf(prediction: Prediction): SpaceEditKind? {
            if (prediction.case != SpaceCase.DOMINANT) return null
            val top = prediction.candidates.firstOrNull() ?: return null
            if (' ' !in top.key) return null
            return if (top.replaces != null) SpaceEditKind.JOIN else SpaceEditKind.SPLIT
        }
    }
}
