package nextglyph.engine.predict

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WordTapsTest {

    private fun tap(c: Char) = TypedTap(c, 0.1, -0.1)

    @Nested
    inner class HappyPath {
        @Test
        fun `frappes alignées sur la clé`() {
            val taps = WordTaps(listOf(tap('a'), tap('b'), null, tap('d')))

            assertThat(taps.alignedTo("abcd")).containsExactly(tap('a'), tap('b'), null, tap('d'))
        }

        @Test
        fun `frappes plus longues que la clé - les dernières entrées (suffixe)`() {
            val taps = WordTaps(listOf(tap('l'), null, tap('a'), tap('m'), tap('i')))

            assertThat(taps.alignedTo("ami")).containsExactly(tap('a'), tap('m'), tap('i'))
        }

        @Test
        fun `modèle spatial neutre par défaut`() {
            assertThat(WordTaps(emptyList()).spatial.offsetX('a')).isZero()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `longueur insuffisante`() {
            assertThat(WordTaps(listOf(tap('a'), tap('b'))).alignedTo("abc")).isNull()
        }

        @Test
        fun `une lettre ne correspond pas`() {
            assertThat(WordTaps(listOf(tap('a'), tap('x'), tap('c'))).alignedTo("abc")).isNull()
        }

        @Test
        fun `toutes les frappes inconnues`() {
            assertThat(WordTaps(listOf(null, null, null)).alignedTo("abc")).isNull()
        }

        @Test
        fun `décalage entre les frappes et la clé - la lettre diffère après le suffixe`() {
            assertThat(WordTaps(listOf(tap('a'), tap('b'), tap('c'))).alignedTo("bcd")).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `clé vide`() {
            assertThat(WordTaps(listOf(tap('a'))).alignedTo("")).isNull()
        }

        @Test
        fun `une seule frappe, une seule lettre`() {
            assertThat(WordTaps(listOf(tap('a'))).alignedTo("a")).containsExactly(tap('a'))
        }

        @Test
        fun `une seule frappe connue parmi des inconnues`() {
            assertThat(WordTaps(listOf(null, tap('b'))).alignedTo("ab")).containsExactly(null, tap('b'))
        }

        @Test
        fun `aucune frappe`() {
            assertThat(WordTaps(emptyList()).alignedTo("a")).isNull()
        }
    }
}
