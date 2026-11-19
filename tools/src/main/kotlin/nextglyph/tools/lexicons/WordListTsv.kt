package nextglyph.tools.lexicons

import nextglyph.tools.wordlists.WordEntry

/** Fichier de liste de mots ou donnée de lexique invalide. */
class LexiconToolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lecture des listes de mots `forme<TAB>fréquence`, produites par `buildWordLists`. */
object WordListTsv {

    /**
     * Lit les lignes d'une liste de mots. Format strict : exactement deux champs séparés par
     * une tabulation, une forme non vide, une fréquence entière strictement positive.
     * Une liste vide est valide (lexique vide).
     *
     * @throws LexiconToolException citant le numéro de la première ligne invalide.
     */
    fun parse(lines: List<String>, source: String): List<WordEntry> =
        lines.mapIndexed { index, line -> parseLine(line, "$source, ligne ${index + 1}") }

    private fun parseLine(line: String, where: String): WordEntry {
        val fields = line.split('\t')
        if (fields.size != 2) {
            throw LexiconToolException("$where : deux champs séparés par une tabulation attendus (« $line »).")
        }
        val (form, raw) = fields
        if (form.isEmpty()) throw LexiconToolException("$where : forme vide.")
        val frequency = raw.toLongOrNull()
        if (frequency == null || frequency <= 0) {
            throw LexiconToolException("$where : fréquence invalide « $raw » (entier strictement positif attendu).")
        }
        return WordEntry(form, frequency)
    }
}
