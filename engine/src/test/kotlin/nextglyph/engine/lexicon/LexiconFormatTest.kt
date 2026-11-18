package nextglyph.engine.lexicon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

class LexiconFormatTest {

    private fun bytesOf(write: ByteSink.() -> Unit): List<Int> =
        ByteSink().apply(write).toByteArray().map { it.toInt() and 0xFF }

    @Nested
    inner class HappyPath {

        @Test
        fun `code une masse par puissances de deux`() {
            assertThat(MassCodec.encode(1.0)).isEqualTo(0)
            assertThat(MassCodec.encode(2.0)).isEqualTo(MassCodec.STEPS_PER_OCTAVE)
            assertThat(MassCodec.encode(1024.0)).isEqualTo(10 * MassCodec.STEPS_PER_OCTAVE)
        }

        @Test
        fun `décode une masse codée`() {
            assertThat(MassCodec.decode(0)).isEqualTo(1.0)
            assertThat(MassCodec.decode(3 * MassCodec.STEPS_PER_OCTAVE)).isEqualTo(8.0)
        }

        @Test
        fun `l'aller-retour d'une masse garde une erreur relative inférieure à 0,07 pour cent`() {
            val random = Random(1)
            repeat(10_000) {
                val mass = 1.0 + random.nextDouble() * 1e15

                val decoded = MassCodec.decode(MassCodec.encode(mass))

                assertThat(decoded / mass).isCloseTo(1.0, within(6.8e-4))
            }
        }

        @Test
        fun `écrit les entiers à taille fixe en gros-boutiste`() {
            assertThat(bytesOf { writeShort(0x1234) }).containsExactly(0x12, 0x34)
            assertThat(bytesOf { writeInt(0x01020304) }).containsExactly(0x01, 0x02, 0x03, 0x04)
        }

        @Test
        fun `écrit les entiers variables en LEB128`() {
            assertThat(bytesOf { writeVarLong(300) }).containsExactly(0xAC, 0x02)
        }

        @Test
        fun `écrit des octets bruts et compte la taille`() {
            val sink = ByteSink()

            sink.writeBytes(byteArrayOf(1, 2, 3))
            sink.writeByte(4)

            assertThat(sink.size).isEqualTo(4)
            assertThat(sink.toByteArray()).containsExactly(1, 2, 3, 4)
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["fr", "en", "en-GB", "fr-CA", "gsw", "zh-Hant-TW"])
        fun `accepte une étiquette de langue BCP 47`(tag: String) {
            assertThat(LexiconFormat.isValidTag(tag)).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["", "f", "fr_FR", "fr-", "-fr", "français", "fr--CA", "fr-CAAAAAAAA", "abcdefghi"])
        fun `refuse une étiquette de langue invalide`(tag: String) {
            assertThat(LexiconFormat.isValidTag(tag)).isFalse()
        }

        @Test
        fun `ramène une masse inférieure à 1 au code minimal`() {
            assertThat(MassCodec.encode(0.25)).isEqualTo(0)
        }

        @Test
        fun `plafonne une masse au-delà de 2 puissance 128`() {
            assertThat(MassCodec.encode(1e300)).isEqualTo(MassCodec.MAX_CODE)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `écrit 0, 127 et 128 sur un puis deux octets`() {
            assertThat(bytesOf { writeVarLong(0) }).containsExactly(0)
            assertThat(bytesOf { writeVarLong(127) }).containsExactly(0x7F)
            assertThat(bytesOf { writeVarLong(128) }).containsExactly(0x80, 0x01)
        }

        @Test
        fun `écrit Long MAX_VALUE sur exactement 9 octets`() {
            assertThat(bytesOf { writeVarLong(Long.MAX_VALUE) })
                .containsExactly(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F)
            assertThat(LexiconFormat.MAX_VARINT_BYTES).isEqualTo(9)
        }

        @Test
        fun `code la masse maximale d'un lexique sans saturer`() {
            val mass = Long.MAX_VALUE.toDouble() * Int.MAX_VALUE

            assertThat(MassCodec.encode(mass)).isLessThan(MassCodec.MAX_CODE)
            assertThat(MassCodec.decode(MassCodec.encode(mass)) / mass).isCloseTo(1.0, within(6.8e-4))
        }

        @Test
        fun `le code maximal vaut 2 puissance 128`() {
            assertThat(MassCodec.decode(MassCodec.MAX_CODE)).isCloseTo(StrictMath.pow(2.0, 128.0), within(1e36))
        }

        @Test
        fun `arrondit une masse au code le plus proche`() {
            val halfStep = StrictMath.pow(2.0, 0.6 / MassCodec.STEPS_PER_OCTAVE)

            assertThat(MassCodec.encode(halfStep)).isEqualTo(1)
        }

        @Test
        fun `accepte une étiquette de 35 caractères mais pas de 36`() {
            assertThat(LexiconFormat.isValidTag("abcdefgh-abcdefgh-abcdefgh-abcdefgh")).isTrue()
            assertThat(LexiconFormat.isValidTag("abcdefg-abcdefgh-abcdefgh-abcdefgh-a")).isFalse()
        }

        @Test
        fun `écrit un octet et un mot en ne gardant que les bits de poids faible`() {
            assertThat(bytesOf { writeByte(0x1FF) }).containsExactly(0xFF)
            assertThat(bytesOf { writeShort(0x12345) }).containsExactly(0x23, 0x45)
        }
    }
}
