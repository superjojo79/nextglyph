package nextglyph.tools.wordlists

import java.util.Locale

/** [code] : ISO 639-1 (noms de fichiers) ; [tatoebaCode] : ISO 639-3, utilisé dans les exports Tatoeba. */
enum class Language(val code: String, val tatoebaCode: String) {
    FR("fr", "fra"),
    EN("en", "eng"),
}

/**
 * Découpe une phrase en mots (casse conservée).
 *
 * - Un mot est une suite de lettres (et signes combinants) ; l'apostrophe et le trait d'union
 *   y sont admis **entre deux lettres** (`aujourd'hui`, `peut-être`, `don't`, `rock'n'roll`).
 *   Les apostrophes typographiques sont ramenées à `'`.
 * - Une suite contenant un chiffre est ignorée (`3D`, `b2b`).
 * - Français : une élision (`l'`, `d'`, `j'`, `qu'`, `jusqu'`...) est un mot à part entière,
 *   suivi du mot élidé : `l'école` donne `l'` et `école`. C'est la convention du §5.8 :
 *   l'élision est validée seule, puis le mot suivant commence sans espace. Le lexique français
 *   contient donc `l'`, `qu'`... comme entrées.
 * - Anglais : aucune coupure ; `don't`, `Tom's` restent un seul mot.
 */
class Tokenizer(private val language: Language) {

    fun tokens(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = -1
        var hasDigit = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            val wordChar = isWordChar(ch)
            val joiner = isJoiner(ch) && start >= 0 && isWordChar(text[index - 1]) &&
                index + 1 < text.length && isWordChar(text[index + 1])
            if (wordChar || joiner) {
                if (start < 0) {
                    start = index
                    hasDigit = false
                }
                if (ch.isDigit()) hasDigit = true
            } else if (start >= 0) {
                emit(text.substring(start, index), hasDigit, result)
                start = -1
            }
            index++
        }
        if (start >= 0) emit(text.substring(start), hasDigit, result)
        return result
    }

    private fun emit(run: String, hasDigit: Boolean, out: MutableList<String>) {
        if (hasDigit) return
        val word = run.replace('’', '\'').replace('ʼ', '\'').replace('‑', '-')
        if (language == Language.FR) splitElisions(word, out) else out += word
    }

    private fun splitElisions(word: String, out: MutableList<String>) {
        var rest = word
        while (true) {
            val apostrophe = rest.indexOf('\'')
            val isElision = apostrophe > 0 &&
                apostrophe < rest.length - 1 &&
                rest.substring(0, apostrophe).lowercase(Locale.ROOT) in ELISION_PREFIXES
            if (!isElision) break
            out += rest.substring(0, apostrophe + 1)
            rest = rest.substring(apostrophe + 1)
        }
        out += rest
    }

    private fun isWordChar(ch: Char): Boolean =
        !isJoiner(ch) && (ch.isLetterOrDigit() || Character.getType(ch) == Character.NON_SPACING_MARK.toInt())

    private fun isJoiner(ch: Char): Boolean =
        ch == '\'' || ch == '’' || ch == 'ʼ' || ch == '-' || ch == '‑'

    companion object {
        val ELISION_PREFIXES: Set<String> =
            setOf("c", "d", "j", "l", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu")
    }
}
