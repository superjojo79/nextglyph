package nextglyph.tools.ngrams

import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.text.TextNormalizer
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.Tokenizer

/**
 * Taux de réussite du mot suivant sur le corpus de **test** (jamais utilisé pour l'entraînement).
 *
 * @property positions mots du test (une prédiction par mot, contexte = les deux mots d'avant dans la phrase).
 * @property eligible positions où le mot attendu est proposable et dans le vocabulaire du modèle.
 * @property top1 positions éligibles où le mot attendu est la première proposition.
 * @property top3 positions éligibles où le mot attendu est parmi les trois propositions.
 */
data class NgramHitRates(val positions: Long, val eligible: Long, val top1: Long, val top3: Long) {
    /** Part des positions éligibles bien prédites en première proposition (0 si aucune). */
    val top1OfEligible: Double get() = ratio(top1, eligible)

    /** Part des positions éligibles dont le mot est parmi les trois propositions (0 si aucune). */
    val top3OfEligible: Double get() = ratio(top3, eligible)

    /** Idem [top1OfEligible] sur toutes les positions (les mots non proposables comptent comme des échecs). */
    val top1OfAll: Double get() = ratio(top1, positions)

    /** Idem [top3OfEligible] sur toutes les positions. */
    val top3OfAll: Double get() = ratio(top3, positions)

    private fun ratio(part: Long, total: Long): Double = if (total == 0L) 0.0 else part.toDouble() / total
}

class NgramEvaluator(
    private val model: NgramModel,
    private val vocabulary: NgramVocabulary,
    language: Language,
) {
    private val tokenizer = Tokenizer(language)

    private var positions = 0L
    private var eligible = 0L
    private var top1 = 0L
    private var top3 = 0L

    /** Évalue les phrases d'une ligne du corpus de test. */
    fun add(line: String) {
        for (sentence in SentenceSplitter.split(line)) {
            val words = vocabulary.forms(tokenizer.tokens(sentence))
            for (index in words.indices) evaluate(words, index)
        }
    }

    fun result(): NgramHitRates = NgramHitRates(positions, eligible, top1, top3)

    private fun evaluate(words: List<String>, index: Int) {
        positions++
        val expected = words[index]
        if (expected == NgramVocabulary.UNKNOWN || !vocabulary.isPredictable(expected) || !model.contains(expected)) return
        val context = when (index) {
            0 -> emptyList()
            1 -> listOf(NgramModel.START, words[0])
            else -> listOf(words[index - 2], words[index - 1])
        }
        eligible++
        val key = TextNormalizer.normalize(expected)
        val proposals = model.next(context, 3).map { TextNormalizer.normalize(it.text) }
        if (proposals.firstOrNull() == key) top1++
        if (key in proposals) top3++
    }
}
