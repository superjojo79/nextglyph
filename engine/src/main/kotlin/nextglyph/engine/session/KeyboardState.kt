package nextglyph.engine.session

import nextglyph.engine.edit.ShiftState

/** Contenu de la barre au-dessus des touches (§5.4, §5.6, §9.1). */
enum class BarMode {
    /** Rien à proposer. */
    EMPTY,

    /** Trois cases de suggestions, le mot que l'espace insérera au centre. */
    WORDS,

    /** Juste après une validation : signes de ponctuation (§5.6). */
    PUNCTUATION,

    /** Raccourcis permanents de l'e-mail et de l'URL (§9.1). */
    SHORTCUTS,
}

/**
 * Une case de la barre.
 *
 * @property highlighted le mot que l'espace insérera à la place du texte tapé (§5.5).
 * @property typed le texte tapé lui-même, pour le garder.
 */
data class BarItem(val text: String, val highlighted: Boolean = false, val typed: Boolean = false)

/**
 * La barre : en mode [BarMode.WORDS], exactement trois cases (gauche, centre, droite), dont
 * certaines peuvent être vides ; sinon une case par signe ou raccourci.
 */
data class Bar(val mode: BarMode, val items: List<BarItem?>) {
    companion object {
        val EMPTY: Bar = Bar(BarMode.EMPTY, emptyList())
    }
}

/**
 * Ce que le clavier affiche, recalculé par la session après chaque événement.
 *
 * @property spaceLabel libellé de l'espace quand elle insérera autre chose que le texte tapé (`␣ mot`).
 * @property languageLabel indicateur discret de langue sur l'espace (`FR`, `EN` ; `[FR]` si
 *   verrouillée), ou `null` hors bilinguisme ou hors prose.
 * @property shift état de la majuscule pour la prochaine lettre (manuel ou automatique).
 * @property prefix préfixe normalisé du mot en cours pour l'adaptation visuelle (`""` début de mot),
 *   ou `null` : clavier neutre.
 * @property languageVersion change à chaque nouvel état de langue (l'adaptation se recalcule).
 */
data class KeyboardState(
    val bar: Bar,
    val spaceLabel: String?,
    val languageLabel: String?,
    val shift: ShiftState,
    val prefix: String?,
    val languageVersion: Int,
) {
    companion object {
        val INITIAL: KeyboardState = KeyboardState(Bar.EMPTY, null, null, ShiftState.OFF, null, 0)
    }
}
