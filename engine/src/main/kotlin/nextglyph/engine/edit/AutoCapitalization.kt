package nextglyph.engine.edit

import java.util.Locale

/**
 * Majuscules automatiques (§5.7), déduites du texte avant le curseur : fonction pure.
 *
 * - `SENTENCES` : début de champ, début de ligne, ou après `. ? ! …` suivi d'au moins une espace
 *   (ordinaire ou insécable), des guillemets ou parenthèses fermants pouvant s'intercaler. Un point
 *   qui termine une abréviation de [EditConfig.abbreviations] ne compte pas.
 * - `WORDS` : début de champ ou après une espace ou un saut de ligne.
 * - `CHARACTERS` : toujours.
 * - Aucune majuscule automatique dans les profils sans typographie (e-mail, URL, mot de passe, §9.1).
 */
object AutoCapitalization {

    /** Nombre de caractères à demander avant le curseur. */
    const val LOOKBACK: Int = 64

    /** Guillemets et parenthèses fermants qui peuvent suivre un signe de fin de phrase. */
    internal const val CLOSERS = ")]}\"”»’'"

    /** Majuscule automatique à l'emplacement du curseur. */
    fun shouldCapitalize(
        textBefore: CharSequence,
        profile: TextProfile,
        capsMode: CapsMode,
        config: EditConfig = EditConfig(),
    ): Boolean {
        if (!profile.automaticTypography) return false
        return when (capsMode) {
            CapsMode.NONE -> false
            CapsMode.CHARACTERS -> true
            CapsMode.WORDS -> textBefore.isEmpty() || isSpace(textBefore.last())
            CapsMode.SENTENCES -> startsSentence(textBefore, config)
        }
    }

    /**
     * État de la touche majuscule à afficher : un état manuel (une lettre ou verrouillé) l'emporte,
     * sinon une majuscule automatique donne [ShiftState.ONCE].
     */
    fun shiftFor(
        textBefore: CharSequence,
        profile: TextProfile,
        capsMode: CapsMode,
        manual: ShiftState,
        config: EditConfig = EditConfig(),
    ): ShiftState = when {
        manual != ShiftState.OFF -> manual
        shouldCapitalize(textBefore, profile, capsMode, config) -> ShiftState.ONCE
        else -> ShiftState.OFF
    }

    /** Espace ordinaire, insécable, fine insécable, saut de ligne, etc. */
    internal fun isSpace(c: Char): Boolean = Character.isWhitespace(c) || c == EditConfig.NBSP || c == EditConfig.NARROW_NBSP || c == ' '

    private fun startsSentence(text: CharSequence, config: EditConfig): Boolean {
        var i = text.length
        var sawSpace = false
        while (i > 0 && isSpace(text[i - 1])) {
            if (text[i - 1] == '\n') return true
            sawSpace = true
            i--
        }
        if (i == 0) return true
        if (!sawSpace) return false
        while (i > 0 && (text[i - 1] in CLOSERS || (i < text.length && isSpace(text[i - 1])))) i--
        if (i == 0) return false
        return when (text[i - 1]) {
            '?', '!', '…' -> true
            '.' -> !endsWithAbbreviation(text, i - 1, config)
            else -> false
        }
    }

    /** Le point à l'index [dot] termine-t-il une abréviation (`M.`, `etc.`, `e.g.`) ? */
    private fun endsWithAbbreviation(text: CharSequence, dot: Int, config: EditConfig): Boolean {
        var start = dot
        while (start > 0) {
            val c = text[start - 1]
            val innerDot = c == '.' && start > 1 && text[start - 2].isLetter()
            if (!c.isLetter() && !innerDot) break
            start--
        }
        if (start == dot) return false
        return text.substring(start, dot).lowercase(Locale.ROOT) in config.abbreviations
    }
}
