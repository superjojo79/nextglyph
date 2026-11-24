package nextglyph.engine.predict

import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramModel

/**
 * Correction d'espace candidate (issue #52, §5.5).
 *
 * @property key clé du candidat (`je suis`, `ordina teur` : toujours une espace, jamais une clé de lexique).
 * @property parts clés des mots du texte inséré (deux pour une coupure, un pour une espace en trop supprimée).
 * @property rawParts texte tapé correspondant à chaque mot de [parts], pour la casse.
 * @property replaces texte remplacé quand il déborde le mot en cours (espace en trop : `ordina teur`), sinon `null`.
 * @property score score mélangé entre langues, dans l'échelle des autres candidats.
 */
internal class SpaceEdit(
    val key: String,
    val parts: List<String>,
    val rawParts: List<String>,
    val replaces: String?,
    val score: Double,
)

/**
 * Candidats des erreurs d'espace (issue #52, §5.5) pour un texte tapé qui n'est pas un mot :
 *
 * 1. **espace tapée sur une touche voisine** (`bonjournmonsieur`) : pour chaque `n`, `b`, `v`, `c` intérieur dont la
 *    frappe est dans le bas de la touche ([SpaceEditConfig.minTapDy]), la lettre est remplacée par une espace ;
 * 2. **mots collés** (`jesuis`) : toute coupure en deux mots ;
 * 3. **espace en trop** (`ordina teur`) : le mot précédent et le mot en cours sont tous deux inconnus (ou très
 *    rares) et leur concaténation est un mot.
 *
 * Chaque langue active note ses propres candidats (les deux mots d'une coupure sont de la même langue) ; les scores
 * sont mélangés par le poids de langue, comme ceux des autres candidats. Sans état : les modèles de bigrammes
 * ([models]) sont remplaçables à chaud.
 */
internal class SpaceEdits(
    private val sets: List<LexiconSet>,
    private val languages: List<String>,
    private val config: SpaceEditConfig,
) {
    /** Modèles de mot suivant par code de langue ; vide : les probabilités conditionnelles sont les unigrammes. */
    var models: Map<String, NgramModel> = emptyMap()

    /** Probabilités lues pendant un appel, par langue : évite de relire un même mot. */
    private class Memo(val sets: List<LexiconSet>) {
        private val cache = Array(sets.size) { HashMap<String, Double>() }

        fun probability(language: Int, key: String): Double =
            cache[language].getOrPut(key) { sets[language].displayForms(key).sumOf { it.frequency.toDouble() } / sets[language].totalMass }
    }

    /**
     * Coupures (cas 1 et 2) du texte tapé [raw] de clé [key] (même longueur, sinon aucune). [taps] : frappes alignées
     * sur [key] (`null` : pas de cas 1). [weights] : poids de langue.
     */
    fun splits(raw: String, key: String, taps: WordTaps?, weights: DoubleArray): List<SpaceEdit> {
        if (key.length < MIN_SPLIT_LENGTH || raw.length != key.length) return emptyList()
        val aligned = taps?.alignedTo(key)
        val memo = Memo(sets)
        val result = ArrayList<SpaceEdit>()
        for (i in 1 until key.length) {
            if (key[i - 1].isLetter() && key[i].isLetter()) {
                score(memo, weights, key.substring(0, i), key.substring(i), raw.substring(0, i), raw.substring(i), config.gluedWeight)?.let(result::add)
            }
            val tap = aligned?.get(i)
            if (i < key.length - 1 && key[i] in config.spaceBarLetters && tap != null && tap.dy >= config.minTapDy &&
                key[i - 1].isLetter() && key[i + 1].isLetter()
            ) {
                score(memo, weights, key.substring(0, i), key.substring(i + 1), raw.substring(0, i), raw.substring(i + 1), config.spaceBarWeight)?.let(result::add)
            }
        }
        return result
    }

    private fun score(memo: Memo, weights: DoubleArray, left: String, right: String, rawLeft: String, rawRight: String, weight: Double): SpaceEdit? {
        var mixed = 0.0
        for (i in sets.indices) {
            if (weights[i] <= 0.0 || !sets[i].containsKey(left) || !sets[i].containsKey(right)) continue
            val pl = memo.probability(i, left)
            val pr = memo.probability(i, right)
            if (!isPlausible(left, pl) || !isPlausible(right, pr)) continue
            val model = models[languages[i]]
            val conditional = when {
                model == null -> pr
                else -> model.bigramProbability(left, right).takeIf { it > 0.0 } ?: (config.unigramBackoff * pr)
            }
            val score = weight * pl * conditional
            if (score >= config.minScore) mixed += weights[i] * score
        }
        return if (mixed > 0.0) SpaceEdit("$left $right", listOf(left, right), listOf(rawLeft, rawRight), null, mixed) else null
    }

    /** Un mot de coupure doit être assez probable ; une seule lettre doit être un mot très fréquent (`a`, `y`, `I`). */
    private fun isPlausible(word: String, probability: Double): Boolean =
        probability >= if (word.length == 1) config.minSingleLetterProbability else config.minPartProbability

    /**
     * Espace en trop (cas 3) : [previous] (mot juste avant l'espace) et [raw] (mot en cours) forment le mot
     * `previousKey + key`. [typedIsWord] : le mot en cours est un mot du lexique (il n'est alors assimilé à une
     * faute que s'il est très rare). `null` si une condition manque.
     */
    fun join(previous: String, previousKey: String, raw: String, key: String, typedIsWord: Boolean, weights: DoubleArray): SpaceEdit? {
        if (!isLetters(previousKey) || !isLetters(key)) return null
        val memo = Memo(sets)
        if (!isMissing(memo, weights, previousKey) || (typedIsWord && !isMissing(memo, weights, key))) return null
        val joined = previousKey + key
        var mixed = 0.0
        for (i in sets.indices) {
            if (weights[i] <= 0.0 || !sets[i].containsKey(joined)) continue
            val p = memo.probability(i, joined)
            if (p >= config.minJoinProbability) mixed += weights[i] * config.joinWeight * p
        }
        if (mixed <= 0.0) return null
        return SpaceEdit("$previousKey $key", listOf(joined), listOf(previous + raw), "$previous $raw", mixed)
    }

    /** Vrai si [key] n'est un mot d'aucune langue active, ou si sa probabilité mélangée est très faible. */
    private fun isMissing(memo: Memo, weights: DoubleArray, key: String): Boolean {
        var mixed = 0.0
        for (i in sets.indices) {
            if (weights[i] > 0.0 && sets[i].containsKey(key)) mixed += weights[i] * memo.probability(i, key)
        }
        return mixed <= 0.0 || mixed < config.rareWordProbability
    }

    private fun isLetters(key: String): Boolean = key.isNotEmpty() && key.all { it in 'a'..'z' }

    private companion object {
        const val MIN_SPLIT_LENGTH = 2
    }
}
