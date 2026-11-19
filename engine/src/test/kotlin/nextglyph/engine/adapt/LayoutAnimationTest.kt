package nextglyph.engine.adapt

import nextglyph.engine.adapt.AdaptFixtures.LAYOUTS
import nextglyph.engine.adapt.AdaptFixtures.assertInvariants
import nextglyph.engine.adapt.AdaptFixtures.randomWeights
import nextglyph.engine.layout.KeyboardLayouts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class LayoutAnimationTest {

    private val azerty = KeyboardLayouts.AZERTY
    private val elastic = AdaptationConfig(mode = AdaptationMode.ELASTIC)

    private fun target(): AdaptedLayout {
        val weights = KeyWeights(azerty)
        weights.setLetterWeight('a', 0f)
        weights.setLetterWeight('e', 1f)
        val out = AdaptedLayout(azerty)
        LayoutSolver(azerty, elastic).solve(weights, out)
        return out
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `ease-out quadratique`() {
            assertThat(LayoutAnimation.easeOut(0.5f, 2f)).isEqualTo(0.75f)
            assertThat(LayoutAnimation.easeOut(0.5f, 3f)).isEqualTo(0.875f)
            assertThat(LayoutAnimation.easeOut(0.5f, 1f)).isEqualTo(0.5f)
        }

        @Test
        fun `image intermédiaire, mélange linéaire après la courbe`() {
            val from = AdaptedLayout(azerty)
            val to = target()
            val out = AdaptedLayout(azerty)

            LayoutAnimation.interpolate(from, to, 0.5f, out, 2f)

            assertThat(out.opacity(0, 0)).isCloseTo(1f + (to.opacity(0, 0) - 1f) * 0.75f, Offset.offset(1e-6f))
            assertThat(out.labelScale(0, 0)).isCloseTo(1f + (to.labelScale(0, 0) - 1f) * 0.75f, Offset.offset(1e-6f))
            assertThat(out.width(0, 2)).isCloseTo(1f + (to.width(0, 2) - 1f) * 0.75f, Offset.offset(1e-6f))
        }

        @Test
        fun `propriété, chaque image intermédiaire respecte les garanties du solveur`() {
            val random = Random(99)
            repeat(300) { n ->
                val layout = LAYOUTS[n % 2]
                val solver = LayoutSolver(layout, elastic)
                val from = AdaptedLayout(layout)
                val to = AdaptedLayout(layout)
                solver.solve(randomWeights(layout, random), from)
                solver.solve(randomWeights(layout, random), to)
                val out = AdaptedLayout(layout)

                LayoutAnimation.interpolate(from, to, random.nextFloat(), out, elastic.easeOutPower)

                assertInvariants(out, elastic, 0.5f)
            }
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette des dispositions différentes`() {
            val other = AdaptedLayout(KeyboardLayouts.QWERTY)

            assertThatThrownBy { LayoutAnimation.interpolate(AdaptedLayout(azerty), other, 0.5f, AdaptedLayout(azerty), 2f) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LayoutAnimation.interpolate(AdaptedLayout(azerty), AdaptedLayout(azerty), 0.5f, other, 2f) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejette un instant ou un exposant invalide`() {
            assertThatThrownBy { LayoutAnimation.easeOut(Float.NaN, 2f) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LayoutAnimation.easeOut(0.5f, 0.5f) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LayoutAnimation.easeOut(0.5f, Float.POSITIVE_INFINITY) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `t = 0 donne exactement le départ, t = 1 exactement l'arrivée`() {
            val from = AdaptedLayout(azerty)
            val to = target()
            val out = AdaptedLayout(azerty)

            LayoutAnimation.interpolate(from, to, 0f, out, 2f)
            assertThat(out.contentEquals(from)).isTrue()

            LayoutAnimation.interpolate(from, to, 1f, out, 2f)
            assertThat(out.contentEquals(to)).isTrue()
        }

        @Test
        fun `t est borné à 0 et 1`() {
            assertThat(LayoutAnimation.easeOut(-1f, 2f)).isEqualTo(0f)
            assertThat(LayoutAnimation.easeOut(2f, 2f)).isEqualTo(1f)
            assertThat(LayoutAnimation.easeOut(Float.NEGATIVE_INFINITY, 2f)).isEqualTo(0f)
        }

        @Test
        fun `la sortie peut être le départ lui-même`() {
            val from = AdaptedLayout(azerty)
            val to = target()

            LayoutAnimation.interpolate(from, to, 1f, from, 2f)

            assertThat(from.contentEquals(to)).isTrue()
        }
    }
}
