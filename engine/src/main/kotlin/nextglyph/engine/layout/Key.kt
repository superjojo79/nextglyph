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

    /** Touche à texte littéral qui n'est pas une lettre du lexique : chiffre, symbole, ponctuation (§7.1, §8). */
    CHARACTER,

    /** Majuscule explicite `⇧` du profil mot de passe (§7.1). */
    SHIFT,

    /** Retour aux lettres (`ABC`) depuis une couche de symboles. */
    LETTERS,

    /** Séparateur décimal du pavé numérique (§8.1) : le caractère inséré est décidé par `DecimalSeparatorPolicy`. */
    DECIMAL,

    /** Emplacement vide d'un pavé : réserve la position, ne réagit pas (§8.1). */
    EMPTY,

    /** Touche du panneau PC (§5.10) : envoie un événement clavier, désigné par son libellé (`PcKey.of`). */
    PC,
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
        require(kind != KeyKind.CHARACTER || label.isNotEmpty()) { "Une touche de caractère porte un libellé non vide." }
    }

    /** Texte littéral inséré par la touche, ou `null` si elle ne fait pas qu'insérer du texte. */
    val text: String?
        get() = when (kind) {
            KeyKind.LETTER -> label
            KeyKind.SPACE -> " "
            KeyKind.CHARACTER -> label
            KeyKind.SYMBOLS, KeyKind.ENTER, KeyKind.BACKSPACE, KeyKind.SHIFT, KeyKind.LETTERS, KeyKind.DECIMAL, KeyKind.EMPTY,
            KeyKind.PC,
            -> null
        }

    companion object {
        /** Largeur au repos d'une lettre des rangées 1 et 2 : 1 u par définition. */
        const val LETTER_WIDTH: Float = 1f

        fun letter(letter: Char, restWidth: Float = LETTER_WIDTH): Key = Key(KeyKind.LETTER, letter.toString(), restWidth)

        /** Touche de caractère littéral (chiffre, symbole). */
        fun character(character: Char, restWidth: Float = LETTER_WIDTH): Key = Key(KeyKind.CHARACTER, character.toString(), restWidth)

        /** Emplacement vide de largeur [restWidth]. */
        fun empty(restWidth: Float): Key = Key(KeyKind.EMPTY, "", restWidth)
    }
}
