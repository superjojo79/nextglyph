package nextglyph.engine.layout

/** Nature d'une touche de la disposition (spec §5.1). */
enum class KeyKind {
    /** Touche lettre : insère son [Key.label]. */
    LETTER,

    /** Bascule vers les chiffres et symboles (`?123`). */
    SYMBOLS,

    /** Espace. */
    SPACE,

    /** Entrée (`↵`) : action de l'éditeur ou saut de ligne. */
    ENTER,

    /** Effacement arrière (`⌫`). */
    BACKSPACE,
}

/**
 * Une touche : sa nature, son libellé et sa largeur au repos en unités
 * (1 u = largeur d'une touche lettre au repos des rangées 1 et 2).
 *
 * Une touche lettre a pour libellé exactement un caractère (la lettre, en minuscule).
 * Le libellé des autres touches peut être vide (l'espace n'a pas de libellé fixe).
 */
data class Key(val kind: KeyKind, val label: String, val restWidth: Float) {

    init {
        require(restWidth.isFinite() && restWidth > 0f) { "La largeur d'une touche doit être strictement positive : $restWidth." }
        require(kind != KeyKind.LETTER || label.length == 1) {
            "Une touche lettre porte exactement un caractère : \"$label\"."
        }
    }

    /** Texte littéral inséré par la touche, ou `null` si elle ne fait pas qu'insérer du texte. */
    val text: String?
        get() = when (kind) {
            KeyKind.LETTER -> label
            KeyKind.SPACE -> " "
            KeyKind.SYMBOLS, KeyKind.ENTER, KeyKind.BACKSPACE -> null
        }

    companion object {
        /** Largeur au repos d'une lettre des rangées 1 et 2 : 1 u par définition. */
        const val LETTER_WIDTH: Float = 1f

        fun letter(letter: Char, restWidth: Float = LETTER_WIDTH): Key = Key(KeyKind.LETTER, letter.toString(), restWidth)
    }
}
