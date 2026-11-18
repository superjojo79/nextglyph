package nextglyph.engine.lexicon

import nextglyph.engine.text.TextNormalizer
import java.nio.ByteBuffer

/** Traitement d'une forme qui ne peut pas être indexée (voir [LexiconBuilder]). */
enum class InvalidFormPolicy {
    /** Lever une `IllegalArgumentException` citant la forme fautive. Choix par défaut. */
    REJECT,

    /** Ignorer la forme et la consigner dans [LexiconBuilder.skippedForms]. */
    SKIP,
}

/**
 * Construit un [Lexicon] en mémoire à partir de paires `(forme d'affichage, fréquence)`.
 *
 * La clé d'une forme est `TextNormalizer.normalize(forme)`. Plusieurs formes peuvent
 * partager une clé (`a`/`à`, `ou`/`où`) : toutes sont conservées avec leur fréquence. Les
 * doublons exacts d'une forme s'additionnent, avec saturation à `Long.MAX_VALUE`.
 *
 * **Formes invalides.** Une forme est invalide si `TextNormalizer` la refuse ou si sa clé
 * contient un caractère hors de [LexiconAlphabet] (`3D`, `Straße`). Le traitement est fixé
 * une fois pour toutes à la construction par [invalidFormPolicy] plutôt que par un drapeau
 * passé à chaque [add] : une même construction applique ainsi une règle uniforme, et le
 * choix permissif est explicite dans le code appelant. Par défaut ([InvalidFormPolicy.REJECT]),
 * la première forme invalide lève une `IllegalArgumentException`, ce qui convient aux tests
 * et aux listes curées. Les outils qui importent de gros dictionnaires choisissent
 * [InvalidFormPolicy.SKIP] et publient [skippedForms] : rien n'est perdu en silence.
 *
 * Une fréquence nulle ou négative est toujours refusée : c'est une erreur du programme
 * appelant, pas une particularité du vocabulaire.
 *
 * @param languageTag étiquette de langue BCP 47 enregistrée dans le fichier (`fr`, `en-GB`).
 */
