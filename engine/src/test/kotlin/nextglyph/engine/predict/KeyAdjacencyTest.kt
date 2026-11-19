package nextglyph.engine.predict

import nextglyph.engine.layout.KeyboardLayouts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KeyAdjacencyTest {

    private val azerty = KeyAdjacency(KeyboardLayouts.AZERTY)
    private val qwerty = KeyAdjacency(KeyboardLayouts.QWERTY)

    @Nested
    inner class HappyPath {
        @Test
        fun `voisines de rangée et des rangées adjacentes`() {
            assertThat(azerty.neighbors('s')).containsExactly('a', 'd', 'e', 'q', 'w', 'x', 'z')
            assertThat(azerty.areNeighbors('u', 'i')).isTrue()
            assertThat(azerty.areNeighbors('a', 'p')).isFalse()
            assertThat(qwerty.neighbors('a')).containsExactly('q', 's', 'w', 'z')
        }

        @Test
        fun `relation symétrique`() {
            for (a in 'a'..'z') for (b in 'a'..'z') assertThat(azerty.areNeighbors(a, b)).isEqualTo(azerty.areNeighbors(b, a))
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette une distance nulle`() {
            assertThatThrownBy { KeyAdjacency(KeyboardLayouts.AZERTY, 0.0) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("strictement positive")
        }

        @Test
        fun `caractères hors de a-z - jamais voisins`() {
            assertThat(azerty.areNeighbors('\'', 'a')).isFalse()
            assertThat(azerty.areNeighbors('a', 'é')).isFalse()
            assertThat(azerty.neighbors('-')).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une lettre n'est pas sa propre voisine`() {
            assertThat(azerty.areNeighbors('a', 'a')).isFalse()
        }

        @Test
        fun `distance au seuil - QWERTY p n'a que o et l est à 1,51 u`() {
            assertThat(qwerty.neighbors('p')).containsExactly('o')
            assertThat(KeyAdjacency(KeyboardLayouts.QWERTY, 1.52).neighbors('p')).containsExactly('l', 'o')
            assertThat(KeyAdjacency(KeyboardLayouts.AZERTY, 0.5).neighbors('a')).isEmpty()
            assertThat(KeyAdjacency(KeyboardLayouts.AZERTY, 1.0).neighbors('a')).containsExactly('q', 'z')
        }
    }
}
