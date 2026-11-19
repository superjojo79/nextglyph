package nextglyph.engine.edit

/**
 * Réglages et constantes de l'édition de texte (§5.6, §14). Immuable.
 *
 * @property frenchTypography réglage « Typographie française » (§14).
 * @property doubleSpacePeriod réglage « Double espace → point » (§14).
 * @property abbreviations abréviations, en minuscules et sans point final, après lesquelles un point
 *   ne termine pas la phrase (§5.7). Liste courte et volontairement commune au français et à l'anglais.
 */
data class EditConfig(
    val frenchTypography: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val abbreviations: Set<String> = DEFAULT_ABBREVIATIONS,
) {
    companion object {
        /** Espace fine insécable. */
        const val NARROW_NBSP: Char = ' '

        /** Espace insécable. */
        const val NBSP: Char = ' '

        /** Apostrophe insérée par la touche de la barre (celle des lexiques). */
        const val APOSTROPHE: Char = '\''

        /** Signes acceptés par [TextEditor.onPunctuation] : barre du §5.6, plus `;` et les guillemets. */
        const val SUPPORTED_SIGNS: String = ",.?!:…';«»"

        val DEFAULT_ABBREVIATIONS: Set<String> = setOf(
            "m", "mm", "mme", "mmes", "mlle", "mlles", "mr", "mrs", "ms", "dr", "pr", "prof", "me",
            "st", "ste", "sr", "jr", "vs", "etc", "cf", "env", "approx", "e.g", "i.e", "u.s",
        )
    }
}
