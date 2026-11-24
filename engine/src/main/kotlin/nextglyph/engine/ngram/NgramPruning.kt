package nextglyph.engine.ngram

/**
 * Élagage du modèle à la construction (taille du fichier, issue #51).
 *
 * @property minBigramCount nombre minimal d'occurrences d'un bigramme pour être gardé.
 * @property minTrigramCount idem pour un trigramme.
 * @property maxBigramFollowers nombre maximal de suivants gardés par mot précédent.
 * @property maxTrigramFollowers idem par couple de mots précédents.
 * @property maxVocabulary nombre maximal de mots du vocabulaire (les plus fréquents).
 * @property minTrigramGain un suivant de trigramme n'est gardé que si `P(c | a b) ≥ gain × P(c | b)` : un
 *   trigramme qui n'apprend rien de plus que le bigramme est retrouvé par repli.
 */
data class NgramPruning(
    val minBigramCount: Int = 2,
    val minTrigramCount: Int = 2,
    val maxBigramFollowers: Int = 8,
    val maxTrigramFollowers: Int = 4,
    val maxVocabulary: Int = 30_000,
    val minTrigramGain: Double = 1.0,
) {
    init {
        require(minBigramCount >= 1) { "Le seuil des bigrammes doit être au moins 1 ($minBigramCount)." }
        require(minTrigramCount >= 1) { "Le seuil des trigrammes doit être au moins 1 ($minTrigramCount)." }
        require(maxBigramFollowers in 1..MAX_FOLLOWERS) {
            "Le nombre de suivants des bigrammes doit être dans 1..$MAX_FOLLOWERS ($maxBigramFollowers)."
        }
        require(maxTrigramFollowers in 1..MAX_FOLLOWERS) {
            "Le nombre de suivants des trigrammes doit être dans 1..$MAX_FOLLOWERS ($maxTrigramFollowers)."
        }
        require(maxVocabulary in 1..NgramFormat.MAX_VOCABULARY) {
            "Le vocabulaire doit contenir de 1 à ${NgramFormat.MAX_VOCABULARY} mots ($maxVocabulary)."
        }
        require(minTrigramGain >= 0.0 && minTrigramGain.isFinite()) {
            "Le gain minimal des trigrammes doit être positif ou nul ($minTrigramGain)."
        }
    }

    companion object {
        const val MAX_FOLLOWERS: Int = 255
    }
}

/** Un mot proposé après un contexte, avec son score (probabilité conditionnelle, éventuellement après repli). */
data class NextWord(val text: String, val probability: Double)
