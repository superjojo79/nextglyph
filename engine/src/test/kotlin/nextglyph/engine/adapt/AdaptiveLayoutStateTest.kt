package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardLayouts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AdaptiveLayoutStateTest {

    private val azerty = KeyboardLayouts.AZERTY
    private val config = AdaptationConfig(mode = AdaptationMode.ELASTIC)

    /** Disposition cible : a atténuée (opacité 0,3), e élargie. */
    private val target: AdaptedLayout = AdaptedLayout(azerty).also { out ->
        val weights = KeyWeights(azerty)
        weights.setLetterWeight('a', 0f)
        weights.setLetterWeight('e', 1f)
        LayoutSolver(azerty, config).solve(weights, out)
    }

    private fun state() = AdaptiveLayoutState(azerty, config)

    private fun AdaptiveLayoutState.opacityOfA() = frame.opacity(0, 0)

    @Nested
    inner class HappyPath {

        @Test
        fun `une frappe lente est appliquée et animée en 100 ms`() {
            val state = state()

            state.submit(target, 1000L)
            assertThat(state.hasPendingUpdate).isTrue()
            assertThat(state.nextUpdateTimeMs).isEqualTo(1000L)

            assertThat(state.advance(1000L)).isTrue()
            assertThat(state.isAnimating).isTrue()
            assertThat(state.hasPendingUpdate).isFalse()
            assertThat(state.opacityOfA()).isEqualTo(1f)

            state.advance(1050L)
            // ease-out quadratique à mi-parcours : 75 %.
            assertThat(state.opacityOfA()).isCloseTo(1f - 0.7f * 0.75f, Offset.offset(1e-5f))

            assertThat(state.advance(1100L)).isFalse()
            assertThat(state.isAnimating).isFalse()
            assertThat(state.frame.contentEquals(target)).isTrue()
        }

        @Test
        fun `rien n'est appliqué tant qu'un doigt est posé`() {
            val state = state()
            state.pointerDown(990L)

            state.submit(target, 1000L)

            assertThat(state.advance(1200L)).isTrue()
            assertThat(state.frame.isRest).isTrue()
            state.pointerUp(1210L)
            state.advance(1210L)
            assertThat(state.isAnimating).isTrue()
        }

        @Test
        fun `l'animation est gelée pendant le toucher puis reprend où elle en était`() {
            val state = state()
            state.submit(target, 1000L)
            state.advance(1000L)
            state.advance(1050L)
            val midway = state.opacityOfA()

            state.pointerDown(1050L)
            state.advance(1500L)
            assertThat(state.opacityOfA()).isEqualTo(midway)
            state.pointerUp(1550L)
            state.advance(1550L)

            assertThat(state.opacityOfA()).isEqualTo(midway)
            state.advance(1600L)
            assertThat(state.frame.contentEquals(target)).isTrue()
        }

        @Test
        fun `en saisie rapide, la mise à jour attend une pause`() {
            val state = state()
            state.submit(AdaptedLayout(azerty), 1000L)
            state.advance(1000L)
            state.advance(1200L)

            state.submit(target, 1250L)
            state.submit(target, 1300L)

            assertThat(state.nextUpdateTimeMs).isEqualTo(1420L)
            assertThat(state.advance(1419L)).isTrue()
            assertThat(state.isAnimating).isFalse()
            assertThat(state.frame.isRest).isTrue()
            state.advance(1420L)
            assertThat(state.isAnimating).isTrue()
        }

        @Test
        fun `la disposition soumise est copiée`() {
            val state = state()
            val buffer = AdaptedLayout(azerty)
            buffer.copyFrom(target)

            state.submit(buffer, 0L)
            buffer.setRest()
            state.advance(0L, animatorDurationScale = 0f)

            assertThat(state.frame.contentEquals(target)).isTrue()
        }

        @Test
        fun `l'échelle des animations système allonge la durée`() {
            val state = state()
            state.submit(target, 0L)
            state.advance(0L, 2f)

            state.advance(100L, 2f)

            assertThat(state.opacityOfA()).isCloseTo(1f - 0.7f * 0.75f, Offset.offset(1e-5f))
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette une disposition d'une autre disposition`() {
            assertThatThrownBy { state().submit(AdaptedLayout(KeyboardLayouts.QWERTY), 0L) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejette une échelle de durée négative ou non finie`() {
            assertThatThrownBy { state().advance(0L, -0.1f) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { state().advance(0L, Float.NaN) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { state().advance(0L, Float.POSITIVE_INFINITY) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `un relâchement sans pointeur posé est ignoré`() {
            val state = state()

            state.pointerUp(0L)
            assertThat(state.pointersDown).isEqualTo(0)
            state.cancelPointers(0L)
            assertThat(state.pointersDown).isEqualTo(0)
            state.pointerDown(1L)
            assertThat(state.pointersDown).isEqualTo(1)
        }

        @Test
        fun `une annulation sans pointeur posé ne décale pas l'animation`() {
            val state = state()
            state.submit(target, 1000L)
            state.advance(1000L)
            state.advance(1050L)
            val midway = state.opacityOfA()

            state.cancelPointers(1060L)
            state.advance(1060L)

            assertThat(state.opacityOfA()).isLessThan(midway)
        }

        @Test
        fun `l'annulation lève tous les pointeurs`() {
            val state = state()
            state.pointerDown(0L)
            state.pointerDown(1L)
            state.submit(target, 2L)

            state.cancelPointers(3L)
            state.advance(3L, 0f)

            assertThat(state.pointersDown).isEqualTo(0)
            assertThat(state.frame.contentEquals(target)).isTrue()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `intervalle égal au seuil de saisie rapide, appliqué immédiatement`() {
            val state = state()
            state.submit(AdaptedLayout(azerty), 1000L)

            state.submit(target, 1120L)

            assertThat(state.nextUpdateTimeMs).isEqualTo(1120L)
        }

        @Test
        fun `intervalle juste sous le seuil, différé`() {
            val state = state()
            state.submit(AdaptedLayout(azerty), 1000L)

            state.submit(target, 1119L)

            assertThat(state.nextUpdateTimeMs).isEqualTo(1239L)
        }

        @Test
        fun `sans disposition en attente, pas de date de mise à jour`() {
            val state = state()

            assertThat(state.nextUpdateTimeMs).isEqualTo(Long.MIN_VALUE)
            assertThat(state.advance(0L)).isFalse()
        }

        @Test
        fun `animations système désactivées, application immédiate`() {
            val state = state()
            state.submit(target, 0L)

            assertThat(state.advance(0L, 0f)).isFalse()

            assertThat(state.frame.contentEquals(target)).isTrue()
        }

        @Test
        fun `durée d'animation nulle, application immédiate`() {
            val state = AdaptiveLayoutState(azerty, config.copy(animationMs = 0L))
            state.submit(target, 0L)

            state.advance(0L)

            assertThat(state.frame.contentEquals(target)).isTrue()
        }

        @Test
        fun `deux pointeurs pendant l'animation, le gel compte depuis le premier`() {
            val state = state()
            state.submit(target, 1000L)
            state.advance(1000L)
            state.advance(1050L)
            val midway = state.opacityOfA()

            state.pointerDown(1050L)
            state.pointerDown(1200L)
            assertThat(state.pointersDown).isEqualTo(2)
            assertThat(state.advance(1250L)).isTrue()
            state.pointerUp(1300L)
            state.pointerUp(1400L)
            state.advance(1400L)

            assertThat(state.opacityOfA()).isEqualTo(midway)
        }

        @Test
        fun `doigt posé sans rien en attente ni animation, rien à rappeler`() {
            val state = state()
            state.pointerDown(0L)

            assertThat(state.advance(10L)).isFalse()
        }

        @Test
        fun `après application, plus de date de mise à jour ni d'image résiduelle`() {
            val state = state()
            state.submit(target, 0L)
            state.advance(0L, 0f)

            assertThat(state.nextUpdateTimeMs).isEqualTo(Long.MIN_VALUE)
            state.reset()
            state.advance(1000L)
            assertThat(state.frame.isRest).isTrue()
        }

        @Test
        fun `deux pointeurs, le gel dure jusqu'au dernier relâchement`() {
            val state = state()
            state.pointerDown(0L)
            state.pointerDown(5L)
            state.submit(target, 10L)

            state.pointerUp(20L)
            state.advance(20L, 0f)
            assertThat(state.frame.isRest).isTrue()

            state.pointerUp(30L)
            state.advance(30L, 0f)
            assertThat(state.frame.contentEquals(target)).isTrue()
        }

        @Test
        fun `une nouvelle disposition en cours d'animation part de l'image courante`() {
            val state = state()
            state.submit(target, 0L)
            state.advance(0L)
            state.advance(50L)
            val midway = state.opacityOfA()

            state.submit(AdaptedLayout(azerty), 500L)
            state.advance(500L)

            assertThat(state.opacityOfA()).isEqualTo(midway)
            state.advance(600L)
            assertThat(state.frame.isRest).isTrue()
        }

        @Test
        fun `remise à zéro, repos immédiat`() {
            val state = state()
            state.pointerDown(0L)
            state.submit(target, 0L)
            state.pointerUp(1L)
            state.advance(1L, 0f)

            state.submit(AdaptedLayout(azerty), 2L)
            state.pointerDown(3L)
            state.reset()

            assertThat(state.frame.isRest).isTrue()
            assertThat(state.hasPendingUpdate).isFalse()
            assertThat(state.isAnimating).isFalse()
            assertThat(state.pointersDown).isEqualTo(0)
            assertThat(state.nextUpdateTimeMs).isEqualTo(Long.MIN_VALUE)
            state.submit(target, 10L)
            assertThat(state.nextUpdateTimeMs).isEqualTo(10L)
        }

        @Test
        fun `relâchement avant le gel enregistré, l'animation ne recule pas`() {
            val state = state()
            state.submit(target, 0L)
            state.advance(0L)
            state.advance(50L)
            val midway = state.opacityOfA()

            state.pointerDown(60L)
            state.pointerUp(40L)
            state.advance(60L)

            assertThat(state.opacityOfA()).isLessThan(midway)
        }
    }
}
