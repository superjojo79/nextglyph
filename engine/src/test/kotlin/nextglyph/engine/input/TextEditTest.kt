package nextglyph.engine.input

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TextEditTest {

    private class RecordingEditor : TextEditor {
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
            val edit = TextEdit(listOf(EditOperation.DeleteBefore(2), EditOperation.Insert("ur ")), endsWithAutoSpace = true)

            edit.applyTo(editor)

            assertThat(editor.calls).containsExactly("delete 2", "insert ur ")
            assertThat(edit.endsWithAutoSpace).isTrue()
            assertThat(edit.isEmpty).isFalse()
        }

        @Test
        fun `applique les opérations à un texte`() {
            val edit = TextEdit(listOf(EditOperation.DeleteBefore(2), EditOperation.Insert("ur ")))

            assertThat(edit.applyTo("bonjoir")).isEqualTo("bonjour ")
        }

        @Test
        fun `deux modifications se composent, l'espace automatique est celle de la seconde`() {
            val word = TextEdit(listOf(EditOperation.Insert("our ")), endsWithAutoSpace = true)
            val sign = TextEdit(listOf(EditOperation.DeleteBefore(1), EditOperation.Insert(", ")), endsWithAutoSpace = true)

            val both = word + sign
            val reverse = sign + TextEdit(listOf(EditOperation.Insert("x")))

            assertThat(both.applyTo("bonj")).isEqualTo("bonjour, ")
            assertThat(both.endsWithAutoSpace).isTrue()
            assertThat(reverse.endsWithAutoSpace).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette un effacement nul ou négatif et une insertion vide`() {
            assertThatThrownBy { EditOperation.DeleteBefore(0) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("strictement positif")
            assertThatThrownBy { EditOperation.DeleteBefore(-1) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EditOperation.Insert("") }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("vide")
        }

        @Test
        fun `rejette un effacement plus long que le texte`() {
            assertThatThrownBy { TextEdit(listOf(EditOperation.DeleteBefore(4))).applyTo("abc") }
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
            assertThat(TextEdit(listOf(EditOperation.DeleteBefore(3))).applyTo("abc")).isEmpty()
            assertThat(TextEdit(listOf(EditOperation.DeleteBefore(1), EditOperation.Insert("é"))).applyTo("e")).isEqualTo("é")
        }
    }
}
