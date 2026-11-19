package nextglyph.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import nextglyph.engine.input.EnterBehavior
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EnterBehaviorResolverTest {

    private val singleLine = InputType.TYPE_CLASS_TEXT
    private val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Nested
    inner class HappyPath {
        @ParameterizedTest
        @ValueSource(
            ints = [
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS,
            ],
        )
        fun `une action explicite est exécutée`(action: Int) {
            assertThat(EnterBehaviorResolver.resolve(action, singleLine)).isEqualTo(EnterBehavior.EditorAction(action))
        }

        @Test
        fun `une action explicite l'emporte même sur un champ multiligne`() {
            assertThat(EnterBehaviorResolver.resolve(EditorInfo.IME_ACTION_SEND, multiLine))
                .isEqualTo(EnterBehavior.EditorAction(EditorInfo.IME_ACTION_SEND))
        }

        @Test
        fun `sans action, un champ multiligne reçoit un saut de ligne`() {
            assertThat(EnterBehaviorResolver.resolve(EditorInfo.IME_ACTION_NONE, multiLine)).isEqualTo(EnterBehavior.NewLine)
        }

        @Test
        fun `sans action, un champ une ligne reçoit la touche Entrée`() {
            assertThat(EnterBehaviorResolver.resolve(EditorInfo.IME_ACTION_NONE, singleLine)).isEqualTo(EnterBehavior.EnterKey)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `IME_FLAG_NO_ENTER_ACTION désactive l'action et donne un saut de ligne en multiligne`() {
            val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION

            assertThat(EnterBehaviorResolver.resolve(options, multiLine)).isEqualTo(EnterBehavior.NewLine)
        }

        @Test
        fun `IME_FLAG_NO_ENTER_ACTION en une ligne envoie la touche Entrée`() {
            val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION

            assertThat(EnterBehaviorResolver.resolve(options, singleLine)).isEqualTo(EnterBehavior.EnterKey)
        }

        @Test
        fun `un champ sans type n'est pas multiligne`() {
            assertThat(EnterBehaviorResolver.resolve(EditorInfo.IME_ACTION_NONE, InputType.TYPE_NULL)).isEqualTo(EnterBehavior.EnterKey)
        }

        @Test
        fun `le drapeau multiligne n'a de sens que pour une classe texte`() {
            val numberWithFlag = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE

            assertThat(EnterBehaviorResolver.resolve(EditorInfo.IME_ACTION_NONE, numberWithFlag)).isEqualTo(EnterBehavior.EnterKey)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `IME_ACTION_UNSPECIFIED est traité comme sans action`() {
            assertThat(EnterBehaviorResolver.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, multiLine)).isEqualTo(EnterBehavior.NewLine)
            assertThat(EnterBehaviorResolver.resolve(EditorInfo.IME_ACTION_UNSPECIFIED, singleLine)).isEqualTo(EnterBehavior.EnterKey)
        }

        @Test
        fun `les autres drapeaux d'imeOptions ne masquent pas l'action`() {
            val options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

            assertThat(EnterBehaviorResolver.resolve(options, singleLine)).isEqualTo(EnterBehavior.EditorAction(EditorInfo.IME_ACTION_SEARCH))
        }

        @Test
        fun `zéro est sans action`() {
            assertThat(EnterBehaviorResolver.resolve(0, 0)).isEqualTo(EnterBehavior.EnterKey)
        }
    }
}
