package nextglyph.engine.learn

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Écriture d'entiers variables (LEB128), de chaînes UTF-8 et de nombres, en mémoire. */
internal class ByteWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u8(value: Int) = out.write(value)

    fun u16(value: Int) {
        u8(value ushr 8)
        u8(value)
    }

    fun u32(value: Long) {
        u16((value ushr 16).toInt() and 0xFFFF)
        u16(value.toInt() and 0xFFFF)
    }

    /** Entier positif ou nul, 7 bits par octet, poids faible d'abord. */
    fun varInt(value: Int) {
        require(value >= 0) { "Entier négatif : $value." }
        var rest = value
        while (rest >= 0x80) {
            u8(rest and 0x7F or 0x80)
            rest = rest ushr 7
        }
        u8(rest)
    }

    fun string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        varInt(bytes.size)
        out.write(bytes)
    }

    fun double(value: Double) {
        val bits = value.toRawBits()
        u32(bits ushr 32)
        u32(bits and 0xFFFFFFFFL)
    }

    fun bytes(value: ByteArray) = out.write(value)

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Lecture bornée : toute lecture hors des données ou hors limites lève une `IllegalArgumentException`
 * (jamais d'autre exception, jamais d'allocation proportionnelle à une valeur lue sans borne).
 */
internal class ByteReader(private val data: ByteArray, start: Int = 0, private val end: Int = data.size) {
    var position: Int = start
        private set

    val remaining: Int get() = end - position

    fun u8(): Int {
        require(position < end) { "Données tronquées à l'octet $position." }
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int = u8() shl 8 or u8()

    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

    fun varInt(max: Int): Int {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = u8()
            result = result or ((byte and 0x7F).toLong() shl shift)
            require(result <= max) { "Valeur hors limites (maximum $max)." }
            if (byte and 0x80 == 0) return result.toInt()
            shift += 7
            require(shift <= MAX_SHIFT) { "Entier variable trop long." }
        }
    }

    fun string(maxBytes: Int): String {
        val length = varInt(maxBytes)
        require(length <= remaining) { "Chaîne tronquée à l'octet $position." }
        val buffer = ByteBuffer.wrap(data, position, length)
        position += length
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(buffer).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Chaîne UTF-8 invalide avant l'octet $position.", e)
        }
    }

    fun double(): Double = Double.fromBits((u32() shl 32) or u32())

    fun bytes(count: Int): ByteArray {
        require(count in 0..remaining) { "Données tronquées à l'octet $position." }
        val result = data.copyOfRange(position, position + count)
        position += count
        return result
    }

    fun requireEnd() = require(remaining == 0) { "$remaining octets en trop après la fin de la section." }

    private companion object {
        const val MAX_SHIFT = 28
    }
}
