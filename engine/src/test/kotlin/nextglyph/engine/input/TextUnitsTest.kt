package nextglyph.engine.input

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TextUnitsTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `un caractère ordinaire compte pour 1`() {
            assertThat(TextUnits.lastCodePointLength("abc")).isEqualTo(1)
        }

        @Test
        fun `un émoji hors plan de base compte pour 2`() {
            assertThat(TextUnits.lastCodePointLength("a😀")).isEqualTo(2)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un substitut bas orphelin compte pour 1`() {
            assertThat(TextUnits.lastCodePointLength("a\uDE00")).isEqualTo(1)
            assertThat(TextUnits.lastCodePointLength("\uDE00\uDE00")).isEqualTo(1)
        }

        @Test
        fun `un substitut haut final compte pour 1`() {
            assertThat(TextUnits.lastCodePointLength("a\uD83D")).isEqualTo(1)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `texte vide`() {
            assertThat(TextUnits.lastCodePointLength("")).isEqualTo(0)
        }

        @Test
        fun `un seul caractère`() {
            assertThat(TextUnits.lastCodePointLength("a")).isEqualTo(1)
        }

        @Test
        fun `une paire de substituts seule`() {
            assertThat(TextUnits.lastCodePointLength("😀")).isEqualTo(2)
        }
    }
}
