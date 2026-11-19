package nextglyph.tools.analysis

import kotlin.random.Random

/**
 * Seuils de la validation par espace (§5.5).
 *
 * @property minLength nombre minimal de lettres tapées pour qu'une validation automatique soit
 *   possible (1 = aucune restriction, règle actuelle de la spec).
 */
data class Thresholds(val minProb: Double, val minRatio: Double, val minLength: Int = 1) {
    init {
        require(minProb in 0.0..1.0) { "minProb doit être dans [0, 1] ($minProb)." }
        require(minRatio >= 1.0) { "minRatio doit être au moins 1 ($minRatio)." }
        require(minLength >= 1) { "minLength doit être au moins 1 ($minLength)." }
    }

    override fun toString(): String = "minProb=$minProb minRatio=$minRatio minLength=$minLength"
}

/** Ce que produit un appui sur espace (§5.5), comparé au mot voulu. */
enum class SpaceOutcome(val isAutomatic: Boolean, val isFalse: Boolean) {
    /** Texte tapé inséré tel quel, et c'est le mot voulu. */
    LITERAL_INTENDED(false, false),

    /** Texte tapé inséré tel quel, et ce n'est pas le mot voulu (préfixe, faute non corrigée). */
    LITERAL_OTHER(false, false),

    /** Validation automatique correcte d'un préfixe tapé sans faute (complétion). */
    AUTO_COMPLETION(true, false),

    /** Validation automatique correcte d'un texte contenant une faute (correction). */
    AUTO_CORRECTION(true, false),

    /** Fausse validation : texte tapé sans faute (mot hors lexique, ou complétion vers un autre mot). */
    AUTO_WRONG(true, true),

    /** Fausse validation : le texte tapé contenait une faute. */
    AUTO_WRONG_TYPO(true, true),
}

/** Règle de la validation par espace (§5.5) et comportement d'un utilisateur attentif. */
object SpaceRule {

    /**
     * Issue d'un appui sur espace après les `k = typed.length` premières lettres tapées [typed]
     * (éventuellement fautives) du mot voulu [intended], le moteur ayant pris la décision [decision].
     */
    fun outcome(decision: Decision, typed: String, intended: String, thresholds: Thresholds): SpaceOutcome {
        val clean = intended.startsWith(typed)
        return when {
            decision.isWord || typed.length < thresholds.minLength || !decision.dominates(thresholds) ->
                if (typed == intended) SpaceOutcome.LITERAL_INTENDED else SpaceOutcome.LITERAL_OTHER
            decision.top == intended -> if (clean) SpaceOutcome.AUTO_COMPLETION else SpaceOutcome.AUTO_CORRECTION
            else -> if (clean) SpaceOutcome.AUTO_WRONG else SpaceOutcome.AUTO_WRONG_TYPO
        }
    }

    /**
     * Utilisateur attentif : il tape [typed] lettre par lettre et appuie sur espace dès que la
     * touche espace affiche le mot voulu (§5.5, « Prévisibilité »), sinon après la dernière lettre.
     * Il ne corrige pas ses fautes. [decisions]`[k − 1]` est la décision après `k` lettres.
     *
     * @return l'issue et le nombre de lettres tapées.
     */
    fun attentive(decisions: List<Decision>, typed: String, intended: String, thresholds: Thresholds): Pair<SpaceOutcome, Int> {
        require(decisions.size == typed.length && typed.isNotEmpty()) {
            "Une décision par lettre tapée est attendue (${decisions.size} pour « $typed »)."
        }
        for (k in 1 until typed.length) {
            val outcome = outcome(decisions[k - 1], typed.substring(0, k), intended, thresholds)
            if (outcome == SpaceOutcome.AUTO_COMPLETION || outcome == SpaceOutcome.AUTO_CORRECTION) return outcome to k
        }
        return outcome(decisions.last(), typed, intended, thresholds) to typed.length
    }
}

/** Modèle de fautes : chaque lettre est remplacée, avec la probabilité [rate], par une voisine tirée uniformément. */
class TypoModel(private val layout: KeyboardLayout, val rate: Double) {
    init {
        require(rate in 0.0..1.0) { "Le taux de fautes doit être dans [0, 1] ($rate)." }
    }

    /** [word] tapé avec des fautes de substitution ; les caractères hors disposition (`'`, `-`) sont intacts. */
    fun apply(word: String, random: Random): String {
        val chars = word.toCharArray()
        for (i in chars.indices) {
            val neighbors = layout.neighbors(chars[i])
            if (neighbors.isNotEmpty() && random.nextDouble() < rate) chars[i] = neighbors[random.nextInt(neighbors.size)]
        }
        return String(chars)
    }
}

/** Compteurs d'une simulation pour un jeu de seuils. */
class ThresholdCounters(val thresholds: Thresholds) {
    /** Utilisateur attentif : occurrences par issue. */
    val attentive = LongArray(SpaceOutcome.entries.size)

    /** Utilisateur attentif : issues par longueur du mot (1 … [LENGTH_BUCKETS], la dernière regroupant les plus longs). */
    val attentiveByLength = Array(LENGTH_BUCKETS) { LongArray(SpaceOutcome.entries.size) }

    /** Espace après `k` lettres, pour tout `k` : issues par `k` (1 … [LENGTH_BUCKETS]). */
    val everyPrefix = Array(LENGTH_BUCKETS) { LongArray(SpaceOutcome.entries.size) }

