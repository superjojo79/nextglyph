package nextglyph.engine.lexicon

import nextglyph.engine.text.TextNormalizer
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.PriorityQueue

/**
 * Lexique d'une langue (spec §10.1), lu directement dans son format binaire.
 *
 * La structure est un trie compact (arbre radix : les chaînes de nœuds à un seul enfant
 * sont fusionnées en une étiquette) dont chaque nœud porte la masse de son sous-arbre et la
 * fréquence maximale d'une forme du sous-arbre. Le format est décrit dans [LexiconFormat] et
 * le choix de structure dans `docs/DECISIONS.md`.
 *
 * Aucune structure n'est reconstruite au chargement : chaque requête lit les octets du
 * [ByteBuffer] fourni, qui peut être un `MappedByteBuffer`. Les lectures sont absolues, sans
 * état partagé : un même lexique peut être interrogé depuis plusieurs fils d'exécution, à
 * condition que personne ne modifie le contenu du tampon.
 *
 * Les clés et préfixes passés aux requêtes sont des clés déjà normalisées
 * (`TextNormalizer.normalize`). Un préfixe contenant un caractère hors de [LexiconAlphabet]
 * est simplement inconnu.
 */
class Lexicon private constructor(
    private val buffer: ByteBuffer,
    private val fileStart: Int,
    private val dataStart: Int,
    private val dataLength: Int,
    private val rootOffset: Int,
    /** Étiquette de langue BCP 47 (`fr`, `en-GB`). */
    val languageTag: String,
    /** Nombre de clés distinctes. */
    val keyCount: Int,
    /** Nombre de formes d'affichage (au moins [keyCount]). */
    val formCount: Int,
) {

    /** Taille du lexique sérialisé, en octets. */
    val sizeInBytes: Int get() = dataStart - fileStart + dataLength

    /**
     * Distribution de la lettre suivant [prefix] : `P(c | p)` pour chaque continuation,
     * `P(fin | p)` et la masse totale. Le préfixe vide donne la distribution des initiales.
     * Coût : la descente sur [prefix], puis la lecture d'au plus 28 enregistrements.
     */
    fun distribution(prefix: String): Distribution {
        val cursor = Cursor()
        val consumed = cursor.locate(prefix)
        return when {
            consumed == UNKNOWN -> Distribution.UNKNOWN
            consumed == ROOT -> cursor.blockDistribution(rootOffset, dataLength, 0.0)
            // Préfixe au milieu d'une étiquette : une seule continuation possible.
            consumed < cursor.labelLength -> cursor.singleContinuation(consumed)
            cursor.hasChildren -> cursor.blockDistribution(cursor.childBlock, cursor.position, cursor.terminalMass)
            else -> Distribution(false, cursor.terminalMass, 1.0, DoubleArray(LexiconAlphabet.SIZE))
        }
    }

    /**
     * Masse des mots commençant par [prefix] (somme de leurs fréquences, 0 si le préfixe est
     * inconnu), lue sur le seul nœud atteint : plus rapide que [distribution] quand seules la
     * connaissance du préfixe et sa masse comptent (correction, §10.4). Même quantification que
     * [Distribution.totalMass], dont elle peut différer de 0,07 % au plus.
     */
    fun prefixMass(prefix: String): Double {
        val cursor = Cursor()
        return when (cursor.locate(prefix)) {
            UNKNOWN -> 0.0
            ROOT -> rootMass
            else -> cursor.mass
        }
    }

    private val rootMass: Double by lazy { distribution("").totalMass }

    /**
     * Les [limit] formes les plus fréquentes dont la clé commence par [prefix] (le préfixe
     * lui-même compris s'il est un mot), par fréquence décroissante, puis clé et forme
     * croissantes à fréquence égale.
     *
     * Recherche best-first : une file de priorité ordonnée par la fréquence maximale stockée
     * dans chaque nœud n'ouvre que les sous-arbres susceptibles de contenir un résultat.
     *
     * @throws IllegalArgumentException si [limit] est négatif.
     */
    fun completions(prefix: String, limit: Int): List<LexiconEntry> {
        require(limit >= 0) { "La limite doit être positive ou nulle ($limit)." }
        val result = ArrayList<LexiconEntry>()
        val cursor = Cursor()
        val queue = PriorityQueue(CANDIDATE_ORDER)
        when (val consumed = cursor.locate(prefix)) {
            UNKNOWN -> {} // Préfixe inconnu : la file reste vide.
            ROOT -> cursor.pushChildren(rootOffset, dataLength, "", queue)
            else -> cursor.pushRecord(prefix.dropLast(consumed) + cursor.label(), queue)
        }
        while (result.size < limit) {
            val candidate = queue.poll() ?: break
            if (candidate.entry == null) cursor.expand(candidate, queue) else result += candidate.entry
        }
        return result
    }

    /** Les formes d'affichage de la clé exacte [key], par fréquence décroissante ; vide si absente. */
    fun displayForms(key: String): List<LexiconEntry> {
        val cursor = Cursor()
        // Pour la clé vide (ROOT), aucun enregistrement n'est lu : `terminal` reste faux.
        val found = cursor.locate(key) == cursor.labelLength && cursor.terminal
        return if (found) cursor.readForms(key) else emptyList()
    }

    /** Copie du lexique sérialisé, identique au fichier chargé. */
    fun toByteArray(): ByteArray = ByteArray(sizeInBytes) { buffer.get(fileStart + it) }

    /** Écrit le lexique sérialisé dans [out]. */
    fun writeTo(out: OutputStream) {
        out.write(toByteArray())
    }

    /** Élément de la file best-first : un nœud à ouvrir ([entry] nul) ou une forme à renvoyer. */
    private class Candidate private constructor(
        val frequency: Long,
        val key: String,
        val form: String,
        val entry: LexiconEntry?,
        val position: Int,
    ) {
        /** Nœud en [position], de clé [key], dont aucune forme ne dépasse [maxFrequency]. */
        constructor(maxFrequency: Long, key: String, position: Int) : this(maxFrequency, key, "", null, position)

        constructor(entry: LexiconEntry) : this(entry.frequency, entry.key, entry.form, entry, 0)
    }

    /**
     * Curseur de lecture : position courante et champs du dernier enregistrement lu.
     * Une instance par requête, jamais partagée.
     */
    private inner class Cursor {
        var position = 0
        private var next = 0
        private var firstIndex = 0
        private var labelStart = 0
        var labelLength = 0
        var hasChildren = false
        var terminal = false
        var mass = 0.0
            private set
        private var maxFrequency = 0L
        var childBlock = 0
        private var formsStart = 0
        var terminalMass = 0.0

        /**
         * Descend sur [prefix]. Renvoie [UNKNOWN], [ROOT] pour le préfixe vide, ou le nombre
         * de caractères consommés dans l'étiquette de l'enregistrement courant.
         */
        fun locate(prefix: String): Int {
            if (prefix.isEmpty()) return ROOT
            var block = rootOffset
            var parent = dataLength
            var depth = 0
            while (true) {
                if (!findChild(block, parent, LexiconAlphabet.indexOf(prefix[depth]))) return UNKNOWN
                depth++
                for (j in 1 until labelLength) {
                    if (depth == prefix.length) return j
                    if (labelIndex(j) != LexiconAlphabet.indexOf(prefix[depth])) return UNKNOWN
                    depth++
                }
                if (depth == prefix.length) return labelLength
                if (!hasChildren) return UNKNOWN
                block = childBlock
                parent = position
            }
        }

        private fun findChild(block: Int, parent: Int, index: Int): Boolean {
            next = block
            repeat(u8()) {
                readChild(parent)
                if (firstIndex == index) return true
            }
            return false
        }

        fun blockDistribution(block: Int, parent: Int, endMass: Double): Distribution {
            val probabilities = DoubleArray(LexiconAlphabet.SIZE)
            var total = endMass
            next = block
            repeat(u8()) {
                readChild(parent)
                probabilities[firstIndex] += mass
                total += mass
            }
            if (total == 0.0) return Distribution.UNKNOWN
            for (i in probabilities.indices) probabilities[i] /= total
            return Distribution(false, total, endMass / total, probabilities)
        }

        fun singleContinuation(consumed: Int): Distribution {
            val probabilities = DoubleArray(LexiconAlphabet.SIZE)
            probabilities[labelIndex(consumed)] = 1.0
            return Distribution(false, mass, 0.0, probabilities)
        }

        /** Ajoute à [queue] les enfants du bloc [block], dont les clés prolongent [key]. */
        fun pushChildren(block: Int, parent: Int, key: String, queue: PriorityQueue<Candidate>) {
            next = block
            val positions = IntArray(u8()) {
                readChild(parent)
                position
            }
            for (child in positions) {
                readRecord(child)
                pushRecord(key + label(), queue)
            }
        }

        /** Ajoute à [queue] l'enregistrement courant, de clé [key]. */
        fun pushRecord(key: String, queue: PriorityQueue<Candidate>) {
            val record = position
            if (hasChildren) {
                queue += Candidate(maxFrequency, key, record)
            } else {
                readForms(key).forEach { queue += Candidate(it) }
            }
        }

        fun expand(node: Candidate, queue: PriorityQueue<Candidate>) {
            readRecord(node.position)
            if (terminal) readForms(node.key).forEach { queue += Candidate(it) }
            pushChildren(childBlock, node.position, node.key, queue)
        }

        /** Les formes de l'enregistrement courant, terminal, de clé [key]. */
        fun readForms(key: String): List<LexiconEntry> {
            next = formsStart
            return List(varInt(Int.MAX_VALUE)) {
                val frequency = varLong()
                val length = varInt(Int.MAX_VALUE)
                LexiconEntry(if (length == 0) key else utf8(length), key, frequency)
            }
        }

        /**
         * Lit l'enregistrement suivant d'un bloc enfant de l'enregistrement [parent]. Il doit
         * se terminer avant son parent : les positions décroissent strictement le long de
         * toute descente, qui se termine donc même sur un fichier corrompu.
         */
        private fun readChild(parent: Int) {
            readRecord(next)
            if (next > parent) throw corrupt("l'enregistrement $position déborde sur son parent $parent")
        }

        /** Lit l'enregistrement situé en [record] ; [next] pointe ensuite juste après. */
        fun readRecord(record: Int) {
            position = record
            next = record
            val header = u8()
            firstIndex = checkIndex(header and LexiconFormat.CHAR_MASK)
            hasChildren = header and LexiconFormat.HAS_CHILDREN != 0
            terminal = header and LexiconFormat.TERMINAL != 0
            labelLength = 1
            if (header and LexiconFormat.LONG_LABEL != 0) {
                val extra = varInt(MAX_KEY_LENGTH - 1)
                labelStart = next
                skip(extra)
                labelLength += extra
            }
            if (hasChildren) {
                mass = MassCodec.decode(u8() shl 8 or u8())
                maxFrequency = varLong()
                childBlock = record - varInt(record)
            }
            formsStart = next
            terminalMass = 0.0
            if (terminal) {
                repeat(varInt(Int.MAX_VALUE)) {
                    terminalMass += varLong().toDouble()
                    skip(varInt(Int.MAX_VALUE))
                }
                if (!hasChildren) mass = terminalMass
            }
        }

        fun labelIndex(j: Int): Int =
            if (j == 0) firstIndex else checkIndex(byteAt(labelStart + j - 1))

        fun label(): String = buildString {
            for (j in 0 until labelLength) append(LexiconAlphabet.CHARS[labelIndex(j)])
        }

        private fun checkIndex(index: Int): Int {
            if (index >= LexiconAlphabet.SIZE) throw corrupt("caractère d'index $index hors de l'alphabet")
            return index
        }

        private fun u8(): Int = byteAt(next++)

        private fun skip(length: Int) {
            if (length > dataLength - next) throw corrupt("données tronquées à la position $next")
            next += length
        }

        private fun utf8(length: Int): String {
            val start = next
            skip(length)
            return String(ByteArray(length) { buffer.get(dataStart + start + it) }, Charsets.UTF_8)
        }

        /** LEB128 non signé, 9 octets au plus : le résultat est toujours positif ou nul. */
        private fun varLong(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = u8()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift == MAX_VARINT_SHIFT) throw corrupt("entier variable trop long à la position $next")
            }
        }

        private fun varInt(max: Int): Int {
            val value = varLong()
            if (value > max) throw corrupt("valeur $value hors limites (maximum $max) à la position $next")
            return value.toInt()
        }
    }

    private fun byteAt(position: Int): Int {
        // Les positions ne sont jamais négatives : seules les distances vers les blocs enfants
        // reculent, et elles sont bornées par la position de l'enregistrement.
        if (position >= dataLength) throw corrupt("position $position hors de la zone de données")
        return buffer.get(dataStart + position).toInt() and 0xFF
    }

    companion object {

        /** Longueur maximale d'une clé : `TextNormalizer` double au plus la longueur (`œ` → `oe`). */
        const val MAX_KEY_LENGTH: Int = 2 * TextNormalizer.MAX_INPUT_LENGTH

        private const val UNKNOWN = -1
        private const val ROOT = 0
        private const val MAX_VARINT_SHIFT = 7 * LexiconFormat.MAX_VARINT_BYTES

        /**
         * Fréquence décroissante, puis clé et forme croissantes. La clé d'un nœud est un préfixe
         * de toutes celles de son sous-arbre : à fréquence égale, il passe avant toute forme de
         * clé plus grande que les siennes, et l'ordre des résultats est bien celui annoncé.
         */
        private val CANDIDATE_ORDER = Comparator<Candidate> { a, b ->
            var order = b.frequency.compareTo(a.frequency)
            if (order == 0) order = a.key.compareTo(b.key)
            if (order == 0) order = a.form.compareTo(b.form)
            order
        }

        /**
         * Charge un lexique depuis [buffer], entre sa position et sa limite, sans copier les
         * données. Le tampon n'est pas modifié (ni position ni limite) et ne doit plus l'être.
         * Seul l'en-tête est vérifié ici ; les nœuds le sont à chaque lecture.
         *
         * @throws LexiconFormatException si l'en-tête est invalide ou si la taille du tampon
         *   ne correspond pas exactement à celle annoncée.
         */
        fun load(buffer: ByteBuffer): Lexicon {
            val view = buffer.duplicate()
            val start = view.position()
            val size = view.remaining()
            val header = HeaderReader(view, start, size)
            for (expected in LexiconFormat.MAGIC) {
                if (header.u8() != expected.toInt()) throw corrupt("magic invalide : ce n'est pas un lexique Nextglyph")
            }
            val version = header.u8() shl 8 or header.u8()
            if (version != LexiconFormat.VERSION) {
                throw corrupt("version $version non prise en charge (attendue : ${LexiconFormat.VERSION})")
            }
            val tag = String(ByteArray(header.u8()) { header.u8().toByte() }, Charsets.US_ASCII)
            if (!LexiconFormat.isValidTag(tag)) throw corrupt("étiquette de langue invalide : « $tag »")
            val keyCount = header.i32()
            val formCount = header.i32()
            val rootOffset = header.i32()
            val dataLength = header.i32()
            if (keyCount < 0 || formCount < keyCount) throw corrupt("compteurs invalides ($keyCount clés, $formCount formes)")
            if (rootOffset !in 0 until dataLength) throw corrupt("bloc racine $rootOffset hors de la zone de données ($dataLength octets)")
            if (size - header.position != dataLength) {
                throw corrupt("taille incohérente : ${size - header.position} octets de données au lieu de $dataLength")
            }
            return Lexicon(view, start, start + header.position, dataLength, rootOffset, tag, keyCount, formCount)
        }

        private fun corrupt(message: String) = LexiconFormatException("Lexique invalide : $message.")
    }

    private class HeaderReader(private val buffer: ByteBuffer, private val start: Int, private val size: Int) {
        var position = 0

        fun u8(): Int {
            if (position >= size) throw corrupt("en-tête tronqué à l'octet $position")
            return buffer.get(start + position++).toInt() and 0xFF
        }

        fun i32(): Int = u8() shl 24 or (u8() shl 16) or (u8() shl 8) or u8()
    }
}
