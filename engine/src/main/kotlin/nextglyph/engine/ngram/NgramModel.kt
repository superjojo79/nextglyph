package nextglyph.engine.ngram

import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.text.TextNormalizer
import java.nio.ByteBuffer

/**
 * Modèle de mot suivant d'une langue (bigrammes et trigrammes, issue #51), lu directement dans un
 * `ByteBuffer` (typiquement un `mmap` de l'asset) : le chargement ne vérifie que l'en-tête et les
 * tailles, rien n'est copié ni instancié par mot. Format : [NgramFormat].
 *
 * Le modèle est statique : il ne retient **rien** de ce que l'utilisateur écrit. Le contexte est
 * fourni à chaque requête par l'appelant, qui le lit dans le champ à ce moment-là seulement.
 *
 * Les mots proposés sont des formes d'affichage (`j'`, `aujourd'hui`, `I`). Non thread-safe si le
 * tampon sous-jacent est partagé et repositionné ailleurs : [load] en prend une copie de la vue.
 */
class NgramModel private constructor(
    private val buffer: ByteBuffer,
    private val base: Int,
    private val size: Int,
    val languageTag: String,
    /** Nombre de mots du vocabulaire. */
    val vocabularySize: Int,
    /** Nombre de suivants de bigrammes. */
    val bigramCount: Int,
    /** Nombre de contextes de trigrammes. */
    val trigramContextCount: Int,
    /** Nombre de suivants de trigrammes. */
    val trigramCount: Int,
    private val blobLength: Int,
) {
    private val wordOffsets = NgramFormat.FIXED_HEADER_SIZE + languageTag.length
    private val blob = wordOffsets + 4 * (vocabularySize + 1)
    private val bigramOffsets = blob + blobLength
    private val bigramRecords = bigramOffsets + 4 * (vocabularySize + 2)
    private val trigramContexts = bigramRecords + NgramFormat.RECORD_SIZE * bigramCount
    private val trigramOffsets = trigramContexts + 4 * trigramContextCount
    private val trigramRecords = trigramOffsets + 4 * (trigramContextCount + 1)

    /** Taille du fichier en octets. */
    val sizeInBytes: Int get() = size

    /**
     * Les [limit] mots les plus probables après [context] (au plus deux derniers mots utilisés), par
     * score décroissant.
     *
     * - [context] vide : début de phrase. [START] en tête signale un début de phrase suivi des mots
     *   (`[START, "je"]` : trigrammes du premier mot).
     * - Repli (« stupid backoff ») : les suivants du trigramme gardent leur probabilité ; ceux du
     *   bigramme absents du trigramme comptent [BACKOFF] fois la leur. Avec un seul mot de contexte
     *   (ou un avant-dernier mot inconnu), le bigramme garde sa probabilité.
     * - Mot précédent inconnu du vocabulaire : aucune proposition. Avant-dernier mot inconnu :
     *   bigramme seul.
     *
     * @throws IllegalArgumentException si [limit] est négatif.
     * @throws NgramFormatException si le fichier est corrompu à l'endroit lu.
     */
    fun next(context: List<String>, limit: Int): List<NextWord> {
        require(limit >= 0) { "La limite doit être positive ou nulle ($limit)." }
        if (limit == 0) return emptyList()
        val last = if (context.isEmpty()) NgramFormat.START_ID else idOf(context.last())
        if (last < 0) return emptyList()
        val before = if (context.size >= 2) idOf(context[context.size - 2]) else -1

        val scores = LinkedHashMap<Int, Double>()
        if (before >= 0) {
            val index = findTrigram(before, last)
            if (index >= 0) readFollowers(trigramOffsets, trigramRecords, index, trigramCount, 1.0, scores)
        }
        val fallback = LinkedHashMap<Int, Double>()
        // Le bigramme est l'ordre le plus haut sans avant-dernier mot connu ; sinon il est un repli.
        readFollowers(bigramOffsets, bigramRecords, last, bigramCount, if (before >= 0) BACKOFF else 1.0, fallback)
        for ((id, score) in fallback) scores.putIfAbsent(id, score)

        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { NextWord(display(it.key), it.value) }
    }

    /** Vrai si [word] (clé normalisée) est dans le vocabulaire. */
    fun contains(word: String): Boolean = idOf(word) > 0

    /**
     * Probabilité de [word] juste après [previous] d'après les bigrammes (0 si l'un des deux est inconnu du
     * vocabulaire ou si le bigramme a été élagué). Sans repli ni trigramme : c'est la probabilité conservée dans
     * le fichier, utilisée pour comparer deux mots consécutifs (erreurs d'espace, issue #52).
     *
     * @throws NgramFormatException si le fichier est corrompu à l'endroit lu.
     */
    fun bigramProbability(previous: String, word: String): Double {
        val last = idOf(previous)
        val id = idOf(word)
        if (last < 0 || id <= 0) return 0.0
        val scores = HashMap<Int, Double>()
        readFollowers(bigramOffsets, bigramRecords, last, bigramCount, 1.0, scores)
        return scores[id] ?: 0.0
    }

    private fun readFollowers(offsets: Int, records: Int, context: Int, total: Int, factor: Double, out: MutableMap<Int, Double>) {
        val from = u32(offsets + 4 * context)
        val to = u32(offsets + 4 * (context + 1))
        if (from > to || to > total) throw corrupt("suivants $from..$to hors de $total pour le contexte $context")
        for (i in from until to) {
            val at = records + NgramFormat.RECORD_SIZE * i
            val id = u16(at)
            if (id < 1 || id > vocabularySize) throw corrupt("identifiant $id hors du vocabulaire ($vocabularySize mots)")
            out[id] = factor * ProbabilityCodec.decode(u8(at + 2))
        }
    }

    /** Identifiant de [word] : [NgramFormat.START_ID] pour [START], −1 si inconnu. */
    private fun idOf(word: String): Int {
        if (word == START) return NgramFormat.START_ID
        val key = try {
            TextNormalizer.normalize(word)
        } catch (_: IllegalArgumentException) {
            return -1
        }
        if (!LexiconAlphabet.isValidKey(key)) return -1
        val bytes = key.toByteArray(Charsets.US_ASCII)
        var low = 1
        var high = vocabularySize
        while (low <= high) {
            val mid = (low + high) ushr 1
            val compared = compareKey(mid, bytes)
            when {
                compared < 0 -> low = mid + 1
                compared > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Compare la clé du mot [id] à [key] (octets non signés, puis longueur). */
    private fun compareKey(id: Int, key: ByteArray): Int {
        val start = wordStart(id)
        val length = u8(start)
        checkWord(start, length)
        val common = minOf(length, key.size)
        for (i in 0 until common) {
            val diff = u8(start + 1 + i) - (key[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return length - key.size
    }

    private fun display(id: Int): String {
        val start = wordStart(id)
        val keyLength = u8(start)
        checkWord(start, keyLength)
        val displayLength = u8(start + 1 + keyLength)
        val length = if (displayLength == 0) keyLength else displayLength
        val from = if (displayLength == 0) start + 1 else start + 2 + keyLength
        if (displayLength != 0) checkWord(start + 1 + keyLength, displayLength)
        return String(bytes(from, length), Charsets.UTF_8)
    }

    /** Position absolue (dans la vue) du mot [id] ; vérifie qu'elle tombe dans la zone des mots. */
    private fun wordStart(id: Int): Int {
        val offset = u32(wordOffsets + 4 * (id - 1))
        val end = u32(wordOffsets + 4 * id)
        if (offset >= end || end > blobLength) throw corrupt("mot $id : offsets $offset..$end hors de la zone ($blobLength octets)")
        return blob + offset
    }

    /** Vérifie que [length] octets, précédés d'un octet de longueur, tiennent dans le fichier. */
    private fun checkWord(start: Int, length: Int) {
        if (start + 1 + length > bigramOffsets) throw corrupt("mot tronqué à la position $start")
    }

    /** Index du contexte (a, b) dans la zone des trigrammes, ou −1. */
    private fun findTrigram(a: Int, b: Int): Int {
        val target = (a.toLong() shl 16) or b.toLong()
        var low = 0
        var high = trigramContextCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = u32Long(trigramContexts + 4 * mid)
            when {
                value < target -> low = mid + 1
                value > target -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun bytes(from: Int, length: Int): ByteArray {
        if (from < 0 || length < 0 || from + length > size) throw corrupt("lecture hors du fichier à la position $from")
        val out = ByteArray(length)
        val view = buffer.duplicate()
        view.position(base + from)
        view.get(out)
        return out
    }

    private fun u8(at: Int): Int {
        if (at < 0 || at >= size) throw corrupt("lecture hors du fichier à la position $at")
        return buffer.get(base + at).toInt() and 0xFF
    }

    private fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

    private fun u32Long(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

    private fun u32(at: Int): Int {
        val value = u32Long(at)
        if (value > Int.MAX_VALUE) throw corrupt("valeur $value trop grande à la position $at")
        return value.toInt()
    }

    private fun corrupt(message: String) = NgramFormatException("Modèle de mot suivant invalide : $message.")

    companion object {
        /** Mot spécial qui, en tête d'un contexte, désigne le début de phrase. */
        const val START: String = "<s>"

        /** Facteur appliqué aux scores d'un bigramme absent du trigramme (repli, issue #51). */
        const val BACKOFF: Double = 0.4

        /**
         * Charge un modèle depuis [buffer] (de sa position à sa limite) sans le copier.
         *
         * @throws NgramFormatException si l'en-tête est invalide ou si la taille ne correspond pas exactement.
         */
        fun load(buffer: ByteBuffer): NgramModel {
            val view = buffer.duplicate()
            val base = view.position()
            val size = view.remaining()
            val reader = HeaderReader(view, base, size)
            for (expected in NgramFormat.MAGIC) {
                if (reader.u8() != expected.toInt()) throw invalid("magic invalide : ce n'est pas un modèle de mot suivant Nextglyph")
            }
            val version = reader.u8() shl 8 or reader.u8()
            if (version != NgramFormat.VERSION) {
                throw invalid("version $version non prise en charge (attendue : ${NgramFormat.VERSION})")
            }
            val tagLength = reader.u8()
            if (tagLength !in 1..NgramFormat.MAX_TAG_LENGTH) throw invalid("longueur d'étiquette invalide ($tagLength)")
            val tag = String(ByteArray(tagLength) { reader.u8().toByte() }, Charsets.US_ASCII)
            if (!NgramFormat.isValidTag(tag)) throw invalid("étiquette de langue invalide : « $tag »")
            val vocabulary = reader.i32()
            val blobLength = reader.i32()
            val bigrams = reader.i32()
            val trigramContexts = reader.i32()
            val trigrams = reader.i32()
            if (vocabulary !in 0..NgramFormat.MAX_VOCABULARY) throw invalid("vocabulaire invalide ($vocabulary mots)")
            if (blobLength < 0 || bigrams < 0 || trigramContexts < 0 || trigrams < 0) throw invalid("compteurs négatifs")
            val expected = NgramFormat.FIXED_HEADER_SIZE.toLong() + tagLength +
                4L * (vocabulary + 1) + blobLength +
                4L * (vocabulary + 2) + NgramFormat.RECORD_SIZE.toLong() * bigrams +
                4L * trigramContexts + 4L * (trigramContexts + 1) + NgramFormat.RECORD_SIZE.toLong() * trigrams
            if (size.toLong() != expected) throw invalid("taille incohérente : $size octets au lieu de $expected")
            return NgramModel(view, base, size, tag, vocabulary, bigrams, trigramContexts, trigrams, blobLength)
        }

        private fun invalid(message: String) = NgramFormatException("Modèle de mot suivant invalide : $message.")
    }

    private class HeaderReader(private val buffer: ByteBuffer, private val base: Int, private val size: Int) {
        private var position = 0

        fun u8(): Int {
            if (position >= size) throw invalid("en-tête tronqué à l'octet $position")
            return buffer.get(base + position++).toInt() and 0xFF
        }

        fun i32(): Int = u8() shl 24 or (u8() shl 16) or (u8() shl 8) or u8()
    }
}
