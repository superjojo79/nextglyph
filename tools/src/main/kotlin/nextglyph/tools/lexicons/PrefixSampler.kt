package nextglyph.tools.lexicons

import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.Tokenizer
import nextglyph.tools.wordlists.WordFilter

/** Extrait, des phrases d'un corpus de test, les préfixes réellement tapés. */
object PrefixSampler {

    /**
     * Pour chaque mot valide (clé normalisée), tous ses préfixes non vides, mot complet
     * compris, dans l'ordre du corpus et avec leurs répétitions (la moyenne reflète donc
     * l'usage). S'arrête dès que [limit] préfixes sont réunis.
     */
    fun sample(sentences: List<String>, language: Language, limit: Int): List<String> {
        require(limit >= 0) { "La limite doit être positive ou nulle ($limit)." }
        val tokenizer = Tokenizer(language)
        val result = ArrayList<String>()
        for (sentence in sentences) {
            for (token in tokenizer.tokens(sentence)) {
                val verdict = WordFilter.check(token) as? WordFilter.Verdict.Accepted ?: continue
                for (length in 1..verdict.key.length) {
                    if (result.size >= limit) return result
                    result += verdict.key.substring(0, length)
                }
            }
        }
        return result
    }
}
