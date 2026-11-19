package nextglyph.tools.analysis

import nextglyph.engine.lexicon.InvalidFormPolicy
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.engine.text.TextNormalizer
import nextglyph.tools.wordlists.Tokenizer

/** Erreur de lecture des entrées de l'analyse (liste de mots, corpus). */
class AnalysisException(message: String) : Exception(message)

/** Lecture d'une liste de mots `forme<TAB>fréquence` (sortie de `buildWordLists`) en lexique en mémoire. */
object WordListLoader {

    /**
     * Construit le lexique avec `LexiconBuilder` en mode `SKIP` (les formes non indexables sont
     * ignorées, comme à la construction des lexiques binaires). Les lignes vides sont ignorées.
     *
     * @throws AnalysisException si une ligne n'a pas la forme `forme<TAB>entier > 0`.
     */
    fun load(lines: Sequence<String>, languageTag: String): Lexicon {
        val builder = LexiconBuilder(languageTag, InvalidFormPolicy.SKIP)
        lines.forEachIndexed { index, line ->
            if (line.isNotEmpty()) {
                val tab = line.indexOf('\t')
                val frequency = if (tab > 0) line.substring(tab + 1).toLongOrNull() else null
                if (frequency == null || frequency <= 0) {
                    throw AnalysisException("Liste de mots ($languageTag), ligne ${index + 1} mal formée : « $line ».")
                }
                builder.add(line.substring(0, tab), frequency)
            }
        }
        return builder.build()
    }
}

/**
 * Occurrences des mots d'un corpus, par clé normalisée.
 *
 * @property counts clé → nombre d'occurrences, par nombre décroissant puis clé croissante.
 * @property tokens nombre total de mots lus.
 * @property rejected mots dont la clé est hors de l'alphabet du lexique (`naïve` passe, `straße` non) :
 *   ils ne sont pas analysés.
 */
class WordCounts(val counts: Map<String, Long>, val tokens: Long, val rejected: Long) {
    /** Nombre d'occurrences analysées. */
    val analyzed: Long get() = tokens - rejected
}

/** Découpage du corpus de test en clés de lexique (même tokenisation que `buildWordLists`). */
object CorpusWords {

    /** Clé de lexique de [token], ou `null` si elle ne peut pas être indexée. */
    fun keyOf(token: String): String? {
        val key = try {
            TextNormalizer.normalize(token)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return key.takeIf(LexiconAlphabet::isValidKey)
    }

    /** Clés des mots de [sentence] ; `null` pour un mot non indexable. */
    fun keys(sentence: String, tokenizer: Tokenizer): List<String?> = tokenizer.tokens(sentence).map(::keyOf)

    /** Compte les clés des mots de [sentences]. */
    fun count(sentences: Sequence<String>, tokenizer: Tokenizer): WordCounts {
        val counts = HashMap<String, Long>()
        var tokens = 0L
        var rejected = 0L
        for (sentence in sentences) {
            for (key in keys(sentence, tokenizer)) {
                tokens++
                if (key == null) rejected++ else counts.merge(key, 1L, Long::plus)
            }
        }
        val sorted = LinkedHashMap<String, Long>()
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .forEach { sorted[it.key] = it.value }
        return WordCounts(sorted, tokens, rejected)
    }
}
