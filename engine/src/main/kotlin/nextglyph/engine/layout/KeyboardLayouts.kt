package nextglyph.engine.layout

/** Dispositions au repos du profil Texte (spec §5.1). */
object KeyboardLayouts {

    private const val ROW3_LETTER = 0.9f

    val AZERTY: KeyboardLayout = KeyboardLayout(
        LayoutKind.AZERTY,
        listOf(
            letters("azertyuiop"),
            letters("qsdfghjklm"),
            KeyRow(
                listOf(Key(KeyKind.SYMBOLS, "?123", 1f)) +
                    letters("wxcvbn", ROW3_LETTER).keys +
                    listOf(
                        Key(KeyKind.SPACE, "", 1.6f),
                        Key(KeyKind.ENTER, "↵", 1f),
                        Key(KeyKind.BACKSPACE, "⌫", 1f),
                    ),
            ),
        ),
    )

    val QWERTY: KeyboardLayout = KeyboardLayout(
        LayoutKind.QWERTY,
        listOf(
            letters("qwertyuiop"),
            KeyRow(
                letters("asdfghjkl", 0.95f).keys + Key(KeyKind.ENTER, "↵", 1.15f),
                leadingInset = 0.3f,
            ),
            KeyRow(
                listOf(Key(KeyKind.SYMBOLS, "?123", 1f)) +
                    letters("zxcvbnm", ROW3_LETTER).keys +
                    listOf(
                        Key(KeyKind.SPACE, "", 1.7f),
                        Key(KeyKind.BACKSPACE, "⌫", 1f),
                    ),
            ),
        ),
    )

    fun of(kind: LayoutKind): KeyboardLayout = when (kind) {
        LayoutKind.AZERTY -> AZERTY
        LayoutKind.QWERTY -> QWERTY
    }

    /** Largeur de `?123`/`ABC`, `↵` et `⌫` dans la rangée de l'espace quand l'espace est élargi (§5.1, issue #23). */
    const val WIDE_SPACE_KEY_WIDTH: Float = 0.8f

    /** Largeur d'écran minimale, en dp, pour élargir l'espace : en dessous, les touches de fonction resteraient trop étroites. */
    const val WIDE_SPACE_MIN_SCREEN_DP: Int = 400

    private val WIDE_SPACE_SHRUNK = setOf(KeyKind.SYMBOLS, KeyKind.LETTERS, KeyKind.ENTER, KeyKind.BACKSPACE)

    /**
     * Réglage « Espace large » (§5.1, issue #23) : dans chaque rangée qui contient l'espace, `?123` (ou `ABC`),
     * `↵` et `⌫` passent à [WIDE_SPACE_KEY_WIDTH] et l'espace reprend la largeur libérée (2,2 u en AZERTY,
     * 2,1 u en QWERTY où `↵` est sur la rangée 2). Les autres rangées et la largeur totale ne changent pas.
     */
    fun widenSpace(layout: KeyboardLayout): KeyboardLayout = KeyboardLayout(
        layout.kind,
        layout.rows.map { row ->
            if (row.keys.none { it.kind == KeyKind.SPACE }) return@map row
            val shrunk = row.keys.map { key ->
                if (key.kind in WIDE_SPACE_SHRUNK && key.restWidth > WIDE_SPACE_KEY_WIDTH) key.copy(restWidth = WIDE_SPACE_KEY_WIDTH) else key
            }
            val freed = row.keys.sumOf { it.restWidth.toDouble() } - shrunk.sumOf { it.restWidth.toDouble() }
            KeyRow(shrunk.map { if (it.kind == KeyKind.SPACE) it.copy(restWidth = it.restWidth + freed.toFloat()) else it }, row.leadingInset)
        },
        layout.role,
    )

    private fun letters(letters: String, width: Float = Key.LETTER_WIDTH): KeyRow =
        KeyRow(letters.map { Key.letter(it, width) })
}
