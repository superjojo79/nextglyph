package nextglyph.tools.analysis

import nextglyph.engine.lexicon.Lexicon
import nextglyph.tools.wordlists.Language
import kotlin.random.Random

/** Un mot du corpus mixte et la langue de sa phrase. */
data class MixedWord(val key: String, val language: Language)

/**
 * Indice apporté par un mot validé au score de langue (§10.6), sous forme de deux poids
 * `(français, anglais)`.
 */
enum class EvidenceMode {
    /**
     * Appartenance au lexique (règle actuelle du §10.6) : `(1, 0)`, `(0, 1)`, `(1, 1)` pour un mot
     * des deux lexiques, `(0, 0)` pour un mot inconnu.
     */
    MEMBERSHIP,

    /**
     * Fréquences relatives : `(f_FR, f_EN) / (f_FR + f_EN)`, où `f_L` est la fréquence du mot
     * divisée par la masse totale du lexique `L`. Un mot commun aux deux langues pèse selon sa
     * fréquence dans chacune (`a`, `on`, `son`).
     */
    FREQUENCY,
}

/** Calcule l'indice de langue d'un mot à partir des deux lexiques. */
class LanguageEvidence(private val fr: Lexicon, private val en: Lexicon, val mode: EvidenceMode) {
    private val frMass = fr.distribution("").totalMass
    private val enMass = en.distribution("").totalMass
    private val cache = HashMap<String, Pair<Double, Double>>()

    /** Poids `(français, anglais)` du mot de clé [key]. */
    fun of(key: String): Pair<Double, Double> = cache.getOrPut(key) {
        val f = fr.displayForms(key).sumOf { it.frequency.toDouble() } / frMass
        val e = en.displayForms(key).sumOf { it.frequency.toDouble() } / enMass
        when {
            f == 0.0 && e == 0.0 -> 0.0 to 0.0
            mode == EvidenceMode.MEMBERSHIP -> (if (f > 0.0) 1.0 else 0.0) to (if (e > 0.0) 1.0 else 0.0)
            else -> f / (f + e) to e / (f + e)
        }
    }
}

/**
 * Score de langue (§10.6) : fenêtre glissante des [window] derniers mots validés.
 * `π_FR = (Σ poids_FR + prior · initialFr) / (Σ poids_FR + Σ poids_EN + prior)`, avec `prior = 1` :
 * fenêtre vide → `π_FR = initialFr` (langue préférée de départ).
 */
class LanguageScore(private val window: Int, private val initialFr: Double, private val evidence: (String) -> Pair<Double, Double>) {
    init {
        require(window >= 1) { "La fenêtre doit contenir au moins un mot ($window)." }
        require(initialFr in 0.0..1.0) { "La part initiale du français doit être dans [0, 1] ($initialFr)." }
    }

    private val recent = ArrayDeque<Pair<Double, Double>>()

    /** `π_FR` courant ; `π_EN = 1 − π_FR`. */
    val piFr: Double
        get() {
            val fr = recent.sumOf { it.first }
            val en = recent.sumOf { it.second }
            return (fr + PRIOR * initialFr) / (fr + en + PRIOR)
        }

    /** Langue dominante (le français à égalité). */
    val dominant: Language get() = if (piFr >= 0.5) Language.FR else Language.EN

    /** Prend en compte le mot validé [key]. */
    fun observe(key: String) {
        recent.addLast(evidence(key))
        if (recent.size > window) recent.removeFirst()
    }

    companion object {
        const val PRIOR: Double = 1.0
    }
}

/**
 * Convergence du score de langue sur le corpus mixte.
 *
 * @property accuracy part des mots pour lesquels la langue dominante, avant la frappe du mot,
 *   est celle de sa phrase.
 * @property delays pour chaque changement de langue, nombre de mots tapés avant que la langue
 *   dominante devienne la nouvelle langue (0 = immédiat) ; un changement jamais rattrapé avant le
 *   suivant compte la longueur du segment et est compté dans [censored].
 */
