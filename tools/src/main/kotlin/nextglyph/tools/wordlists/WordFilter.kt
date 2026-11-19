package nextglyph.tools.wordlists

import nextglyph.engine.text.TextNormalizer

/** Entrée de liste de mots : forme d'affichage et fréquence entière strictement positive. */
data class WordEntry(val display: String, val frequency: Long)

/** Erreur de format ou de contenu des données d'entrée. */
class WordListException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Remplace les apostrophes typographiques par l'apostrophe droite (comme `TextNormalizer`). */
fun canonicalApostrophes(text: String): String = text.replace('’', '\'').replace('ʼ', '\'')

/**
 * Décide si une forme peut entrer dans une liste de mots : sa clé `TextNormalizer.normalize`
 * ne doit contenir que `a-z`, `'` et `-`, et au moins une lettre.
 */
object WordFilter {

    private val ALLOWED_KEY = Regex("^[a-z'-]+$")

    enum class Rejection(val label: String) {
        NORMALIZER_REFUSED("refusée par TextNormalizer.normalize"),
        OUT_OF_ALPHABET("clé avec caractère hors a-z ' -"),
        NO_LETTER("clé sans aucune lettre"),
    }

    sealed interface Verdict {
        data class Accepted(val key: String) : Verdict
        data class Rejected(val reason: Rejection) : Verdict
    }

    fun check(form: String): Verdict {
        val key = try {
            TextNormalizer.normalize(form)
        } catch (e: IllegalArgumentException) {
            return Verdict.Rejected(Rejection.NORMALIZER_REFUSED)
        }
        if (!ALLOWED_KEY.matches(key)) return Verdict.Rejected(Rejection.OUT_OF_ALPHABET)
        if (key.none { it in 'a'..'z' }) return Verdict.Rejected(Rejection.NO_LETTER)
        return Verdict.Accepted(key)
    }
}
