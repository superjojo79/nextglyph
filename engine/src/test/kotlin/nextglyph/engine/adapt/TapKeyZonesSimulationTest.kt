package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.learn.SpatialModel
import nextglyph.engine.predict.PredictionSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Simulation déterministe (graine fixe) d'un utilisateur qui vise l'espace en tapant systématiquement trop haut
 * (vers la rangée des lettres) et trop à gauche (vers `n`) : le modèle apprend à partir des seules frappes que
 * la session validerait (décodées comme espace et tombées dans le rectangle de l'espace au repos), puis le taux
 * d'espaces manqués est mesuré sur de nouvelles frappes, avec le modèle appris et sans modèle.
 */
class TapKeyZonesSimulationTest {

    private val layout = KeyboardLayouts.AZERTY
    private val geometry = KeyboardGeometry.compute(layout, 1000f, 100f, 0f)
    private val space: KeyRect = geometry.rows[2].first { it.key.kind == KeyKind.SPACE }
    private val letterLeftOfSpace: KeyRect = geometry.rows[2][space.keyIndex - 1]
    private val settings = PredictionSettings()

    /** Utilisateur : décalage moyen et dispersion (pixels) autour du centre de la touche visée. */
    private class User(val biasX: Float, val biasY: Float, val sdX: Float, val sdY: Float)

    private val biased = User(biasX = -60f, biasY = -30f, sdX = 40f, sdY = 20f)

    private fun tapAt(user: User, target: KeyRect, random: Random, out: FloatArray) {
        out[0] = target.centerX + user.biasX + random.nextGaussian().toFloat() * user.sdX
        out[1] = target.centerY + user.biasY + random.nextGaussian().toFloat() * user.sdY
    }

    /**
     * Entraîne un modèle sur [taps] frappes visant [target] : chaque frappe est décodée avec le modèle courant ;
     * seule une frappe décodée comme l'espace est mise en attente, par `stageKey` qui refuse celles hors du
     * rectangle au repos (comme la session), puis validée par la frappe suivante.
     */
    private fun train(user: User, taps: Int, seed: Long, config: TapDecoderConfig = TapDecoderConfig()): Pair<SpatialModel, Int> {
        val model = SpatialModel()
        val decoder = TapDecoder(layout, config, model)
        val out = TapDecoding(layout)
        val random = Random(seed)
        val point = FloatArray(2)
        var accepted = 0
        repeat(taps) {
            tapAt(user, space, random, point)
            decoder.decode(point[0], point[1], geometry, geometry, null, out)
            if (out.key?.kind == KeyKind.SPACE) {
                val dx = (point[0] - space.centerX).toDouble() / space.width
                val dy = (point[1] - space.centerY).toDouble() / space.height
                if (model.stageKey(KeyKind.SPACE, dx, dy, settings)) {
                    accepted++
                    model.confirmKey()
                }
            }
        }
        return model to accepted
    }

    /** Part des [taps] frappes visant [target] qui ne sont pas décodées comme [target]. */
    private fun missRate(user: User, target: KeyRect, model: TapSpatialModel, taps: Int, seed: Long, config: TapDecoderConfig = TapDecoderConfig()): Double {
        val decoder = TapDecoder(layout, config, model)
        val out = TapDecoding(layout)
        val random = Random(seed)
        val point = FloatArray(2)
        var misses = 0
        repeat(taps) {
            tapAt(user, target, random, point)
            decoder.decode(point[0], point[1], geometry, geometry, null, out)
            if (out.key !== target.key) misses++
        }
        return misses.toDouble() / taps
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `après apprentissage, l'utilisateur qui vise l'espace trop haut et trop à gauche en manque beaucoup moins`() {
            val (model, accepted) = train(biased, 600, seed = 30)

            val before = missRate(biased, space, TapSpatialModel.NONE, 20_000, seed = 31)
            val after = missRate(biased, space, model, 20_000, seed = 31)

            assertThat(accepted).isGreaterThan(200)
            assertThat(model.keyOffsetX(KeyKind.SPACE)).isNegative()
            assertThat(model.keyOffsetY(KeyKind.SPACE)).isNegative()
            assertThat(before).isGreaterThan(0.35)
            assertThat(after).isLessThan(before - 0.10)
        }

        @Test
        fun `le déplacement de frontière appris reste dans la borne de la configuration`() {
            val (model, _) = train(biased, 600, seed = 32)
            val config = TapDecoderConfig()
            val limitX = config.maxBoundaryShift * minOf(letterLeftOfSpace.width, space.width)
            val limitY = config.maxBoundaryShift * geometry.rowHeight
            val decoder = TapDecoder(layout, config, model)
            val out = TapDecoding(layout)

            // La frontière gauche de l'espace ne recule pas de plus de limitX, ni la frontière haute de plus de limitY.
            decoder.decode(space.left - limitX - 1f, space.centerY, geometry, geometry, null, out)
            assertThat(out.key?.kind).isEqualTo(KeyKind.LETTER)
            decoder.decode(space.centerX, space.top - limitY - 1f, geometry, geometry, null, out)
            assertThat(out.key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `le coût sur la lettre voisine d'un utilisateur centré reste borné`() {
            val centered = User(biasX = 0f, biasY = 0f, sdX = 40f, sdY = 20f)
            val (model, _) = train(biased, 600, seed = 39)

            val before = missRate(centered, letterLeftOfSpace, TapSpatialModel.NONE, 20_000, seed = 40)
            val after = missRate(centered, letterLeftOfSpace, model, 20_000, seed = 40)

            assertThat(after).isLessThan(before + 0.15)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un utilisateur centré n'apprend aucun décalage notable et ne perd rien`() {
            val centered = User(biasX = 0f, biasY = 0f, sdX = 40f, sdY = 20f)
            val (model, _) = train(centered, 600, seed = 33)

            val before = missRate(centered, space, TapSpatialModel.NONE, 20_000, seed = 34)
            val after = missRate(centered, space, model, 20_000, seed = 34)

            assertThat(Math.abs(model.keyOffsetX(KeyKind.SPACE))).isLessThan(0.05f)
            assertThat(Math.abs(model.keyOffsetY(KeyKind.SPACE))).isLessThan(0.05f)
            assertThat(after).isLessThanOrEqualTo(before + 0.02)
        }

        @Test
        fun `seules les frappes tombées dans le rectangle de l'espace sont apprises`() {
            val (model, accepted) = train(biased, 600, seed = 35)

            assertThat(accepted).isLessThan(600)
            assertThat(model.sampleCount(KeyKind.SPACE)).isEqualTo(minOf(accepted, model.config.window))
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `avec maxBoundaryShift à zéro, le modèle appris ne change rien`() {
            val off = TapDecoderConfig(maxBoundaryShift = 0f)
            val (model, _) = train(biased, 600, seed = 36, config = off)

            val before = missRate(biased, space, TapSpatialModel.NONE, 5_000, seed = 37, config = off)
            val after = missRate(biased, space, model, 5_000, seed = 37, config = off)

            assertThat(after).isEqualTo(before)
        }

        @Test
        fun `sans aucune frappe apprise, le taux d'échec est celui du modèle vide`() {
            val before = missRate(biased, space, TapSpatialModel.NONE, 5_000, seed = 38)
            val after = missRate(biased, space, SpatialModel(), 5_000, seed = 38)

            assertThat(after).isEqualTo(before)
        }
    }
}
