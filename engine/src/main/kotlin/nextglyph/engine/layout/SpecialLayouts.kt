package nextglyph.engine.layout

/** Variante du pavé date / heure (spec §8.3). */
enum class DateTimeVariant(val hasDate: Boolean, val hasTime: Boolean) {
    /** `TYPE_DATETIME_VARIATION_DATE` : `/` et `-`. */
    DATE(hasDate = true, hasTime = false),

    /** `TYPE_DATETIME_VARIATION_TIME` : `:`. */
    TIME(hasDate = false, hasTime = true),

    /** `TYPE_DATETIME_VARIATION_NORMAL` : date et heure, les trois séparateurs. */
    DATE_TIME(hasDate = true, hasTime = true),
}

/**
 * Dispositions spécialisées : mot de passe (§7.1), couches de symboles `?123` (§5.1, §7.1)
 * et pavés (§8). Chaque rangée fait 10 u.
 *
 * Les pavés ont une position fixe par touche : une touche absente laisse un emplacement
 * [KeyKind.EMPTY], si bien que les chiffres ne bougent jamais (§8.1).
 */
object SpecialLayouts {

    /** Largeur d'une colonne d'un pavé à 4 colonnes (numérique, PIN, date/heure). */
    private const val COLUMN_4 = 2.5f

    /** Largeur d'une colonne du pavé téléphone (5 colonnes). */
    private const val COLUMN_5 = 2f

    /** Largeur des lettres de la rangée 4 QWERTY du mot de passe : 9 lettres + retrait de 0,5 u = 10 u. */
    private const val QWERTY_ROW_LETTER = 9.5f / 9f

    /** Largeur d'un symbole de la rangée 3 de la couche `?123` (32,4 dp à 360 dp, §13). */
    private const val ROW3_SYMBOL = 0.9f

    /**
     * Disposition classique à 6 rangées du profil mot de passe (§7.1), avec `⇧` explicite.
     * Les rangées de lettres suivent [kind] ; le retrait de la rangée 4 QWERTY est compensé
     * par des lettres de 19/18 u pour que la rangée fasse 10 u.
     */
    fun password(kind: LayoutKind): KeyboardLayout {
        val letterRows = when (kind) {
            LayoutKind.AZERTY -> listOf(
                letters("azertyuiop"),
                letters("qsdfghjklm"),
                KeyRow(listOf(shift(2f)) + letters("wxcvbn").keys + Key(KeyKind.BACKSPACE, "⌫", 2f)),
            )
            LayoutKind.QWERTY -> listOf(
                letters("qwertyuiop"),
                KeyRow(letters("asdfghjkl", QWERTY_ROW_LETTER).keys, leadingInset = 0.5f),
                KeyRow(listOf(shift(1.5f)) + letters("zxcvbnm").keys + Key(KeyKind.BACKSPACE, "⌫", 1.5f)),
            )
        }
        val last = KeyRow(
            listOf(
                Key(KeyKind.SYMBOLS, "?123", 1.5f),
                Key.character(',', 1f),
                Key(KeyKind.SPACE, "", 4.5f),
                Key.character('.', 1f),
                Key(KeyKind.ENTER, "↵", 2f),
            ),
        )
        val rows = listOf(characters("@#$%&*-_!?"), characters("1234567890")) + letterRows + last
        return KeyboardLayout(kind, rows, LayoutRole.PASSWORD)
    }

    /** Couche `?123` du profil mot de passe : les symboles restants du §7.1. */
    fun passwordSymbols(kind: LayoutKind = LayoutKind.QWERTY): KeyboardLayout = KeyboardLayout(
        kind,
        listOf(
            characters("[]{}<>^~|\\"),
            characters("/+='\";:€£`"),
            KeyRow(
                listOf(Key(KeyKind.LETTERS, "ABC", 1.5f)) + characters("()§°¥¢").keys + Key(KeyKind.BACKSPACE, "⌫", 2.5f),
            ),
        ),
        LayoutRole.PASSWORD_SYMBOLS,
    )

    /**
     * Couche `?123` du profil texte : 3 rangées de 10 u, même répartition de la rangée 3 que
     * les lettres AZERTY (`ABC` 1 u, 6 symboles à 0,9 u, espace 1,6 u, `↵` 1 u, `⌫` 1 u).
     */
    fun textSymbols(kind: LayoutKind = LayoutKind.QWERTY): KeyboardLayout = KeyboardLayout(
        kind,
        listOf(
            characters("1234567890"),
            characters("@#€%&-+()/"),
            KeyRow(
                listOf(Key(KeyKind.LETTERS, "ABC", 1f)) + characters(",.!?':", ROW3_SYMBOL).keys +
                    listOf(
                        Key(KeyKind.SPACE, "", 1.6f),
                        Key(KeyKind.ENTER, "↵", 1f),
                        Key(KeyKind.BACKSPACE, "⌫", 1f),
                    ),
            ),
        ),
        LayoutRole.TEXT_SYMBOLS,
    )

