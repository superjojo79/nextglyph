package nextglyph.engine.predict

import nextglyph.engine.adapt.TapSpatialModel
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRow
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpatialSubstitutionTest {

    private val none = TapSpatialModel.NONE
    private val substitution = SpatialSubstitution(KeyboardLayouts.QWERTY, PredictionConfig())

    @Nested
    inner class HappyPath {
        @Test
        fun `frappe au centre de la touche - voisine horizontale à environ 0,01`() {
            assertThat(substitution.weight(TypedTap('e', 0.0, 0.0), 'r', none)).isCloseTo(0.0101, within(2e-4))
        }

        @Test
        fun `frappe sur la frontière - spatialScale`() {
            assertThat(substitution.weight(TypedTap('e', 0.5, 0.0), 'r', none)).isCloseTo(0.6, within(1e-9))
        }

        @Test
        fun `la dispersion apprise élargit la gaussienne de la touche voulue`() {
            val wide = object : TapSpatialModel by none {
                override fun sigmaScaleX(letter: Char): Float = if (letter == 'r') 2f else 1f
            }

            // Plus dispersée, « r » est plus vraisemblable loin de son centre.
            assertThat(substitution.weight(TypedTap('e', 0.0, 0.0), 'r', wide))
                .isGreaterThan(substitution.weight(TypedTap('e', 0.0, 0.0), 'r', none))
        }

        @Test
        fun `la dispersion verticale apprise compte aussi`() {
            val tall = object : TapSpatialModel by none {
                override fun sigmaScaleY(letter: Char): Float = if (letter == 'd') 2f else 1f
            }

            assertThat(substitution.weight(TypedTap('e', 0.0, 0.0), 'd', tall))
                .isGreaterThan(substitution.weight(TypedTap('e', 0.0, 0.0), 'd', none))
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `lettre hors de a-z - non applicable`() {
            assertThat(substitution.weight(TypedTap('e', 0.0, 0.0), '-', none)).isEqualTo(-1.0)
            assertThat(substitution.weight(TypedTap('é', 0.0, 0.0), 'e', none)).isEqualTo(-1.0)
        }

        @Test
        fun `lettre absente de la disposition - non applicable`() {
            val sparse = SpatialSubstitution(
                KeyboardLayout(LayoutKind.QWERTY, listOf(KeyRow(listOf(Key(KeyKind.LETTER, "a", 10f))))),
                PredictionConfig(),
            )

            assertThat(sparse.weight(TypedTap('a', 0.0, 0.0), 'b', none)).isEqualTo(-1.0)
            assertThat(sparse.weight(TypedTap('b', 0.0, 0.0), 'a', none)).isEqualTo(-1.0)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `plancher otherSubstitution`() {
            assertThat(substitution.weight(TypedTap('a', 0.0, 0.0), 'p', none)).isEqualTo(0.0005)
        }

        @Test
        fun `plafond 1`() {
            assertThat(substitution.weight(TypedTap('e', 0.9, 0.0), 'r', none)).isEqualTo(1.0)
        }

        @Test
        fun `frappe très éloignée - pas de dépassement`() {
            assertThat(substitution.weight(TypedTap('e', 100.0, 0.0), 'r', none)).isEqualTo(1.0)
            assertThat(substitution.weight(TypedTap('e', -100.0, 0.0), 'r', none)).isBetween(0.0005, 1.0)
        }

        @Test
        fun `le plancher suit la configuration`() {
            val config = PredictionConfig(correction = CorrectionWeights(otherSubstitution = 0.003))

            assertThat(SpatialSubstitution(KeyboardLayouts.QWERTY, config).weight(TypedTap('a', 0.0, 0.0), 'p', none)).isEqualTo(0.003)
        }
    }
}
