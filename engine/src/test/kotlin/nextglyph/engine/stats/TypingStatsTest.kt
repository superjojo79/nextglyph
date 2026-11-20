package nextglyph.engine.stats

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TypingStatsTest {

    private var now = 0L
    private fun stats(counters: TypingStats.Counters = TypingStats.Counters()) = TypingStats({ now }, counters)

    @Nested
    inner class HappyPath {
        @Test
        fun `vitesse - 10 caractères en 6 secondes, 20 mots par minute`() {
            val s = stats()
            repeat(11) {
                s.onKey()
                now += 600
            }

            assertThat(s.counters.chars).isEqualTo(11)
            assertThat(s.counters.activeMs).isEqualTo(6000)
            assertThat(s.counters.wordsPerMinute!!).isCloseTo(22.0, Offset.offset(1e-9))
        }

        @Test
        fun `taux de correction, d'acceptation et d'annulation`() {
            val s = stats()
            repeat(8) { s.onKey() }
            s.onBackspace()
            s.onBackspace()
            s.onWord(autocommit = true)
            s.onWord(autocommit = true)
            s.onUndo()
            s.onWord(autocommit = false)
            s.onPick()

            val c = s.counters
            assertThat(c.correctionRate!!).isCloseTo(0.2, Offset.offset(1e-9))
            assertThat(c.acceptanceRate!!).isCloseTo(0.5, Offset.offset(1e-9))
            assertThat(c.undoRate!!).isCloseTo(0.5, Offset.offset(1e-9))
            assertThat(s.changed).isTrue()
        }

        @Test
        fun `enregistrement puis relecture identiques`() {
            val c = TypingStats.Counters(1, 2, 3, 4, 5, 6, 7, 8)

            assertThat(c.serialize()).isEqualTo("v1;1;2;3;4;5;6;7;8")
            assertThat(TypingStats.Counters.parse(c.serialize())).isEqualTo(c)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `forme enregistrée absente, abîmée ou d'une autre version - compteurs nuls`() {
            for (stored in listOf(null, "", "v2;1;2;3;4;5;6;7;8", "v1;1;2", "v1;1;2;3;4;5;6;7;x", "v1;1;2;3;4;5;6;7;-1")) {
                assertThat(TypingStats.Counters.parse(stored)).isEqualTo(TypingStats.Counters())
            }
        }

        @Test
        fun `compteur négatif refusé`() {
            assertThatThrownBy { TypingStats.Counters(chars = -1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("négatif")
        }

        @Test
        fun `sans données - pas de taux`() {
            val c = TypingStats.Counters()

            assertThat(c.wordsPerMinute).isNull()
            assertThat(c.correctionRate).isNull()
            assertThat(c.acceptanceRate).isNull()
            assertThat(c.undoRate).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `pause de plus de 5 s non comptée, pause de 5 s comptée`() {
            val s = stats()
            s.onKey()
            now += 5000
            s.onKey()
            now += 5001
            s.onKey()

            assertThat(s.counters.activeMs).isEqualTo(5000)
        }

        @Test
        fun `remise à zéro et enregistrement marqué`() {
            val s = stats(TypingStats.Counters(chars = 3, keys = 3))
            assertThat(s.changed).isFalse()

            s.onKey()
            s.markSaved()
            assertThat(s.changed).isFalse()
            now += 100
            s.reset()

            assertThat(s.counters).isEqualTo(TypingStats.Counters())
            assertThat(s.changed).isTrue()
            s.onKey()
            assertThat(s.counters.activeMs).isZero()
        }

        @Test
        fun `plus d'annulations que de validations - acceptation jamais négative`() {
            val c = TypingStats.Counters(words = 2, autocommits = 1, undone = 3)

            assertThat(c.acceptanceRate).isEqualTo(0.0)
            assertThat(TypingStats.PAUSE_MS).isEqualTo(5000)
        }
    }
}
