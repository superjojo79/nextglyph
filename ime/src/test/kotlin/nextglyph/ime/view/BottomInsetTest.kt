package nextglyph.ime.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BottomInsetTest {

    @Nested
    inner class HappyPath {

        @Test
        fun `le plus grand encart système est retenu, pas leur somme`() {
            assertThat(BottomInset.resolve(listOf(63, 144, 0), 0)).isEqualTo(144)
        }

        @Test
        fun `la marge réglée s'ajoute à l'encart système`() {
            assertThat(BottomInset.resolve(listOf(144, 63), 48)).isEqualTo(192)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `encarts négatifs ignorés`() {
            assertThat(BottomInset.resolve(listOf(-10, -1), 0)).isZero()
        }

        @Test
        fun `marge réglée négative traitée comme nulle`() {
            assertThat(BottomInset.resolve(listOf(40), -20)).isEqualTo(40)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `aucun encart ni marge - zéro`() {
            assertThat(BottomInset.resolve(emptyList(), 0)).isZero()
        }

        @Test
        fun `aucun encart - la marge réglée seule`() {
            assertThat(BottomInset.resolve(emptyList(), 1)).isEqualTo(1)
        }

        @Test
        fun `encarts égaux - une seule fois`() {
            assertThat(BottomInset.resolve(listOf(1, 1), 0)).isEqualTo(1)
        }
    }
}
