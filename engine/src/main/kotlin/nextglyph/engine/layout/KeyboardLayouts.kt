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

    private fun letters(letters: String, width: Float = Key.LETTER_WIDTH): KeyRow =
        KeyRow(letters.map { Key.letter(it, width) })
}
