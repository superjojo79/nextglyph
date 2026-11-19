package nextglyph.tools.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class KeyboardLayoutTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `AZERTY - voisines calculées à la main`() {
            assertThat(KeyboardLayout.AZERTY.neighbors('a')).containsExactly('q', 's', 'z')
            assertThat(KeyboardLayout.AZERTY.neighbors('q')).containsExactly('a', 's', 'w', 'z')
            assertThat(KeyboardLayout.AZERTY.neighbors('m')).containsExactly('l', 'o', 'p')
            assertThat(KeyboardLayout.AZERTY.neighbors('n')).containsExactly('b', 'h', 'j')
        }

        @Test
        fun `QWERTY - voisines calculées à la main`() {
            assertThat(KeyboardLayout.QWERTY.neighbors('a')).containsExactly('q', 's', 'w', 'z')
            assertThat(KeyboardLayout.QWERTY.neighbors('u')).containsExactly('h', 'i', 'j', 'k', 'y')
        }

        @Test
        fun `distances entre centres`() {
            assertThat(KeyboardLayout.AZERTY.distance('a', 'z')).isCloseTo(1.0, within(1e-9))
            assertThat(KeyboardLayout.AZERTY.distance('a', 's')).isCloseTo(Math.sqrt(2.0), within(1e-9))
            assertThat(KeyboardLayout.AZERTY.distance('w', 'x')).isCloseTo(0.85, within(1e-9))
            assertThat(KeyboardLayout.QWERTY.distance('a', 's')).isCloseTo(0.95, within(1e-9))
            assertThat(KeyboardLayout.QWERTY.distance('q', 'a')).isCloseTo(Math.hypot(0.275, 1.0), within(1e-9))
        }

        @Test
        fun `areNeighbors est symétrique`() {
            assertThat(KeyboardLayout.AZERTY.areNeighbors('u', 'i')).isTrue()
            assertThat(KeyboardLayout.AZERTY.areNeighbors('i', 'u')).isTrue()
            assertThat(KeyboardLayout.AZERTY.areNeighbors('a', 'p')).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `distance d'un caractère hors disposition refusée`() {
            assertThatThrownBy { KeyboardLayout.AZERTY.distance('a', '\'') }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("'")
            assertThatThrownBy { KeyboardLayout.AZERTY.distance('é', 'a') }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("é")
        }

        @Test
        fun `un caractère hors disposition n'a pas de voisine`() {
            assertThat(KeyboardLayout.QWERTY.neighbors('-')).isEmpty()
            assertThat(KeyboardLayout.QWERTY.areNeighbors('-', 'a')).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {
        @ParameterizedTest
        @EnumSource(KeyboardLayout::class)
        fun `26 lettres, chacune avec au moins une voisine, jamais elle-même`(layout: KeyboardLayout) {
            assertThat(layout.letters).hasSize(26).containsAll(('a'..'z').toList())
            for (c in layout.letters) {
                assertThat(layout.neighbors(c)).isNotEmpty().doesNotContain(c)
                for (n in layout.neighbors(c)) assertThat(layout.distance(c, n)).isLessThanOrEqualTo(KeyboardLayout.NEIGHBOR_DISTANCE)
            }
        }

        @Test
        fun `QWERTY - p n'a qu'une voisine, l est à 1,506 u`() {
            assertThat(KeyboardLayout.QWERTY.neighbors('p')).containsExactly('o')
            assertThat(KeyboardLayout.QWERTY.distance('p', 'l')).isCloseTo(Math.hypot(1.125, 1.0), within(1e-9))
        }

        @Test
        fun `une distance juste au-dessus du seuil exclut la voisine`() {
            // a (0,5 ; 0) et x (2,275 ; 2) en AZERTY : trop loin ; q et w (distance 1,36) voisines.
            assertThat(KeyboardLayout.AZERTY.areNeighbors('q', 'x')).isFalse()
            assertThat(KeyboardLayout.AZERTY.distance('q', 'w')).isCloseTo(Math.hypot(0.925, 1.0), within(1e-9))
            // QWERTY : a (0,775 ; 1) et x (2,275 ; 2) à 1,80 u ; e (2,5 ; 0) à 1,99 u.
            assertThat(KeyboardLayout.QWERTY.areNeighbors('a', 'x')).isFalse()
            assertThat(KeyboardLayout.QWERTY.areNeighbors('a', 'e')).isFalse()
        }
    }
}