class Convergence(val words: Int, val accuracy: Double, val delays: List<Int>, val censored: Int) {
    val switches: Int get() = delays.size
    val mean: Double get() = if (delays.isEmpty()) 0.0 else delays.average()
    fun percentile(p: Int): Int = if (delays.isEmpty()) 0 else delays.sorted()[((delays.size - 1) * p) / 100]
}

/** Partie 3 du §15.1 : bilinguisme sur le corpus mixte étiqueté. */
object BilingualAnalysis {

    /** Mesure la convergence d'un score neuf créé par [newScore] ; les mots validés sont les mots voulus. */
    fun convergence(words: List<MixedWord>, newScore: () -> LanguageScore): Convergence {
        val score = newScore()
        var correct = 0
        val delays = ArrayList<Int>()
        var censored = 0
        var pending = -1 // mots écoulés depuis le dernier changement non encore rattrapé, -1 sinon
        for ((index, word) in words.withIndex()) {
            if (index > 0 && word.language != words[index - 1].language) {
                if (pending >= 0) {
                    delays += pending
                    censored++
                }
                pending = 0
            }
            val ok = score.dominant == word.language
            if (ok) correct++
            if (pending >= 0) {
                if (ok) {
                    delays += pending
                    pending = -1
                } else {
                    pending++
                }
            }
            score.observe(word.key)
        }
        if (pending >= 0) {
            delays += pending
            censored++
        }
        return Convergence(words.size, if (words.isEmpty()) 0.0 else correct.toDouble() / words.size, delays, censored)
    }

    /**
     * Fausses validations du corpus mixte (utilisateur attentif, §5.5) avec le score de langue
     * [newScore], comparées à une référence où la langue de la phrase est connue et seule active
     * (`π = 1`). [sources] donne les candidats de chaque langue.
     */
    fun falseValidations(
        words: List<MixedWord>,
        sources: Map<Language, CandidateSource>,
        thresholds: List<Thresholds>,
        newScore: () -> LanguageScore,
        typos: TypoModel? = null,
        random: Random = Random(0),
    ): List<MixedCounters> {
        val fr = sources.getValue(Language.FR)
        val en = sources.getValue(Language.EN)
        val counters = thresholds.map(::MixedCounters)
        val score = newScore()
        for (word in words) {
            val typed = typos?.apply(word.key, random) ?: word.key
            val pi = score.piFr
            val mixed = (1..typed.length).map {
                val t = typed.substring(0, it)
                CandidateMixer.combine(listOf(pi to fr.lookup(t), (1.0 - pi) to en.lookup(t)))
            }
            val own = sources.getValue(word.language)
            val oracle = (1..typed.length).map { CandidateMixer.combine(listOf(1.0 to own.lookup(typed.substring(0, it)))) }
            for (counter in counters) {
                val estimated = SpaceRule.attentive(mixed, typed, word.key, counter.thresholds).first
                val reference = SpaceRule.attentive(oracle, typed, word.key, counter.thresholds).first
                counter.add(estimated, reference)
            }
            score.observe(word.key)
        }
        return counters
    }
}

/** Compteurs des fausses validations du corpus mixte pour un jeu de seuils. */
class MixedCounters(val thresholds: Thresholds) {
    var words = 0L; private set
    var automatic = 0L; private set
    var falseValidations = 0L; private set
    var referenceAutomatic = 0L; private set
    var referenceFalse = 0L; private set

    /** Fausses avec le score de langue, mais pas avec la langue connue : attribuables à la mauvaise langue. */
    var attributable = 0L; private set

    fun add(estimated: SpaceOutcome, reference: SpaceOutcome) {
        words++
        if (estimated.isAutomatic) automatic++
        if (estimated.isFalse) falseValidations++
        if (reference.isAutomatic) referenceAutomatic++
        if (reference.isFalse) referenceFalse++
        if (estimated.isFalse && !reference.isFalse) attributable++
    }

    val falseRate: Double get() = ThresholdCounters.ratio(falseValidations, automatic)
    val referenceFalseRate: Double get() = ThresholdCounters.ratio(referenceFalse, referenceAutomatic)

    /** Part des fausses validations attribuables à la mauvaise langue. */
    val attributableShare: Double get() = ThresholdCounters.ratio(attributable, falseValidations)
}
