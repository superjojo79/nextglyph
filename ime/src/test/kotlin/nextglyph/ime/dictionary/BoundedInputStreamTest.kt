package nextglyph.ime.dictionary

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

class BoundedInputStreamTest {

    private fun bytes(count: Int) = ByteArray(count) { 1 }

    @Nested
    inner class HappyPath {
        @Test
        fun `lit un flux plus court que le plafond`() {
            assertThat(BoundedInputStream.readAll(bytes(10).inputStream(), 100)).hasSize(10)
        }

        @Test
        fun `lit octet par octet jusqu'à la fin`() {
            val stream = BoundedInputStream(bytes(3).inputStream(), 3)

            assertThat(listOf(stream.read(), stream.read(), stream.read(), stream.read())).containsExactly(1, 1, 1, -1)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `refuse un flux plus long que le plafond`() {
            assertThatThrownBy { BoundedInputStream.readAll(bytes(101).inputStream(), 100) }
                .isInstanceOf(IOException::class.java)
                .hasMessageContaining("100")
        }

        @Test
        fun `refuse un octet de trop lu un par un`() {
            val stream = BoundedInputStream(bytes(2).inputStream(), 1)
            stream.read()

            assertThatThrownBy { stream.read() }.isInstanceOf(IOException::class.java)
        }

        @Test
        fun `refuse un plafond négatif`() {
            assertThatThrownBy { BoundedInputStream(bytes(1).inputStream(), -1) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `ne prend pas en charge le marquage`() {
            val stream = BoundedInputStream(bytes(1).inputStream(), 1)
            stream.mark(1)

            assertThat(stream.markSupported()).isFalse()
            assertThatThrownBy { stream.reset() }.isInstanceOf(IOException::class.java)
        }

        @Test
        fun `compte aussi les octets sautés`() {
            val stream = BoundedInputStream(bytes(10).inputStream(), 5)

            assertThatThrownBy { stream.skip(6) }.isInstanceOf(IOException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte un flux d'exactement le plafond`() {
            assertThat(BoundedInputStream.readAll(bytes(100).inputStream(), 100)).hasSize(100)
        }

        @Test
        fun `un flux vide passe avec un plafond nul`() {
            assertThat(BoundedInputStream.readAll(bytes(0).inputStream(), 0)).isEmpty()
        }

        @Test
        fun `un octet avec un plafond nul est refusé`() {
            assertThatThrownBy { BoundedInputStream.readAll(bytes(1).inputStream(), 0) }.isInstanceOf(IOException::class.java)
        }
    }
}
