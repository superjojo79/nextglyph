package nextglyph.engine.input

/** Utilitaires sur le texte autour du curseur. */
object TextUnits {

    /**
     * Nombre de `char` du dernier point de code de [text] (2 pour une paire de substituts,
     * 1 sinon, 0 si [text] est vide). Sert à effacer un caractère complet, émoji compris.
     */
    fun lastCodePointLength(text: CharSequence): Int {
        val n = text.length
        if (n == 0) return 0
        return if (n >= 2 && Character.isLowSurrogate(text[n - 1]) && Character.isHighSurrogate(text[n - 2])) 2 else 1
    }
}