    /**
     * Pavé numérique (§8.1). Signe moins seulement si [signed], séparateur ([decimalLabel], `,` en français)
     * seulement si [decimal] ; sinon l'emplacement reste vide. Le signe moins est le trait d'union ASCII `-`
     * (U+002D) : les champs numériques d'Android refusent le signe moins typographique `−` (U+2212).
     */
    fun number(signed: Boolean, decimal: Boolean, decimalLabel: Char = ','): KeyboardLayout =
        numberPad(
            top = if (signed) Key.character('-', COLUMN_4) else Key.empty(COLUMN_4),
            second = if (decimal) Key(KeyKind.DECIMAL, decimalLabel.toString(), COLUMN_4) else Key.empty(COLUMN_4),
            role = LayoutRole.NUMBER,
        )

    /** Pavé du code PIN (§8.4) : pavé numérique sans `−` ni séparateur. */
    fun pin(): KeyboardLayout = numberPad(Key.empty(COLUMN_4), Key.empty(COLUMN_4), LayoutRole.PIN)

    /**
     * Pavé téléphone (§8.2), 5 colonnes : `1`–`9`, `*`, `0`, `#`, plus `+`, `,` (pause), `;` (attente),
     * `⌫` et `↵`.
     */
    fun phone(): KeyboardLayout = KeyboardLayout(
        LayoutKind.QWERTY,
        listOf(
            KeyRow(listOf(digit('1', COLUMN_5), digit('2', COLUMN_5), digit('3', COLUMN_5), Key.character('+', COLUMN_5), Key(KeyKind.BACKSPACE, "⌫", COLUMN_5))),
            KeyRow(listOf(digit('4', COLUMN_5), digit('5', COLUMN_5), digit('6', COLUMN_5), Key.character(',', COLUMN_5), Key.empty(COLUMN_5))),
            KeyRow(listOf(digit('7', COLUMN_5), digit('8', COLUMN_5), digit('9', COLUMN_5), Key.character(';', COLUMN_5), Key.empty(COLUMN_5))),
            KeyRow(listOf(Key.character('*', COLUMN_5), digit('0', COLUMN_5), Key.character('#', COLUMN_5), Key.empty(COLUMN_5), Key(KeyKind.ENTER, "↵", COLUMN_5))),
        ),
        LayoutRole.PHONE,
    )

    /**
     * Pavé date / heure (§8.3), mêmes positions que le pavé numérique : `/` et `-` dans la colonne de droite
     * (date), `:` à gauche de `↵` (heure) ; les séparateurs hors variation laissent un emplacement vide.
     */
    fun dateTime(variant: DateTimeVariant): KeyboardLayout {
        val slash = if (variant.hasDate) Key.character('/', COLUMN_4) else Key.empty(COLUMN_4)
        val dash = if (variant.hasDate) Key.character('-', COLUMN_4) else Key.empty(COLUMN_4)
        val colon = if (variant.hasTime) Key.character(':', COLUMN_4) else Key.empty(COLUMN_4)
        return KeyboardLayout(
            LayoutKind.QWERTY,
            listOf(
                KeyRow(listOf(digit('1'), digit('2'), digit('3'), slash)),
                KeyRow(listOf(digit('4'), digit('5'), digit('6'), dash)),
                KeyRow(listOf(digit('7'), digit('8'), digit('9'), Key(KeyKind.BACKSPACE, "⌫", COLUMN_4))),
                KeyRow(listOf(Key.empty(COLUMN_4), digit('0'), colon, Key(KeyKind.ENTER, "↵", COLUMN_4))),
            ),
            LayoutRole.DATE_TIME,
        )
    }

    private fun numberPad(top: Key, second: Key, role: LayoutRole): KeyboardLayout = KeyboardLayout(
        LayoutKind.QWERTY,
        listOf(
            KeyRow(listOf(digit('1'), digit('2'), digit('3'), top)),
            KeyRow(listOf(digit('4'), digit('5'), digit('6'), second)),
            KeyRow(listOf(digit('7'), digit('8'), digit('9'), Key(KeyKind.BACKSPACE, "⌫", COLUMN_4))),
            KeyRow(listOf(Key.empty(COLUMN_4), digit('0'), Key.empty(COLUMN_4), Key(KeyKind.ENTER, "↵", COLUMN_4))),
        ),
        role,
    )

    private fun digit(digit: Char, width: Float = COLUMN_4): Key = Key.character(digit, width)

    private fun shift(width: Float) = Key(KeyKind.SHIFT, "⇧", width)

    private fun letters(letters: String, width: Float = Key.LETTER_WIDTH): KeyRow =
        KeyRow(letters.map { Key.letter(it, width) })

    private fun characters(characters: String, width: Float = Key.LETTER_WIDTH): KeyRow =
        KeyRow(characters.map { Key.character(it, width) })
}
