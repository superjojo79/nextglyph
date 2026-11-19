package nextglyph.engine.input

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EditOperationTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `une suppression et une insertion portent leurs valeurs`() {
            assertThat(EditOperation.DeleteBeforeCursor(2).count).isEqualTo(2)
            assertThat(EditOperation.InsertText(", ").text).isEqualTo(", ")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `refuse une suppression nulle ou négative`() {
            assertThatThrownBy { EditOperation.DeleteBeforeCursor(0) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("strictement positif")
            assertThatThrownBy { EditOperation.DeleteBeforeCursor(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `refuse une insertion vide`() {
            assertThatThrownBy { EditOperation.InsertText("") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("vide")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte une suppression de un caractère et une insertion d'un caractère`() {
            assertThat(EditOperation.DeleteBeforeCursor(1).count).isEqualTo(1)
            assertThat(EditOperation.InsertText(" ").text).isEqualTo(" ")
        }
    }
}
