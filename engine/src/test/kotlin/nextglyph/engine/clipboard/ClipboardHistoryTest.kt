package nextglyph.engine.clipboard

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClipboardHistoryTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `les textes copiés, le plus récent d'abord`() {
            val history = ClipboardHistory()

            assertThat(history.add("un")).isTrue()
            assertThat(history.add("deux")).isTrue()

            assertThat(history.list).containsExactly("deux", "un")
        }

        @Test
        fun `un texte recopié remonte en tête sans doublon`() {
            val history = ClipboardHistory()
            history.add("un")
            history.add("deux")

            assertThat(history.add("un")).isTrue()
            assertThat(history.list).containsExactly("un", "deux")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `texte vide, blanc ou trop long - ignoré`() {
            val history = ClipboardHistory(maxLength = 5)

            assertThat(history.add("")).isFalse()
            assertThat(history.add("  \n")).isFalse()
            assertThat(history.add("123456")).isFalse()
            assertThat(history.list).isEmpty()
        }

        @Test
        fun `capacité ou longueur nulle refusée`() {
            assertThatThrownBy { ClipboardHistory(0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Capacité")
            assertThatThrownBy { ClipboardHistory(maxLength = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `au-delà de la capacité, le plus ancien est oublié`() {
            val history = ClipboardHistory(capacity = 2)
            history.add("a")
            history.add("b")
            history.add("c")

            assertThat(history.list).containsExactly("c", "b")
        }

        @Test
        fun `même texte déjà en tête - rien ne change, longueur maximale acceptée, effacement`() {
            val history = ClipboardHistory(maxLength = 5)

            assertThat(history.add("12345")).isTrue()
            assertThat(history.add("12345")).isFalse()
            assertThat(history.list).containsExactly("12345")
            history.clear()
            assertThat(history.list).isEmpty()
            assertThat(ClipboardHistory.DEFAULT_CAPACITY).isEqualTo(10)
        }
    }
}
