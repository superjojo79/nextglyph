package nextglyph.engine.learn

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ByteCodecTest {

    private fun reader(vararg bytes: Int) = ByteReader(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun rejects(message: String, block: () -> Any?) {
        assertThatThrownBy { block() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
    }

    @Nested
    inner class HappyPath {

        @ParameterizedTest
        @ValueSource(ints = [0, 1, 127, 128, 255, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Int.MAX_VALUE])
        fun `les entiers variables font l'aller-retour`(value: Int) {
            val bytes = ByteWriter().apply { varInt(value) }.toByteArray()

            assertThat(ByteReader(bytes).varInt(Int.MAX_VALUE)).isEqualTo(value)
        }

        @Test
        fun `un entier variable occupe un octet par groupe de sept bits`() {
            assertThat(ByteWriter().apply { varInt(127) }.toByteArray()).containsExactly(0x7F)
            assertThat(ByteWriter().apply { varInt(128) }.toByteArray()).containsExactly(0x80, 0x01)
            assertThat(ByteWriter().apply { varInt(300) }.toByteArray()).containsExactly(0xAC, 0x02)
        }

        @Test
        fun `les chaînes, nombres et octets font l'aller-retour`() {
            val bytes = ByteWriter().apply {
                string("Héléne œuvre ✓ 😀")
                string("")
                double(-0.125)
                double(Double.MIN_VALUE)
                u8(255)
                u16(0xABCD)
                u32(0xFFFFFFFEL)
                bytes(byteArrayOf(1, 2, 3))
            }.toByteArray()
            val r = ByteReader(bytes)

            assertThat(r.string(100)).isEqualTo("Héléne œuvre ✓ 😀")
            assertThat(r.string(0)).isEmpty()
            assertThat(r.double()).isEqualTo(-0.125)
            assertThat(r.double()).isEqualTo(Double.MIN_VALUE)
            assertThat(r.u8()).isEqualTo(255)
            assertThat(r.u16()).isEqualTo(0xABCD)
            assertThat(r.u32()).isEqualTo(0xFFFFFFFEL)
            assertThat(r.bytes(3)).containsExactly(1, 2, 3)
            assertThat(r.remaining).isZero()
            r.requireEnd()
        }

        @Test
        fun `le lecteur suit sa position et la taille de l'écrivain`() {
            val writer = ByteWriter().apply { u16(1) }
            val r = ByteReader(byteArrayOf(9, 8, 7), 1)

            assertThat(writer.size).isEqualTo(2)
            assertThat(r.position).isEqualTo(1)
            r.u8()
            assertThat(r.position).isEqualTo(2)
            assertThat(r.remaining).isEqualTo(1)
        }

        @Test
        fun `un lecteur borné ne lit pas au-delà de sa fin`() {
            val r = ByteReader(byteArrayOf(1, 2, 3), 0, 2)

            r.u8()
            r.u8()

            rejects("tronquées") { r.u8() }
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `lire au-delà des données lève une exception`() {
            rejects("tronquées") { reader().u8() }
            rejects("tronquées") { reader(1).u16() }
            rejects("tronquées") { reader(1, 2, 3).u32() }
            rejects("tronquées") { reader(1, 2, 3).double() }
            rejects("tronquées") { reader(1, 2).bytes(3) }
            rejects("tronquées") { reader(0x80).varInt(10) }
        }

        @Test
        fun `un entier variable trop grand ou trop long est refusé`() {
            rejects("limites") { reader(0x0B).varInt(10) }
            rejects("trop long") { reader(0x80, 0x80, 0x80, 0x80, 0x80, 0x00).varInt(Int.MAX_VALUE) }
            rejects("limites") { reader(0xFF, 0xFF, 0xFF, 0xFF, 0x7F).varInt(Int.MAX_VALUE) }
        }

        @Test
        fun `une chaîne trop longue, tronquée ou en UTF-8 invalide est refusée`() {
            rejects("limites") { reader(3, 'a'.code, 'b'.code, 'c'.code).string(2) }
            rejects("tronquée") { reader(3, 'a'.code).string(10) }
            rejects("UTF-8") { reader(1, 0xFF).string(10) }
            rejects("UTF-8") { reader(2, 0xC3, 0x28).string(10) }
        }

        @Test
        fun `un octet en trop est signalé`() {
            rejects("en trop") { reader(1).requireEnd() }
        }

        @Test
        fun `un entier négatif ne s'écrit pas`() {
            rejects("négatif") { ByteWriter().varInt(-1) }
            assertThatThrownBy { ByteReader(byteArrayOf(1, 2), 0).bytes(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une chaîne de exactement la longueur maximale est lue`() {
            val bytes = ByteWriter().apply { string("abc") }.toByteArray()

            assertThat(ByteReader(bytes).string(3)).isEqualTo("abc")
            rejects("limites") { ByteReader(bytes).string(2) }
        }

        @Test
        fun `un entier variable égal au maximum est lu, au maximum plus un il est refusé`() {
            assertThat(reader(10).varInt(10)).isEqualTo(10)
            rejects("limites") { reader(11).varInt(10) }
        }

        @Test
        fun `un entier variable de cinq octets est lu`() {
            assertThat(reader(0xFF, 0xFF, 0xFF, 0xFF, 0x07).varInt(Int.MAX_VALUE)).isEqualTo(Int.MAX_VALUE)
        }

        @Test
        fun `lire exactement tous les octets restants est permis`() {
            assertThat(reader(1, 2).bytes(2)).containsExactly(1, 2)
            assertThat(reader().bytes(0)).isEmpty()
        }

        @Test
        fun `les entiers de 32 bits aux extrémités`() {
            val bytes = ByteWriter().apply {
                u32(0)
                u32(0xFFFFFFFFL)
                u16(0)
                u16(0xFFFF)
            }.toByteArray()
            val r = ByteReader(bytes)

            assertThat(r.u32()).isZero()
            assertThat(r.u32()).isEqualTo(0xFFFFFFFFL)
            assertThat(r.u16()).isZero()
            assertThat(r.u16()).isEqualTo(0xFFFF)
        }
    }
}
