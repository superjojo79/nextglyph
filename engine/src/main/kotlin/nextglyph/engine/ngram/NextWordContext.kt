package nextglyph.engine.ngram

/**
 * Lecture du contexte du mot suivant dans le texte qui précède le curseur (issue #51).
 *
 * Le texte est lu au moment de la prédiction et jamais conservé. Les mots sont ceux de la phrase en
 * cours seulement (après `.`, `!`, `?`, `…` ou un saut de ligne), découpés comme le corpus
 * d'entraînement : en français, une élision est un mot à part (`l'école` donne `l'` puis `école`).
 */
object NextWordContext {

    /** Nombre de caractères à demander avant le curseur. */
    const val LOOKBACK: Int = 160

    private const val SENTENCE_END = ".!?…\n"
    private const val CLAUSE_END = ",;:"
    private const val OPENING = "(«“‘\"-–—[{/*•"
    private val ELISIONS = setOf("c", "d", "j", "l", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu")

    private class Token(val word: String, val hasDigit: Boolean, val breakBefore: Boolean)

    /**
     * Contexte à donner à [NgramModel.next] pour [textBefore], ou `null` si aucune prédiction n'a de
     * sens : texte indisponible, curseur dans un mot (pas d'espace avant lui), chiffre juste avant.
     *
     * - Champ vide, début de phrase, ou juste après un signe ouvrant : [NgramModel.START] seul.
     * - Premier mot de la phrase : `[START, mot]` ; sinon les deux derniers mots, ou le dernier
     *   seul si une virgule, un point-virgule ou un signe les sépare (pas de trigramme à travers).
     *
     * @param elisions vrai pour le français : `j'` au curseur, sans espace, est un contexte valide.
     * @param lookback taille de la fenêtre demandée à l'éditeur : un texte plus court commence la phrase.
     */
    fun read(textBefore: CharSequence?, elisions: Boolean, lookback: Int = LOOKBACK): List<String>? {
        if (textBefore == null) return null
        val text = textBefore.toString()
        if (text.isEmpty()) return listOf(NgramModel.START)
        val trailing = text.last()
        val endsInElision = !trailing.isWhitespace() && elisions && endsWithElision(text)
        if (!trailing.isWhitespace() && !endsInElision) return null

        val sentenceStart = text.indexOfLast { it in SENTENCE_END } + 1
        val startKnown = sentenceStart > 0 || text.length < lookback
        val tokens = tokenize(text.substring(sentenceStart), elisions)
        val lastSign = text.trimEnd().lastOrNull()
        if (tokens.isEmpty() || (lastSign != null && lastSign in OPENING)) return listOf(NgramModel.START)

        val last = tokens.last()
        if (last.hasDigit) return null
        val before = tokens.getOrNull(tokens.size - 2)?.takeIf { !last.breakBefore && !it.hasDigit }
        val clauseBreak = lastSign != null && lastSign in CLAUSE_END
        return when {
            before != null && !clauseBreak -> listOf(before.word, last.word)
            tokens.size == 1 && startKnown && !clauseBreak -> listOf(NgramModel.START, last.word)
            else -> listOf(last.word)
        }
    }

    private fun endsWithElision(text: String): Boolean {
        if (text.last() != '\'' && text.last() != '’' && text.last() != 'ʼ') return false
        var start = text.length - 1
        while (start > 0 && text[start - 1].isLetter()) start--
        if (start == text.length - 1) return false
        if (start > 0 && isJoiner(text[start - 1])) return false
        return text.substring(start, text.length - 1).lowercase() in ELISIONS
    }

    private fun tokenize(tail: String, elisions: Boolean): List<Token> {
        val tokens = ArrayList<Token>()
        var index = 0
        var gapHasSign = false
        while (index < tail.length) {
            val c = tail[index]
            if (!isWordChar(c)) {
                if (!c.isWhitespace()) gapHasSign = true
                index++
                continue
            }
            var end = index
            var digit = false
            while (end < tail.length) {
                val ch = tail[end]
                val inside = isWordChar(ch) || (isJoiner(ch) && end + 1 < tail.length && isWordChar(tail[end + 1]))
                if (!inside) break
                if (ch.isDigit()) digit = true
                end++
            }
            var run = tail.substring(index, end).replace('’', '\'').replace('ʼ', '\'').replace('‑', '-')
            if (elisions && end == tail.length - 1 && isApostrophe(tail[end]) && run.lowercase() in ELISIONS) {
                run += "'"
                end++
            }
            val first = tokens.isEmpty()
            val words = if (elisions && !digit) splitElisions(run) else listOf(run)
            words.forEachIndexed { i, word ->
                tokens += Token(word, digit, breakBefore = i == 0 && !first && gapHasSign)
            }
            gapHasSign = false
            index = end
        }
        return tokens
    }

    private fun splitElisions(word: String): List<String> {
        val out = ArrayList<String>()
        var rest = word
        while (true) {
            val apostrophe = rest.indexOf('\'')
            if (apostrophe <= 0 || apostrophe >= rest.length - 1 || rest.substring(0, apostrophe).lowercase() !in ELISIONS) break
            out += rest.substring(0, apostrophe + 1)
            rest = rest.substring(apostrophe + 1)
        }
        out += rest
        return out
    }

    private fun isWordChar(c: Char): Boolean =
        !isJoiner(c) && (c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt())

    private fun isApostrophe(c: Char): Boolean = c == '\'' || c == '’' || c == 'ʼ'

    private fun isJoiner(c: Char): Boolean = isApostrophe(c) || c == '-' || c == '‑'
}
