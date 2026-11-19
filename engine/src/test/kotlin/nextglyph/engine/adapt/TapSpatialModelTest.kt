package nextglyph.engine.adapt

import nextglyph.engine.adapt.AdaptFixtures.randomWeights
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.learn.SpatialModel
import nextglyph.engine.predict.PredictionSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TapSpatialModelTest {

    private val azerty = KeyboardLayouts.AZERTY
    private val rest = KeyboardGeometry.compute(azerty, 1000f, 100f, 0f)

    /** Modèle d'essai : décalages et facteurs fixes pour une lettre. */
    private class Fixed(
        private val letter: Char,
        private val dx: Float = 0f,
        private val dy: Float = 0f,
        private val sx: Float = 1f,
        private val sy: Float = 1f,
    ) : TapSpatialModel {
        override fun offsetX(letter: Char) = if (letter == this.letter) dx else 0f
        override fun offsetY(letter: Char) = if (letter == this.letter) dy else 0f
        override fun sigmaScaleX(letter: Char) = if (letter == this.letter) sx else 1f
        override fun sigmaScaleY(letter: Char) = if (letter == this.letter) sy else 1f
    }

    private fun decode(x: Float, y: Float, spatial: TapSpatialModel, weights: KeyWeights? = null): TapDecoding {
        val out = TapDecoding(azerty)
        TapDecoder(azerty, TapDecoderConfig(), spatial).decode(x, y, rest, rest, weights, out)
        return out
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `un modèle vide donne exactement les mêmes décisions et scores que sans modèle`() {
            val random = Random(7)
            val empty = SpatialModel()
            val plain = TapDecoder(azerty)
            val personal = TapDecoder(azerty, TapDecoderConfig(), empty)
            val a = TapDecoding(azerty)
            val b = TapDecoding(azerty)

            repeat(2000) {
                val weights = if (random.nextInt(3) == 0) null else randomWeights(azerty, random)
                val x = random.nextFloat() * 1000f
                val y = random.nextFloat() * 300f
                val okA = plain.decode(x, y, rest, rest, weights, a)
                val okB = personal.decode(x, y, rest, rest, weights, b)

                assertThat(okB).isEqualTo(okA)
                assertThat(b.key).isEqualTo(a.key)
                assertThat(b.decision).isEqualTo(a.decision)
                for (r in 0 until azerty.rows.size) {
                    for (k in 0 until azerty.rows[r].keys.size) assertThat(b.score(r, k)).isEqualTo(a.score(r, k))
                }
            }
        }

        @Test
        fun `le décalage appris déplace la frontière entre deux touches`() {
            // Frappe à 5 px à gauche de la frontière a|z (x = 100) : sans modèle, c'est un a.
            assertThat(decode(95f, 50f, TapSpatialModel.NONE).key?.label).isEqualTo("a")

            // L'utilisateur frappe z 20 % de touche trop à gauche : cette frappe est probablement un z.
            val result = decode(95f, 50f, Fixed('z', dx = -0.2f))

            assertThat(result.key?.label).isEqualTo("z")
            assertThat(result.decision).isEqualTo(TapDecision.SCORE)
        }

        @Test
        fun `le facteur sigma change la vraisemblance`() {
            val base = decode(150f, 50f, TapSpatialModel.NONE).score(0, 1)

            val wider = decode(150f, 50f, Fixed('z', sx = 2f)).score(0, 1)
            val taller = decode(150f, 50f, Fixed('z', sy = 4f)).score(0, 1)

            // Au centre, la densité gaussienne est inversement proportionnelle à σ.
            assertThat(wider).isCloseTo(base / 2, Offset.offset(base * 1e-9))
            assertThat(taller).isCloseTo(base / 4, Offset.offset(base * 1e-9))
        }

        @Test
        fun `le noyau et la nature de la touche ne dépendent pas du modèle`() {
            val result = decode(150f, 50f, Fixed('z', dx = -0.3f))

            assertThat(result.decision).isEqualTo(TapDecision.CORE)
            assertThat(result.key?.label).isEqualTo("z")
        }

        @Test
        fun `le clavier adaptatif transmet le modèle au décodeur`() {
            val keyboard = AdaptiveKeyboard(azerty, AdaptationConfig(), Fixed('z', dx = -0.2f))

            assertThat(keyboard.decode(95f, 50f, rest, rest)?.key?.label).isEqualTo("z")
            assertThat(AdaptiveKeyboard(azerty).decode(95f, 50f, rest, rest)?.key?.label).isEqualTo("a")
        }

        @Test
        fun `un modèle appris par frappes validées déplace la décision`() {
            val model = SpatialModel()
            val z = rest.rows[0][1]
            repeat(200) {
                model.stage(z.centerX - 0.2f * z.width, z.centerY, z, PredictionSettings())
            }
            model.confirm()

            assertThat(model.offsetX('z')).isLessThan(-0.15f)
            assertThat(decode(95f, 50f, model).key?.label).isEqualTo("z")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `les lettres inconnues du modèle gardent le comportement par défaut`() {
            val model = TapSpatialModel.NONE

            assertThat(model.offsetX('é')).isZero()
            assertThat(model.offsetY('é')).isZero()
            assertThat(model.sigmaScaleX('é')).isEqualTo(1f)
            assertThat(model.sigmaScaleY('é')).isEqualTo(1f)
        }

        @Test
        fun `un tap hors du clavier n'est pas décodé, avec ou sans modèle`() {
            val out = TapDecoding(azerty)

            val decoded = TapDecoder(azerty, TapDecoderConfig(), Fixed('z', dx = 0.2f)).decode(-5f, 50f, rest, rest, null, out)

            assertThat(decoded).isFalse()
            assertThat(out.key).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un décalage nul et un facteur de un sont neutres`() {
            val neutral = decode(95f, 50f, Fixed('z', 0f, 0f, 1f, 1f))
            val none = decode(95f, 50f, TapSpatialModel.NONE)

            assertThat(neutral.key).isEqualTo(none.key)
            assertThat(neutral.score(0, 1)).isEqualTo(none.score(0, 1))
        }

        @Test
        fun `un décalage maximal reste décodable sur toute la rangée`() {
            for (x in listOf(1f, 50f, 99f, 500f, 999f)) {
                assertThat(decode(x, 50f, Fixed('z', dx = 0.25f, dy = -0.25f, sx = 1.6f, sy = 0.7f)).key).isNotNull()
            }
        }
    }
}
