package nextglyph.engine.lexicon

import java.util.Locale

/**
 * Un fichier de lexique embarqué (asset de l'application, produit par `:tools:buildLexicons`).
 *
 * @property name nom du fichier (`fr-lexique.nglx`).
 * @property language code de langue (`fr`).
 * @property caseless ses formes sont des orthographes sans casse (voir [LexiconSet]).
 */
data class LexiconFile(val name: String, val language: String, val caseless: Boolean = false) {
    /** Nom sans l'extension `.nglx` : nom de la liste de mots correspondante (`fr-lexique.tsv`). */
    val baseName: String get() = name.removeSuffix(EXTENSION)

    companion object {
        const val EXTENSION: String = ".nglx"
    }
}

/**
 * Langues des lexiques embarqués (spec §10.1), fichiers de chacune et choix de la langue préférée.
 *
 * Le français est réparti en deux fichiers, un par licence (`docs/DATA.md`) : `fr-lexique.nglx`
 * (formes et fréquences de Lexique 4.10, CC BY-SA 4.0, sans casse) et `fr-dicollecte.nglx` (formes
 * de Dicollecte, fréquence plancher, MPL-2.0), fusionnés à la lecture en un [LexiconSet].
 */
object LexiconLanguage {

    const val FRENCH: String = "fr"
    const val ENGLISH: String = "en"

    /** Codes des lexiques embarqués. */
    val SUPPORTED: List<String> = listOf(FRENCH, ENGLISH)

    /** Fichiers de lexique d'une langue prise en charge, dans l'ordre de fusion. */
    fun files(language: String): List<LexiconFile> = when (language) {
        FRENCH -> listOf(LexiconFile("fr-lexique.nglx", FRENCH, caseless = true), LexiconFile("fr-dicollecte.nglx", FRENCH))
        ENGLISH -> listOf(LexiconFile("en.nglx", ENGLISH))
        else -> throw IllegalArgumentException("Langue de lexique inconnue : « $language ».")
    }

    /** Tous les fichiers embarqués, langue par langue. */
    val ALL_FILES: List<LexiconFile> get() = SUPPORTED.flatMap(::files)

    /**
     * Assemble les lexiques chargés de [language] (un par fichier de [files], dans le même ordre)
     * en un [LexiconSet], les membres sans casse étant déclarés comme tels.
     *
     * @throws IllegalArgumentException si la langue est inconnue ou si le nombre de lexiques ne
     *   correspond pas à ses fichiers.
     */
    fun assemble(language: String, lexicons: List<Lexicon>): LexiconSet {
        val files = files(language)
        require(lexicons.size == files.size) { "${files.size} lexiques attendus pour « $language », ${lexicons.size} reçus." }
        return LexiconSet(lexicons, lexicons.filterIndexed { i, _ -> files[i].caseless })
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
