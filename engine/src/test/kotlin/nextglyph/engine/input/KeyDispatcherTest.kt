package nextglyph.engine.input

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyDispatcherTest {

    private class FakeEditor : EditorGateway {
        val calls = mutableListOf<String>()
        override fun commitText(text: String) { calls += "commit:$text" }
        override fun deleteBackward() { calls += "delete" }
        override fun performEditorAction(actionId: Int) { calls += "action:$actionId" }
        override fun sendEnterKey() { calls += "enterKey" }
    }

    private val editor = FakeEditor()
    private val dispatcher = KeyDispatcher(editor)
    private val enter = Key(KeyKind.ENTER, "↵", 1f)
    private val space = Key(KeyKind.SPACE, "", 2f)

    @Nested
    inner class HappyPath {
        @Test
        fun `une lettre est insérée`() {
            dispatcher.onKey(Key.letter('é'), EnterBehavior.EnterKey)

            assertThat(editor.calls).containsExactly("commit:é")
        }

        @Test
        fun `l'espace insère une espace`() {
            dispatcher.onKey(space, EnterBehavior.EnterKey)

            assertThat(editor.calls).containsExactly("commit: ")
        }

        @Test
        fun `effacer appelle deleteBackward`() {
            dispatcher.onKey(Key(KeyKind.BACKSPACE, "⌫", 1f), EnterBehavior.EnterKey)

            assertThat(editor.calls).containsExactly("delete")
        }

        @Test
        fun `un chiffre ou un symbole est inséré`() {
            dispatcher.onKey(Key.character('7'), EnterBehavior.EnterKey)
            dispatcher.onKey(Key.character('€'), EnterBehavior.EnterKey)

            assertThat(editor.calls).containsExactly("commit:7", "commit:€")
        }

        @Test
        fun `Entrée selon le comportement`() {
            dispatcher.onKey(enter, EnterBehavior.NewLine)
            dispatcher.onKey(enter, EnterBehavior.EditorAction(3))
            dispatcher.onKey(enter, EnterBehavior.EnterKey)

            assertThat(editor.calls).containsExactly("commit:\n", "action:3", "enterKey")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `la touche symboles n'a pas encore d'effet et ne lève rien`() {
            dispatcher.onKey(Key(KeyKind.SYMBOLS, "?123", 1f), EnterBehavior.NewLine)

            assertThat(editor.calls).isEmpty()
        }

        @Test
        fun `majuscule, retour aux lettres, séparateur décimal et emplacement vide sont sans effet direct`() {
            dispatcher.onKey(Key(KeyKind.SHIFT, "⇧", 1f), EnterBehavior.NewLine)
            dispatcher.onKey(Key(KeyKind.LETTERS, "ABC", 1f), EnterBehavior.NewLine)
            dispatcher.onKey(Key(KeyKind.DECIMAL, ",", 1f), EnterBehavior.NewLine)
            dispatcher.onKey(Key.empty(1f), EnterBehavior.NewLine)

            assertThat(editor.calls).isEmpty()
        }

        @Test
        fun `le comportement d'Entrée est ignoré pour les autres touches`() {
            dispatcher.onKey(Key.letter('a'), EnterBehavior.EditorAction(2))
            dispatcher.onKey(space, EnterBehavior.NewLine)

            assertThat(editor.calls).containsExactly("commit:a", "commit: ")
        }
    }

    @Nested
    inner class BoundaryValues {
        @ParameterizedTest
        @ValueSource(ints = [0, 1, Int.MAX_VALUE, -1])
        fun `l'identifiant d'action est transmis tel quel`(actionId: Int) {
            dispatcher.onKey(enter, EnterBehavior.EditorAction(actionId))

            assertThat(editor.calls).containsExactly("action:$actionId")
        }

        @Test
        fun `chaque appui produit exactement un appel`() {
            repeat(3) { dispatcher.onKey(Key.letter('a'), EnterBehavior.NewLine) }

            assertThat(editor.calls).hasSize(3)
        }
    }
}
