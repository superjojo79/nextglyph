package nextglyph.engine.lexicon

/**
 * Alphabet des clés de lexique (spec §5.8) : `'`, `-` puis `a`–`z`.
 *
 * L'ordre des index suit l'ordre des codes de caractère (`'` < `-` < `a`), si bien que
 * l'ordre naturel des chaînes Kotlin coïncide avec l'ordre des index : le constructeur
 * du lexique peut trier les clés avec `sorted()`.
 */
object LexiconAlphabet {

    /** Les caractères de l'alphabet, dans l'ordre de leurs index. */
    const val CHARS: String = "'-abcdefghijklmnopqrstuvwxyz"

    /** Nombre de caractères de l'alphabet. */
    const val SIZE: Int = CHARS.length

    private const val APOSTROPHE_INDEX = 0
    private const val HYPHEN_INDEX = 1
    private const val FIRST_LETTER_INDEX = 2

    /** Index de [c] dans [CHARS], ou `-1` s'il n'appartient pas à l'alphabet. */
    fun indexOf(c: Char): Int = when (c) {
        '\'' -> APOSTROPHE_INDEX
        '-' -> HYPHEN_INDEX
        in 'a'..'z' -> FIRST_LETTER_INDEX + (c - 'a')
        else -> -1
    }

    /** Vrai si [key] n'est formée que de caractères de l'alphabet. */
    fun isValidKey(key: String): Boolean = key.all { indexOf(it) >= 0 }
}