    /** Frappes de l'utilisateur attentif (lettres + espace) et frappes sans validation automatique. */
    var keystrokes = 0L; internal set
    var baselineKeystrokes = 0L; internal set

    /** Nombre d'occurrences simulées. */
    val words: Long get() = attentive.sum()

    /** Validations automatiques (correctes ou non) de l'utilisateur attentif. */
    val automatic: Long get() = SpaceOutcome.entries.filter { it.isAutomatic }.sumOf { attentive[it.ordinal] }

    /** Fausses validations de l'utilisateur attentif. */
    val falseValidations: Long get() = SpaceOutcome.entries.filter { it.isFalse }.sumOf { attentive[it.ordinal] }

    /** Part des validations automatiques qui sont fausses (approximation des annulations du §15.2). */
    val falseRate: Double get() = ratio(falseValidations, automatic)

    /** Part des mots complétés ou corrigés correctement par l'espace. */
    val correctAutoRate: Double get() = ratio(automatic - falseValidations, words)

    /** Part des mots où une validation automatique fausse a lieu. */
    val falsePerWord: Double get() = ratio(falseValidations, words)

    /** Frappes par caractère (mot + espace), sans le coût de réparation des fausses validations. */
    val kspc: Double get() = ratio(keystrokes, baselineKeystrokes)

    /** Frappes par caractère en comptant 2 frappes (⌫ d'annulation, espace) par fausse validation. */
    val kspcWithRepair: Double get() = ratio(keystrokes + REPAIR_KEYSTROKES * falseValidations, baselineKeystrokes)

    /** Comme [falseRate], si l'utilisateur appuie sur espace après `k` lettres pour tous les `k`. */
    fun everyPrefixFalseRate(bucket: Int? = null): Double {
        val rows = if (bucket == null) everyPrefix.toList() else listOf(everyPrefix[bucket])
        val auto = rows.sumOf { row -> SpaceOutcome.entries.filter { it.isAutomatic }.sumOf { row[it.ordinal] } }
        val wrong = rows.sumOf { row -> SpaceOutcome.entries.filter { it.isFalse }.sumOf { row[it.ordinal] } }
        return ratio(wrong, auto)
    }

    /** Part des mots de la tranche de longueur [bucket] (0 = 1 lettre) qui donnent une fausse validation. */
    fun falsePerWordByLength(bucket: Int): Double {
        val row = attentiveByLength[bucket]
        return ratio(SpaceOutcome.entries.filter { it.isFalse }.sumOf { row[it.ordinal] }, row.sum())
    }

    companion object {
        const val LENGTH_BUCKETS: Int = 8
        const val REPAIR_KEYSTROKES: Long = 2

        fun ratio(numerator: Long, denominator: Long): Double =
            if (denominator == 0L) 0.0 else numerator.toDouble() / denominator
    }
}

/**
 * Partie 2 du §15.1 : simule la frappe des mots du corpus de test et l'appui sur espace, pour
 * chaque jeu de seuils de [grid].
 */
class SpaceSimulation(val grid: List<Thresholds>) {

    val counters: List<ThresholdCounters> = grid.map(::ThresholdCounters)

    /**
     * Ajoute [count] occurrences du mot [intended] tapé [typed] (même longueur, fautes de
     * substitution comprises). [decide] donne la décision du moteur pour un texte tapé.
     */
    fun record(intended: String, typed: String, count: Long, decide: (String) -> Decision) {
        require(typed.length == intended.length && intended.isNotEmpty()) {
            "Le mot tapé « $typed » doit avoir la longueur du mot voulu « $intended »."
        }
        if (count == 0L) return
        val decisions = (1..typed.length).map { decide(typed.substring(0, it)) }
        val lengthBucket = minOf(intended.length, ThresholdCounters.LENGTH_BUCKETS) - 1
        for (counter in counters) {
            val (outcome, k) = SpaceRule.attentive(decisions, typed, intended, counter.thresholds)
            counter.attentive[outcome.ordinal] += count
            counter.attentiveByLength[lengthBucket][outcome.ordinal] += count
            counter.keystrokes += (k + 1) * count
            counter.baselineKeystrokes += (intended.length + 1) * count
            for (j in 1..typed.length) {
                val each = SpaceRule.outcome(decisions[j - 1], typed.substring(0, j), intended, counter.thresholds)
                counter.everyPrefix[minOf(j, ThresholdCounters.LENGTH_BUCKETS) - 1][each.ordinal] += count
            }
        }
    }

    /**
     * Simule tout le corpus [words] (clé → occurrences). Sans [typos], chaque mot est tapé sans
     * faute. Avec [typos], chaque occurrence reçoit ses propres fautes, tirées de [random] dans
     * l'ordre de [words] : le résultat est reproductible.
     */
    fun run(words: Map<String, Long>, decide: (String) -> Decision, typos: TypoModel? = null, random: Random = Random(0)) {
        for ((word, count) in words) {
            if (typos == null) {
                record(word, word, count, decide)
                continue
            }
            var clean = 0L
            repeat(count.toInt()) {
                val typed = typos.apply(word, random)
                if (typed == word) clean++ else record(word, typed, 1, decide)
            }
            record(word, word, clean, decide)
        }
    }
}
