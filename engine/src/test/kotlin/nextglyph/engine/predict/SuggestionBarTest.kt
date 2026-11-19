package nextglyph.engine.predict

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SuggestionBarTest {

    private val a = Suggestion("a")
    private val b = Suggestion("b")
    private val c = Suggestion("c")

    @Nested
    inner class HappyPath {
        @Test
        fun `premier au centre, deuxième à gauche, troisième à droite`() {
            val bar = SuggestionBar.of(listOf(a, b, c))

            assertThat(bar.center).isEqualTo(a)
            assertThat(bar.left).isEqualTo(b)
            assertThat(bar.right).isEqualTo(c)
            assertThat(bar.slots).containsExactly(b, a, c)
            assertThat(bar.isEmpty).isFalse()
        }

        @Test
        fun `libellé de l'espace - seulement si le texte inséré diffère`() {
            val same = Prediction("le", "le", SpaceCase.WORD, "le", emptyList(), SuggestionBar.EMPTY, "fr")
            val other = Prediction("bonj", "bonj", SpaceCase.DOMINANT, "bonjour", emptyList(), SuggestionBar.EMPTY, "fr")

            assertThat(same.spaceLabel).isNull()
            assertThat(other.spaceLabel).isEqualTo("bonjour")
            assertThat(other.toString()).contains("bonjour")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `liste vide - barre vide`() {
            assertThat(SuggestionBar.of(emptyList())).isEqualTo(SuggestionBar.EMPTY)
            assertThat(SuggestionBar.EMPTY.isEmpty).isTrue()
            assertThat(SuggestionBar.EMPTY.slots).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une ou deux suggestions, et plus de trois`() {
            assertThat(SuggestionBar.of(listOf(a)).slots).containsExactly(a)
            assertThat(SuggestionBar.of(listOf(a, b)).right).isNull()
            assertThat(SuggestionBar.of(listOf(a, b, c, Suggestion("d"))).slots).containsExactly(b, a, c)
            assertThat(SuggestionBar(null, null, c).isEmpty).isFalse()
            assertThat(SuggestionBar(b, null, null).isEmpty).isFalse()
        }
    }
}
