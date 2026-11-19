package nextglyph.engine.lexicon

import java.util.Locale

/**
 * Langues des lexiques embarqués (spec §10.1) et choix de la langue active.
 *
 * M1 : une seule langue active à la fois, choisie d'après le sous-type de méthode de saisie
 * courant ; le bilinguisme (§10.6) viendra ensuite.
 */
object LexiconLanguage {

    const val FRENCH: String = "fr"
    const val ENGLISH: String = "en"

    /** Codes des lexiques embarqués. */
    val SUPPORTED: List<String> = listOf(FRENCH, ENGLISH)

    /** Nom du fichier de lexique d'une langue prise en charge (asset de l'application). */
    fun fileName(language: String): String {
        require(language in SUPPORTED) { "Langue de lexique inconnue : « $language »." }
        return "$language.nglx"
    }

    /**
     * Langue du premier des [localeTags] (dans l'ordre de préférence) dont la langue est prise en
     * charge ; l'anglais si aucune ne l'est. Accepte un code de langue (`fr`) ou une balise de
     * locale (`fr-CA`, `fr_BE`) ; les valeurs vides ou `null` sont ignorées.
     */
    fun choose(vararg localeTags: String?): String =
        localeTags.firstNotNullOfOrNull { primaryLanguage(it).takeIf { language -> language in SUPPORTED } } ?: ENGLISH

    private fun primaryLanguage(tag: String?): String =
        tag.orEmpty().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
}
