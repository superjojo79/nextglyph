package nextglyph.tools.ngrams

import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.WordEntry
import java.util.Locale

/**
 * Passage des mots du corpus aux formes d'affichage du modèle de mot suivant (issue #51).
 *
 * Un mot du corpus est ramené à sa forme **minuscule** si la liste de mots la contient (`Je` donne
 * `je`, `École` donne `école`), sinon gardé tel quel s'il y figure (`Paris`, `Tom`, `I`, `I'm`), sinon
 * il est **inconnu** de la liste (faute, mot rare, nom propre absent) : il casse le contexte et n'est
 * jamais proposé.
 *
 * Un mot n'est **proposable** que s'il est en minuscules dans la liste : jamais `Tom` ni `Paris`, mais
 * en anglais `I` et ses contractions (`I'm`, `I'll`...), qui sont des mots courants.
 */
class NgramVocabulary(private val language: Language, entries: List<WordEntry>) {

    private val forms: Set<String> = entries.mapTo(HashSet()) { it.display }

    /** Forme d'affichage de [token], ou `null` s'il est inconnu de la liste de mots. */
    fun display(token: String): String? {
        val lower = token.lowercase(Locale.ROOT)
        return when {
            lower in forms -> lower
            token in forms -> token
            else -> null
        }
    }

    /** Vrai si la forme [display] peut être proposée comme mot suivant. */
    fun isPredictable(display: String): Boolean =
        display == display.lowercase(Locale.ROOT) || (language == Language.EN && (display == "I" || display.startsWith("I'")))

    /** Mots du corpus ramenés à leurs formes ; les mots inconnus de la liste valent [UNKNOWN]. */
    fun forms(tokens: List<String>): List<String> = tokens.map { display(it) ?: UNKNOWN }

    companion object {
        /** Mot inconnu : n'est pas une clé valide, le modèle le traite comme un mot inconnu. */
        const val UNKNOWN: String = "<inconnu>"
    }
}

/** Découpe un texte du corpus en phrases au sens du clavier : après `.`, `!`, `?` ou `…` suivis d'une espace. */
object SentenceSplitter {

    private val BOUNDARY = Regex("(?<=[.!?…])\\s+")

    fun split(text: String): List<String> = text.split(BOUNDARY).filter { it.isNotBlank() }
}
