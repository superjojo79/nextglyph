package nextglyph.ime

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InputConnectionEditorTest {

    /** Faux InputConnection : enregistre les appels, texte avant le curseur et sélection configurables. */
    private class FakeConnection(
        var before: CharSequence? = "",
        var selected: CharSequence? = null,
    ) : InputConnectionWrapper(null, false) {
        val calls = mutableListOf<String>()

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "commit:$text:$newCursorPosition"
            return true
        }

        override fun getSelectedText(flags: Int): CharSequence? = selected

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = before

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            calls += "delete:$beforeLength:$afterLength"
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            calls += "key"
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            calls += "action:$editorAction"
            return true
        }
    }

    private fun editorOn(connection: InputConnection?) = InputConnectionEditor { connection }

    @Nested
    inner class HappyPath {
        @Test
        fun `commitText transmet le texte avec le curseur après`() {
            val connection = FakeConnection()

            editorOn(connection).commitText("é")

            assertThat(connection.calls).containsExactly("commit:é:1")
        }

        @Test
        fun `deleteBackward efface le caractère avant le curseur`() {
            val connection = FakeConnection(before = "ab")

            editorOn(connection).deleteBackward()

            assertThat(connection.calls).containsExactly("delete:1:0")
        }

        @Test
        fun `deleteBackward efface un émoji complet`() {
            val connection = FakeConnection(before = "a😀")

            editorOn(connection).deleteBackward()

            assertThat(connection.calls).containsExactly("delete:2:0")
        }

        @Test
        fun `deleteBackward efface la sélection`() {
            val connection = FakeConnection(before = "ab", selected = "ab")

            editorOn(connection).deleteBackward()

            assertThat(connection.calls).containsExactly("commit::1")
        }

        @Test
        fun `l'action d'éditeur est transmise`() {
            val connection = FakeConnection()

            editorOn(connection).performEditorAction(3)

            assertThat(connection.calls).containsExactly("action:3")
        }

        @Test
        fun `la touche Entrée envoie un appui et un relâchement`() {
            val connection = FakeConnection()

            editorOn(connection).sendEnterKey()

            assertThat(connection.calls).containsExactly("key", "key")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `sans connexion, aucune opération ne lève d'exception`() {
            val editor = editorOn(null)

            editor.commitText("a")
            editor.deleteBackward()
            editor.performEditorAction(1)
            editor.sendEnterKey()
        }

        @Test
        fun `un champ qui ne rend pas son texte reçoit la touche Retour arrière`() {
            val connection = FakeConnection(before = null)

            editorOn(connection).deleteBackward()

            assertThat(connection.calls).containsExactly("key", "key")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une sélection vide n'est pas une sélection`() {
            val connection = FakeConnection(before = "a", selected = "")

            editorOn(connection).deleteBackward()

            assertThat(connection.calls).containsExactly("delete:1:0")
        }

        @Test
        fun `au début d'un champ vide, repli sur la touche Retour arrière`() {
            val connection = FakeConnection(before = "")

            editorOn(connection).deleteBackward()

            assertThat(connection.calls).containsExactly("key", "key")
        }

        @Test
        fun `la connexion est relue à chaque appel`() {
            val first = FakeConnection()
            val second = FakeConnection()
            var current: InputConnection = first
            val editor = InputConnectionEditor { current }

            editor.commitText("a")
            current = second
            editor.commitText("b")

            assertThat(first.calls).containsExactly("commit:a:1")
            assertThat(second.calls).containsExactly("commit:b:1")
        }
    }
}
