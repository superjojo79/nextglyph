package nextglyph.tools.wordlists

import java.nio.file.Files
import java.nio.file.Path

data class Sentence(val id: Long, val text: String)

/** Lecture des exports Tatoeba : `identifiant<TAB>langue<TAB>texte`. */
object TatoebaReader {

    data class Result(val sentences: List<Sentence>, val malformed: Int, val otherLanguage: Int)

    /**
     * Lignes mal formées (moins de 3 colonnes, identifiant non numérique ou négatif, texte vide)
     * et phrases d'une autre langue que [language] sont ignorées et comptées.
     */
    fun read(lines: Sequence<String>, language: Language): Result {
        val sentences = ArrayList<Sentence>()
        var malformed = 0
        var other = 0
        for (rawLine in lines) {
            if (rawLine.isBlank()) continue
            val columns = rawLine.split('\t', limit = 3)
            val id = columns.getOrNull(0)?.trim()?.toLongOrNull()
            val text = columns.getOrNull(2)?.trim().orEmpty()
            when {
                columns.size < 3 || id == null || id < 0 || text.isEmpty() -> malformed++
                columns[1].trim() != language.tatoebaCode -> other++
                else -> sentences += Sentence(id, text)
            }
        }
        return Result(sentences, malformed, other)
    }
}

/** Partition déterministe entraînement / test, fondée sur un hachage stable de l'identifiant. */
object SentencePartition {

    /** Part de phrases de test, en pour cent. */
    const val TEST_PERCENT: Int = 10

    /** Finaliseur SplitMix64 : mélange bien les identifiants consécutifs, stable à jamais. */
    fun mix(value: Long): Long {
        var z = value + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    fun isTest(id: Long): Boolean = Math.floorMod(mix(id), 100L) < TEST_PERCENT
}

data class CorpusSplit(val train: List<String>, val test: List<String>, val duplicates: Int)

object CorpusSplitter {

    private val WHITESPACE = Regex("\\s+")

    /**
     * Supprime les doublons de texte (on garde le plus petit identifiant : Tatoeba contient des
     * phrases identiques sous des identifiants différents, qui feraient fuir le test vers
     * l'entraînement), puis partitionne par [SentencePartition]. Les phrases sortent triées par
     * identifiant : le résultat ne dépend pas de l'ordre d'entrée.
     */
    fun split(sentences: List<Sentence>): CorpusSplit {
        val seen = HashSet<String>()
        val train = ArrayList<String>()
        val test = ArrayList<String>()
        var duplicates = 0
        for (sentence in sentences.sortedBy { it.id }) {
            val text = sentence.text.replace(Regex("\\s+"), " ")
            if (!seen.add(text)) {
                duplicates++
            } else if (SentencePartition.isTest(sentence.id)) {
                test += text
            } else {
                train += text
            }
        }
        return CorpusSplit(train, test, duplicates)
    }
}

/** Corpus mixte pour simuler un utilisateur bilingue : blocs de phrases FR et EN alternés. */
object MixedCorpus {

    const val MAX_BLOCK: Int = 8

    data class Mixed(val sentences: List<String>, val languages: List<Language>)

    /**
     * Alterne un bloc français, un bloc anglais, etc. ; la taille de chaque bloc (1 à [MAX_BLOCK])
     * vient d'un hachage de `seed` et du numéro de bloc, donc le résultat est reproductible.
     * S'arrête quand la langue dont c'est le tour n'a plus de phrase.
     */
    fun interleave(fr: List<String>, en: List<String>, seed: Long = DEFAULT_SEED): Mixed {
        val sentences = ArrayList<String>()
        val languages = ArrayList<Language>()
        var frIndex = 0
        var enIndex = 0
        var block = 0L
        while (true) {
            val french = block % 2 == 0L
            val source = if (french) fr else en
            val from = if (french) frIndex else enIndex
            if (from >= source.size) break
            val size = 1 + Math.floorMod(SentencePartition.mix(seed + block), MAX_BLOCK.toLong()).toInt()
            val to = minOf(source.size, from + size)
            for (i in from until to) {
                sentences += source[i]
                languages += if (french) Language.FR else Language.EN
            }
            if (french) frIndex = to else enIndex = to
            block++
        }
        return Mixed(sentences, languages)
    }

    const val DEFAULT_SEED: Long = 20261119L
}

/** Écriture d'un fichier texte UTF-8, une ligne par élément, fins de ligne `\n`. */
fun writeLines(path: Path, lines: List<String>) {
    path.parent?.let(Files::createDirectories)
    Files.newBufferedWriter(path, Charsets.UTF_8).use { writer ->
        for (line in lines) {
            writer.write(line)
            writer.write("\n")
        }
    }
}
