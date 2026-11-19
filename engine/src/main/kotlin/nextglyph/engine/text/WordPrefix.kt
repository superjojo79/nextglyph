package nextglyph.engine.text

import nextglyph.engine.lexicon.LexiconAlphabet

/**
 * Préfixe du mot en cours de saisie, déduit du texte qui précède le curseur (spec §11.2 :
 * `getTextBeforeCursor()` et `onUpdateSelection()`), sous la forme d'une clé de lexique.
 *
 * Fonction pure, sans état : l'application l'appelle après chaque frappe et à chaque changement de
 * sélection. Le mot en cours est la dernière suite de lettres (et signes combinants) avant le
 * curseur ; l'apostrophe et le trait d'union en font partie **entre deux lettres** (`don't`,
 * `peut-`), comme dans le découpage des lexiques. Tout autre caractère (espace, ponctuation, saut
 * de ligne, emoji) termine le mot : le préfixe est alors vide (début de mot).
 *
 * Français : une élision (`l'`, `qu'`, `jusqu'`...) est un mot à part entière (§5.8), le mot suivant
 * commence donc sans espace : `l'` donne le préfixe vide, `l'éc` donne `ec`.
 */
object WordPrefix {

    /** Nombre de caractères à demander avant le curseur pour calculer le préfixe. */
    const val LOOKBACK: Int = TextNormalizer.MAX_INPUT_LENGTH + 2

    private val FRENCH_ELISIONS = setOf("c", "d", "j", "l", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu")

    private const val FRENCH = "fr"

    /**
     * Préfixe normalisé du mot en cours, `""` au début d'un mot, ou `null` quand le mot en cours
     * n'est pas un mot du lexique (chiffre, caractère hors de l'alphabet du lexique, plus de
     * [TextNormalizer.MAX_INPUT_LENGTH] caractères) ou que le texte est indisponible : le clavier
     * reste alors neutre.
     *
     * @param textBeforeCursor texte précédant le curseur (au moins les [LOOKBACK] derniers
     *   caractères), ou `null` si le champ ne le fournit pas.
     * @param selectionLength nombre de caractères sélectionnés : une sélection sera remplacée par
     *   la prochaine frappe, qui commence donc un mot.
     * @param language code de langue du lexique (`fr` active les élisions).
     */
    fun of(textBeforeCursor: CharSequence?, selectionLength: Int = 0, language: String = ""): String? {
        if (textBeforeCursor == null) return null
        if (selectionLength > 0) return ""
        val token = lastToken(textBeforeCursor)
        if (token.length > TextNormalizer.MAX_INPUT_LENGTH || token.any(Char::isDigit)) return null
        val word = if (language == FRENCH) withoutElisions(token) else token
        if (word.isEmpty()) return ""
        val key = try {
            TextNormalizer.normalize(word.replace('‑', '-'))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (LexiconAlphabet.isValidKey(key)) key else null
    }

    private fun lastToken(text: CharSequence): String {
        var start = text.length
        while (start > 0) {
            val c = text[start - 1]
            val inWord = isWordChar(c) || (isJoiner(c) && start > 1 && isWordChar(text[start - 2]))
            if (!inWord) break
            start--
        }
        return text.substring(start)
    }

    private fun withoutElisions(token: String): String {
        var rest = token
        while (true) {
            val apostrophe = rest.indexOfFirst(::isApostrophe)
            if (apostrophe <= 0 || rest.substring(0, apostrophe).lowercase() !in FRENCH_ELISIONS) return rest
            rest = rest.substring(apostrophe + 1)
        }
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    private fun isApostrophe(c: Char): Boolean = c == '\'' || c == '’' || c == 'ʼ'

    private fun isJoiner(c: Char): Boolean = isApostrophe(c) || c == '-' || c == '‑'
}
