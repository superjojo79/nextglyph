package nextglyph.engine.predict

/**
 * Score de langue (spec §10.6), valeur immuable : chaque mot validé produit un nouvel état.
 *
 * - **Score** : fenêtre glissante des [window] derniers mots validés ; chaque mot apporte un
 *   indice par langue `a_L` (fréquence relative, voir [Predictor.evidence]) et
 *   `π_L = (Σ a_L + prior · π0_L) / (Σ_L' Σ a_L' + prior)`, où `π0` part de la langue préférée.
 *   Fenêtre vide : `π = π0`.
 * - **Verrouillage** : [lock] fixe une langue (`π = 1` pour elle, 0 pour les autres) ; `null`
 *   revient au mode automatique, avec le score accumulé entre-temps.
 * - **Langue de la phrase** ([sentenceLanguage]) : langue dominante du score restreint aux mots
 *   validés depuis la dernière ponctuation finale ([endSentence]), avec pour a priori le score
 *   de la fenêtre au début de la phrase ; sans mot dans la phrase, la langue dominante.
 *
 * @property languages codes des langues actives, dans l'ordre des tableaux de poids.
 */
class LanguageState private constructor(
    val languages: List<String>,
    private val initial: DoubleArray,
    private val window: Int,
    private val prior: Double,
    private val recent: List<DoubleArray>,
    private val sentenceStart: DoubleArray,
    private val sentence: DoubleArray,
    /** Langue verrouillée manuellement, ou `null` en mode automatique. */
    val locked: String?,
) {

    /** `π_L` de chaque langue de [languages], somme 1. */
    val weights: DoubleArray
        get() {
            val lockedIndex = languages.indexOf(locked)
            if (lockedIndex >= 0) return DoubleArray(languages.size) { if (it == lockedIndex) 1.0 else 0.0 }
            return windowWeights()
        }

    /** `π` de [language] (0 si elle n'est pas active). */
    fun weight(language: String): Double {
        val index = languages.indexOf(language)
        return if (index < 0) 0.0 else weights[index]
    }

    /** Langue dominante du score (à égalité, la plus forte au départ, puis la première). */
    val dominant: String get() = argmax(weights)

    /** Langue de la phrase en cours, pour les règles propres à chaque langue (§10.6). */
    val sentenceLanguage: String
        get() {
            if (locked != null) return locked
            val total = sentence.sum()
            if (total == 0.0) return dominant
            return argmax(DoubleArray(languages.size) { (sentence[it] + prior * sentenceStart[it]) / (total + prior) })
        }

    /**
     * Nouvel état après la validation d'un mot d'indice [evidence] (un poids par langue, somme 1,
     * ou tous nuls pour un mot inconnu de tous les lexiques, qui n'apporte rien mais occupe la
     * fenêtre, comme dans l'analyse M0).
     *
     * @throws IllegalArgumentException si [evidence] n'a pas un poids par langue, ou un poids négatif.
     */
    fun observe(evidence: DoubleArray): LanguageState {
        require(evidence.size == languages.size) { "Un indice par langue est attendu (${evidence.size} pour ${languages.size})." }
        require(evidence.all { it >= 0.0 && it.isFinite() }) { "Les indices de langue doivent être positifs ou nuls." }
        val next = (recent + evidence.copyOf()).takeLast(window)
        val sums = DoubleArray(languages.size) { sentence[it] + evidence[it] }
        return LanguageState(languages, initial, window, prior, next, sentenceStart, sums, locked)
    }

    /** Nouvel état au début d'une phrase (après `.`, `?`, `!`, `…`). */
    fun endSentence(): LanguageState =
        LanguageState(languages, initial, window, prior, recent, windowWeights(), DoubleArray(languages.size), locked)

    /**
     * Verrouille [language], ou revient au mode automatique si `null` (menu de l'espace, §10.6).
     *
     * @throws IllegalArgumentException si [language] n'est pas une langue active.
     */
    fun lock(language: String?): LanguageState {
        require(language == null || language in languages) { "Langue inactive : « $language »." }
        return LanguageState(languages, initial, window, prior, recent, sentenceStart, sentence, language)
    }

    private fun windowWeights(): DoubleArray {
        val sums = DoubleArray(languages.size)
        for (evidence in recent) for (i in sums.indices) sums[i] += evidence[i]
        val total = sums.sum() + prior
        return DoubleArray(languages.size) { (sums[it] + prior * initial[it]) / total }
    }

    private fun argmax(values: DoubleArray): String {
        var best = 0
        for (i in 1 until values.size) {
            if (values[i] > values[best] || (values[i] == values[best] && initial[i] > initial[best])) best = i
        }
        return languages[best]
    }

    override fun toString(): String =
        "LanguageState(${languages.zip(weights.toList()).joinToString { "${it.first}=${"%.3f".format(it.second)}" }}, " +
            "phrase=$sentenceLanguage, verrou=$locked)"

    companion object {
        /**
         * État de départ : [preferred] reçoit la part [preferredShare] de `π0`, le reste est réparti
         * également entre les autres langues (une seule langue : `π = 1`).
         *
         * @throws IllegalArgumentException si [languages] est vide ou contient des doublons, si
         *   [preferred] n'en fait pas partie, ou si un paramètre est hors de son domaine.
         */
        fun initial(
            languages: List<String>,
            preferred: String,
            window: Int = PredictionConfig().languageWindow,
            prior: Double = PredictionConfig().languagePrior,
            preferredShare: Double = PredictionConfig().preferredLanguageShare,
        ): LanguageState {
            require(languages.isNotEmpty() && languages.distinct().size == languages.size) {
                "Les langues actives doivent être non vides et distinctes : $languages."
            }
            require(preferred in languages) { "La langue préférée « $preferred » n'est pas active." }
            require(window >= 1) { "La fenêtre doit contenir au moins un mot ($window)." }
            require(prior > 0.0) { "Le poids de départ doit être strictement positif ($prior)." }
            require(preferredShare in 0.0..1.0) { "La part de la langue préférée doit être dans [0, 1] ($preferredShare)." }
            val others = languages.size - 1
            val initial = DoubleArray(languages.size) {
                when {
                    languages[it] == preferred -> if (others == 0) 1.0 else preferredShare
                    else -> (1.0 - preferredShare) / others
                }
            }
            return LanguageState(languages.toList(), initial, window, prior, emptyList(), initial, DoubleArray(languages.size), null)
        }
    }
}
