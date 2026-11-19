package nextglyph.engine.input

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TextEditTest {

    private class RecordingEditor : EditTarget {
        val calls = mutableListOf<String>()
        override fun deleteBefore(length: Int) {
            calls += "delete $length"
        }

        override fun insert(text: String) {
            calls += "insert $text"
        }
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `applique les opérations dans l'ordre à un éditeur`() {
            val editor = RecordingEditor()
            val edit = TextEdit(listOf(EditOperation.DeleteBeforeCursor(2), EditOperation.InsertText("ur ")), endsWithAutoSpace = true)

            edit.applyTo(editor)

            assertThat(editor.calls).containsExactly("delete 2", "insert ur ")
            assertThat(edit.endsWithAutoSpace).isTrue()
            assertThat(edit.isEmpty).isFalse()
        }

        @Test
        fun `applique les opérations à un texte`() {
            val edit = TextEdit(listOf(EditOperation.DeleteBeforeCursor(2), EditOperation.InsertText("ur ")))

            assertThat(edit.applyTo("bonjoir")).isEqualTo("bonjour ")
        }

        @Test
        fun `deux modifications se composent, l'espace automatique est celle de la seconde`() {
            val word = TextEdit(listOf(EditOperation.InsertText("our ")), endsWithAutoSpace = true)
            val sign = TextEdit(listOf(EditOperation.DeleteBeforeCursor(1), EditOperation.InsertText(", ")), endsWithAutoSpace = true)

            val both = word + sign
            val reverse = sign + TextEdit(listOf(EditOperation.InsertText("x")))

            assertThat(both.applyTo("bonj")).isEqualTo("bonjour, ")
            assertThat(both.endsWithAutoSpace).isTrue()
            assertThat(reverse.endsWithAutoSpace).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette un effacement nul ou négatif et une insertion vide`() {
            assertThatThrownBy { EditOperation.DeleteBeforeCursor(0) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("strictement positif")
            assertThatThrownBy { EditOperation.DeleteBeforeCursor(-1) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EditOperation.InsertText("") }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("vide")
        }

        @Test
        fun `rejette un effacement plus long que le texte`() {
            assertThatThrownBy { TextEdit(listOf(EditOperation.DeleteBeforeCursor(4))).applyTo("abc") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("4")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `modification vide`() {
            assertThat(TextEdit.NONE.isEmpty).isTrue()
            assertThat(TextEdit.NONE.applyTo("abc")).isEqualTo("abc")
            assertThat(TextEdit.NONE.endsWithAutoSpace).isFalse()
        }

        @Test
        fun `effacer exactement tout le texte, un seul caractère`() {
            assertThat(TextEdit(listOf(EditOperation.DeleteBeforeCursor(3))).applyTo("abc")).isEmpty()
            assertThat(TextEdit(listOf(EditOperation.DeleteBeforeCursor(1), EditOperation.InsertText("é"))).applyTo("e")).isEqualTo("é")
        }
    }
}
