package nextglyph.engine.session

import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.profile.AndroidInputConstants as C
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EnterKeyLabelTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `chaque action de l'éditeur a son libellé`() {
            val labels = mapOf(
                C.IME_ACTION_GO to "➔",
                C.IME_ACTION_SEARCH to "⌕",
                C.IME_ACTION_SEND to "➤",
                C.IME_ACTION_NEXT to "⇥",
                C.IME_ACTION_PREVIOUS to "⇤",
                C.IME_ACTION_DONE to "✓",
            )

            for ((action, label) in labels) assertThat(EnterKeyLabel.of(EnterBehavior.EditorAction(action))).isEqualTo(label)
        }

        @Test
        fun `saut de ligne et touche Entrée - flèche de retour`() {
            assertThat(EnterKeyLabel.of(EnterBehavior.NewLine)).isEqualTo("↵")
            assertThat(EnterKeyLabel.of(EnterBehavior.EnterKey)).isEqualTo("↵")
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @ValueSource(ints = [-1, 8, 99, 255])
        fun `une action inconnue garde la flèche de retour`(action: Int) {
            assertThat(EnterKeyLabel.of(EnterBehavior.EditorAction(action))).isEqualTo("↵")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `actions voisines des bornes - aucune et non précisée`() {
            assertThat(EnterKeyLabel.of(EnterBehavior.EditorAction(0))).isEqualTo("↵")
            assertThat(EnterKeyLabel.of(EnterBehavior.EditorAction(1))).isEqualTo("↵")
        }

        @Test
        fun `tous les libellés sont distincts et tiennent en un caractère`() {
            val labels = (0..8).map { EnterKeyLabel.of(EnterBehavior.EditorAction(it)) }.toSet()

            assertThat(labels).hasSize(7)
            assertThat(labels).allMatch { it.length == 1 }
        }
    }
}
