package nextglyph.engine.text

import java.text.Normalizer
import java.util.Locale

/**
 * Transforme un mot saisi en clé de lexique (spec §5.8 et §10.1).
 *
 * La clé est en minuscules, sans diacritiques, avec les ligatures développées
 * (`œ` → `oe`, `æ` → `ae`) et les apostrophes typographiques ramenées à `'`.
 * Les apostrophes et traits d'union sont conservés : ils font partie du lexique.
 */
object TextNormalizer {

    /** Longueur maximale acceptée en entrée, avant développement des ligatures. */
    const val MAX_INPUT_LENGTH: Int = 64

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Renvoie la clé normalisée de [word].
     *
     * @throws IllegalArgumentException si [word] est vide, dépasse [MAX_INPUT_LENGTH]
     *   caractères, contient un espace (y compris insécable), ou ne contient que des
     *   signes diacritiques.
     */
    fun normalize(word: String): String {
        require(word.isNotEmpty()) { "Le mot ne doit pas être vide." }
        require(word.length <= MAX_INPUT_LENGTH) {
            "Le mot dépasse $MAX_INPUT_LENGTH caractères (${word.length})."
        }
        require(word.none(Char::isWhitespace)) { "Le mot ne doit pas contenir d'espace." }

        val expanded = buildString {
            for (ch in word) {
                when (ch) {
                    'œ', 'Œ' -> append("oe")
                    'æ', 'Æ' -> append("ae")
                    '’', 'ʼ' -> append('\'')
                    else -> append(ch)
                }
            }
        }
        val decomposed = Normalizer.normalize(expanded, Normalizer.Form.NFD)
        val result = COMBINING_MARKS.replace(decomposed, "").lowercase(Locale.ROOT)

        require(result.isNotEmpty()) { "Le mot ne contient que des signes diacritiques." }
        return result
    }
}
