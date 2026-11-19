package nextglyph.engine.learn

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ByteCodecReadTest {

    /** Flux qui ne rend que [chunk] octets par lecture, comme un fichier lu par morceaux. */
    private class Trickle(private val bytes: ByteArray, private val chunk: Int) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(len, chunk, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `lit tout un flux plus court que la borne`() {
            assertThat(ByteCodec.readAtMost(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 10)).containsExactly(1, 2, 3)
        }

        @Test
        fun `lit un flux rendu par morceaux`() {
            val bytes = ByteArray(20_000) { it.toByte() }

            assertThat(ByteCodec.readAtMost(Trickle(bytes, 7), 30_000)).isEqualTo(bytes)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une borne négative est refusée`() {
            assertThatThrownBy { ByteCodec.readAtMost(ByteArrayInputStream(ByteArray(0)), -1) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("négatif")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `s'arrête exactement à la borne`() {
            val bytes = ByteArray(20_000) { 5 }

            assertThat(ByteCodec.readAtMost(Trickle(bytes, 9000), 10_001)).hasSize(10_001)
            assertThat(ByteCodec.readAtMost(ByteArrayInputStream(bytes), 0)).isEmpty()
        }

        @Test
        fun `une lecture de zéro octet n'arrête pas la lecture`() {
            val stream = object : InputStream() {
                private var calls = 0
                override fun read(): Int = -1
                override fun read(b: ByteArray, off: Int, len: Int): Int = when (calls++) {
                    0 -> 0
                    1 -> { b[off] = 7; 1 }
                    else -> -1
                }
            }

            assertThat(ByteCodec.readAtMost(stream, 5)).containsExactly(7)
        }

        @Test
        fun `flux vide`() {
            assertThat(ByteCodec.readAtMost(ByteArrayInputStream(ByteArray(0)), 5)).isEmpty()
        }
    }
}