class LexiconBuilder(
    val languageTag: String,
    private val invalidFormPolicy: InvalidFormPolicy = InvalidFormPolicy.REJECT,
) {

    init {
        require(LexiconFormat.isValidTag(languageTag)) {
            "Étiquette de langue invalide : « $languageTag »."
        }
    }

    private val formsByKey = HashMap<String, HashMap<String, Long>>()
    private val skipped = ArrayList<String>()

    /** Formes ignorées en mode [InvalidFormPolicy.SKIP], dans l'ordre d'ajout. */
    val skippedForms: List<String> get() = skipped.toList()

    /**
     * Ajoute [frequency] occurrences de [form].
     *
     * @throws IllegalArgumentException si [frequency] ≤ 0, ou si [form] est invalide en
     *   mode [InvalidFormPolicy.REJECT].
     */
    fun add(form: String, frequency: Long): LexiconBuilder {
        require(frequency > 0) { "La fréquence de « $form » doit être strictement positive ($frequency)." }
        val key = keyOf(form)
        if (key == null) {
            skipped += form
        } else {
            val forms = formsByKey.getOrPut(key) { HashMap() }
            forms[form] = saturatingAdd(forms[form] ?: 0L, frequency)
        }
        return this
    }

    /** Construit le lexique ; équivaut à `Lexicon.load(ByteBuffer.wrap(toByteArray()))`. */
    fun build(): Lexicon = Lexicon.load(ByteBuffer.wrap(toByteArray()))

    /** Sérialise le lexique au format binaire décrit dans [LexiconFormat]. */
    fun toByteArray(): ByteArray {
        val keys = formsByKey.keys.sorted()
        val data = ByteSink()
        val root = writeBlock(data, keys, 0, keys.size, 0)
        val tag = languageTag.toByteArray(Charsets.US_ASCII)
        val file = ByteSink()
        file.writeBytes(LexiconFormat.MAGIC)
        file.writeShort(LexiconFormat.VERSION)
        file.writeByte(tag.size)
        file.writeBytes(tag)
        file.writeInt(keys.size)
        file.writeInt(formsByKey.values.sumOf { it.size })
        file.writeInt(root.position)
        file.writeInt(data.size)
        file.writeBytes(data.toByteArray())
        return file.toByteArray()
    }

    private fun keyOf(form: String): String? {
        val key = try {
            TextNormalizer.normalize(form)
        } catch (e: IllegalArgumentException) {
            return invalid(form, e.message)
        }
        if (!LexiconAlphabet.isValidKey(key)) {
            return invalid(form, "la clé « $key » sort de l'alphabet ${LexiconAlphabet.CHARS}")
        }
        return key
    }

    private fun invalid(form: String, reason: String?): String? {
        require(invalidFormPolicy == InvalidFormPolicy.SKIP) { "Forme « $form » refusée : $reason" }
        return null
    }

    /** Bloc écrit : sa position, la masse et la fréquence maximale de ses sous-arbres. */
    private class Block(val position: Int, val mass: Double, val maxFrequency: Long)

    /** Enfant en attente d'écriture : étiquette, formes (si terminal), bloc d'enfants. */
    private class Child(val key: String, val label: String, val forms: List<Map.Entry<String, Long>>?, val block: Block?) {
        val mass: Double = (forms?.sumOf { it.value.toDouble() } ?: 0.0) + (block?.mass ?: 0.0)
        // Les formes sont triées par fréquence décroissante : la première est la plus fréquente.
        val maxFrequency: Long = maxOf(forms?.let { it[0].value } ?: 0L, block?.maxFrequency ?: 0L)
    }

    /**
     * Écrit le bloc des enfants communs aux clés `keys[from until to]`, qui partagent
     * leurs [depth] premiers caractères et sont toutes plus longues que [depth].
     * Les blocs des petits-enfants sont écrits d'abord (ordre postfixe).
     */
    private fun writeBlock(sink: ByteSink, keys: List<String>, from: Int, to: Int, depth: Int): Block {
        val children = ArrayList<Child>()
        var start = from
        while (start < to) {
            val first = keys[start]
            var end = start + 1
            while (end < to && keys[end][depth] == first[depth]) end++
            // Les clés sont triées : le préfixe commun du groupe est celui de la première et de
            // la dernière, et une clé préfixe d'une autre la précède.
            val last = keys[end - 1]
            var labelEnd = depth + 1
            while (labelEnd < first.length && first[labelEnd] == last[labelEnd]) labelEnd++
            val terminal = first.length == labelEnd
            val childrenFrom = if (terminal) start + 1 else start
            children += Child(
                key = first.substring(0, labelEnd),
                label = first.substring(depth, labelEnd),
                forms = if (terminal) sortedForms(first) else null,
                block = if (childrenFrom < end) writeBlock(sink, keys, childrenFrom, end, labelEnd) else null,
            )
            start = end
        }
        val position = sink.size
        sink.writeByte(children.size)
        children.forEach { writeChild(sink, it) }
        return Block(position, children.sumOf { it.mass }, children.fold(0L) { max, child -> maxOf(max, child.maxFrequency) })
    }

    private fun sortedForms(key: String): List<Map.Entry<String, Long>> =
        formsByKey.getValue(key).entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })

    private fun writeChild(sink: ByteSink, child: Child) {
        val position = sink.size
        val indexes = child.label.map(LexiconAlphabet::indexOf)
        var header = indexes[0]
        if (child.block != null) header = header or LexiconFormat.HAS_CHILDREN
        if (child.forms != null) header = header or LexiconFormat.TERMINAL
        if (indexes.size > 1) header = header or LexiconFormat.LONG_LABEL
        sink.writeByte(header)
        if (indexes.size > 1) {
            sink.writeVarLong(indexes.size - 1L)
            indexes.drop(1).forEach(sink::writeByte)
        }
        if (child.block != null) {
            sink.writeShort(MassCodec.encode(child.mass))
            sink.writeVarLong(child.maxFrequency)
            sink.writeVarLong((position - child.block.position).toLong())
        }
        if (child.forms != null) {
            sink.writeVarLong(child.forms.size.toLong())
            for ((form, frequency) in child.forms) {
                sink.writeVarLong(frequency)
                val bytes = if (form == child.key) ByteArray(0) else form.toByteArray(Charsets.UTF_8)
                sink.writeVarLong(bytes.size.toLong())
                sink.writeBytes(bytes)
            }
        }
    }

    private companion object {
        fun saturatingAdd(a: Long, b: Long): Long = try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}
