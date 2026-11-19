package nextglyph.ime

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InputConnectionTargetTest {

    /** Faux InputConnection : enregistre les appels, texte avant et après le curseur configurables. */
    private class FakeConnection(
        var before: CharSequence? = "",
        var after: CharSequence? = "",
    ) : InputConnectionWrapper(null, false) {
        val calls = mutableListOf<String>()

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "commit:$text:$newCursorPosition"
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "compose:$text:$newCursorPosition"
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            calls += "region:$start:$end"
            return true
        }

        override fun finishComposingText(): Boolean {
            calls += "finish"
            return true
        }

        override fun beginBatchEdit(): Boolean {
            calls += "begin"
            return true
        }

        override fun endBatchEdit(): Boolean {
            calls += "end"
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = before?.let { it.subSequence(maxOf(0, it.length - n), it.length) }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = after?.let { it.subSequence(0, minOf(n, it.length)) }

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

    private fun on(connection: InputConnection?) = InputConnectionTarget { connection }

    @Nested
    inner class HappyPath {
        @Test
        fun `chaque opération est traduite en appel InputConnection, curseur après le texte`() {
            val connection = FakeConnection()
            val target = on(connection)

            target.beginBatchEdit()
            target.setComposingText("bon")
            target.setComposingRegion(2, 5)
            target.finishComposingText()
            target.commitText("é")
            target.deleteBefore(2)
            target.endBatchEdit()
            target.performEditorAction(3)

            assertThat(connection.calls).containsExactly(
                "begin", "compose:bon:1", "region:2:5", "finish", "commit:é:1", "delete:2:0", "end", "action:3",
            )
        }

        @Test
        fun `texte avant et après le curseur relu au champ`() {
            val target = on(FakeConnection(before = "Bonjour", after = " le"))

            assertThat(target.textBeforeCursor(3).toString()).isEqualTo("our")
            assertThat(target.textAfterCursor(1).toString()).isEqualTo(" ")
        }

        @Test
        fun `les touches Entrée et Retour arrière envoient un appui et un relâchement`() {
            val connection = FakeConnection()

            on(connection).sendEnterKey()
            on(connection).sendBackspaceKey()

            assertThat(connection.calls).containsExactly("key", "key", "key", "key")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `sans connexion, aucune opération ne lève d'exception et rien n'est lu`() {
            val target = on(null)

            target.beginBatchEdit()
            target.setComposingText("a")
            target.setComposingRegion(0, 1)
            target.finishComposingText()
            target.commitText("a")
            target.deleteBefore(1)
            target.sendBackspaceKey()
            target.performEditorAction(1)
            target.sendEnterKey()
            target.endBatchEdit()

            assertThat(target.textBeforeCursor(5)).isNull()
            assertThat(target.textAfterCursor(5)).isNull()
        }

        @Test
        fun `un champ qui ne rend pas son texte donne null`() {
            val target = on(FakeConnection(before = null, after = null))

            assertThat(target.textBeforeCursor(2)).isNull()
            assertThat(target.textAfterCursor(2)).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `la connexion est relue à chaque appel`() {
            val first = FakeConnection()
            val second = FakeConnection()
            var current: InputConnection = first
            val target = InputConnectionTarget { current }

            target.commitText("a")
            current = second
            target.commitText("b")

            assertThat(first.calls).containsExactly("commit:a:1")
            assertThat(second.calls).containsExactly("commit:b:1")
        }

        @Test
        fun `texte vide - composition et validation vides transmises`() {
            val connection = FakeConnection()

            on(connection).setComposingText("")
            on(connection).commitText("")

            assertThat(connection.calls).containsExactly("compose::1", "commit::1")
        }
    }
}
