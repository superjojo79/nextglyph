package nextglyph.ime.dictionary

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Flux limité à [limit] octets : lire un octet de plus lève une [IOException] au lieu de laisser un serveur (ou un
 * intermédiaire) remplir le stockage. Un flux d'exactement [limit] octets est accepté.
 */
open class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {

    init {
        require(limit >= 0) { "Plafond négatif : $limit." }
    }

    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) add(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) add(read.toLong())
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) add(skipped)
        return skipped
    }

    override fun markSupported(): Boolean = false

    override fun mark(readlimit: Int) = Unit

    override fun reset() {
        throw IOException("Marquage non pris en charge.")
    }

    private fun add(bytes: Long) {
        count += bytes
        if (count > limit) throw IOException("Réponse trop volumineuse : plus de $limit octets.")
    }

    companion object {
        /** Lit [input] en entier, au plus [limit] octets ; [IOException] au-delà. */
        fun readAll(input: InputStream, limit: Long): ByteArray =
            BoundedInputStream(input, limit).use { it.readBytes() }
    }
}
