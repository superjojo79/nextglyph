package nextglyph.engine.adapt

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AdaptationConfigTest {

    private fun rejects(message: String, build: () -> Any) {
        assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `valeurs par défaut de la spec`() {
            val config = AdaptationConfig()

            assertThat(config.mode).isEqualTo(AdaptationMode.CALM_REINFORCED)
            assertThat(config.maxDisplacement).isEqualTo(0.5f)
            assertThat(config.minOpacity).isEqualTo(0.30f)
            assertThat(config.opacityBase).isEqualTo(0.55f)
            assertThat(config.opacitySlope).isEqualTo(0.45f)
            assertThat(config.minWidth).isEqualTo(0.6f)
            assertThat(config.widthBase).isEqualTo(0.8f)
            assertThat(config.wordStartIntensity).isEqualTo(0.5f)
            assertThat(config.animationMs).isEqualTo(100L)
            assertThat(config.fastTypingMs).isEqualTo(120L)
            assertThat(config.decoder.alpha).isEqualTo(0.5f)
            assertThat(config.decoder.lambda).isEqualTo(0.6f)
        }

        @Test
        fun `valeurs choisies, documentées dans DECISIONS`() {
            val config = AdaptationConfig()

            assertThat(config.gain).isEqualTo(0.4f)
            assertThat(config.spaceGain).isEqualTo(0.6f)
            assertThat(config.calmReinforcedDisplacement).isEqualTo(0.15f)
            assertThat(config.minLabelScale).isEqualTo(0.7f)
            assertThat(config.correctionIntensity).isEqualTo(0.5f)
            assertThat(config.easeOutPower).isEqualTo(2f)
            assertThat(config.decoder.sigma).isEqualTo(0.35f)
            assertThat(config.decoder.minPrior).isEqualTo(0.05f)
            assertThat(config.decoder.coreFraction).isEqualTo(0.5f)
            assertThat(config.decoder.maxBoundaryShift).isEqualTo(0.2f)
        }

        @Test
        fun `profil et plafond de chaque mode`() {
            val config = AdaptationConfig(maxDisplacement = 0.4f, calmReinforcedDisplacement = 0.1f)

            assertThat(config.profile(AdaptationMode.OFF)).isEqualTo(ModeProfile.NONE)
            assertThat(config.profile(AdaptationMode.CALM)).isEqualTo(ModeProfile(0.5f, 0f))
            assertThat(config.profile(AdaptationMode.CALM_REINFORCED)).isEqualTo(ModeProfile(0.8f, 0.4f))
            assertThat(config.profile(AdaptationMode.ELASTIC)).isEqualTo(ModeProfile(1f, 1f))
            assertThat(config.profile()).isEqualTo(config.calmReinforced)
            assertThat(config.displacement(AdaptationMode.OFF)).isEqualTo(0f)
            assertThat(config.displacement(AdaptationMode.CALM)).isEqualTo(0f)
            assertThat(config.displacement(AdaptationMode.CALM_REINFORCED)).isEqualTo(0.1f)
            assertThat(config.displacement(AdaptationMode.ELASTIC)).isEqualTo(0.4f)
            assertThat(config.displacement()).isEqualTo(0.1f)
        }

        @Test
        fun `le décodeur spatial a lambda nul`() {
            assertThat(TapDecoderConfig.SPATIAL.lambda).isEqualTo(0f)
            assertThat(TapDecoderConfig.SPATIAL.alpha).isEqualTo(0.5f)
            assertThat(TapDecoderConfig.SPATIAL.maxBoundaryShift).isEqualTo(0.2f)
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(floats = [-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette une fraction hors de 0 à 1`(value: Float) {
            rejects("maxDisplacement") { AdaptationConfig(maxDisplacement = value) }
            rejects("calmReinforcedDisplacement") { AdaptationConfig(calmReinforcedDisplacement = value) }
            rejects("minOpacity") { AdaptationConfig(minOpacity = value) }
            rejects("opacityBase") { AdaptationConfig(opacityBase = value, opacitySlope = 0f) }
            rejects("opacitySlope") { AdaptationConfig(opacitySlope = value, opacityBase = 0f) }
            rejects("wordStartIntensity") { AdaptationConfig(wordStartIntensity = value) }
            rejects("correctionIntensity") { AdaptationConfig(correctionIntensity = value) }
            rejects("correctionDeletionWeight") { AdaptationConfig(correctionDeletionWeight = value) }
            rejects("correctionInsertionWeight") { AdaptationConfig(correctionInsertionWeight = value) }
            rejects("opacityStrength") { ModeProfile(value, 0f) }
            rejects("labelStrength") { ModeProfile(0f, value) }
            rejects("alpha") { TapDecoderConfig(alpha = value) }
            rejects("coreFraction") { TapDecoderConfig(coreFraction = value) }
        }

        @ParameterizedTest
        @ValueSource(floats = [-0.01f, 0.51f, 1f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY])
        fun `rejette un déplacement maximal de frontière hors de 0 à 0,5`(value: Float) {
            rejects("maxBoundaryShift") { TapDecoderConfig(maxBoundaryShift = value) }
        }

        @Test
        fun `rejette une opacité de formule supérieure à 1`() {
            rejects("au plus 1") { AdaptationConfig(opacityBase = 0.6f, opacitySlope = 0.45f) }
        }

        @ParameterizedTest
        @ValueSource(floats = [0f, -1f, 1.01f, Float.NaN])
        fun `rejette une largeur minimale ou un libellé minimal hors de 0 exclu à 1`(value: Float) {
            rejects("minWidth") { AdaptationConfig(minWidth = value) }
            rejects("minLabelScale") { AdaptationConfig(minLabelScale = value) }
            rejects("minPrior") { TapDecoderConfig(minPrior = value) }
        }

        @ParameterizedTest
        @ValueSource(floats = [-0.01f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY])
        fun `rejette un paramètre négatif ou non fini`(value: Float) {
            rejects("gain") { AdaptationConfig(gain = value) }
            rejects("spaceGain") { AdaptationConfig(spaceGain = value) }
            rejects("correctionNeighborRadius") { AdaptationConfig(correctionNeighborRadius = value) }
            rejects("lambda") { TapDecoderConfig(lambda = value) }
        }

        @ParameterizedTest
        @ValueSource(floats = [0f, -1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette un paramètre qui doit être strictement positif`(value: Float) {
            rejects("widthBase") { AdaptationConfig(widthBase = value) }
            rejects("correctionAdjacencySigma") { AdaptationConfig(correctionAdjacencySigma = value) }
            rejects("sigma") { TapDecoderConfig(sigma = value) }
        }

        @Test
        fun `rejette des durées, une courbe ou un nombre de candidats invalides`() {
            rejects("animationMs") { AdaptationConfig(animationMs = -1L) }
            rejects("fastTypingMs") { AdaptationConfig(fastTypingMs = -1L) }
            rejects("easeOutPower") { AdaptationConfig(easeOutPower = 0.99f) }
            rejects("easeOutPower") { AdaptationConfig(easeOutPower = Float.NaN) }
            rejects("easeOutPower") { AdaptationConfig(easeOutPower = Float.POSITIVE_INFINITY) }
            rejects("correctionMaxCandidates") { AdaptationConfig(correctionMaxCandidates = -1) }
            rejects("gain") { AdaptationConfig(gain = Float.POSITIVE_INFINITY) }
            rejects("lambda") { TapDecoderConfig(lambda = Float.POSITIVE_INFINITY) }
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `accepte un déplacement de frontière de 0 (désactivé) et de 0,5`() {
            assertThat(TapDecoderConfig(maxBoundaryShift = 0f).maxBoundaryShift).isZero()
            assertThat(TapDecoderConfig(maxBoundaryShift = 0.5f).maxBoundaryShift).isEqualTo(0.5f)
        }

        @Test
        fun `accepte les bornes incluses`() {
            AdaptationConfig(
                maxDisplacement = 1f, calmReinforcedDisplacement = 0f, minOpacity = 0f, opacityBase = 1f, opacitySlope = 0f,
                minLabelScale = 1f, minWidth = 1f, gain = 0f, spaceGain = 0f, wordStartIntensity = 1f, correctionIntensity = 0f,
                correctionNeighborRadius = 0f, correctionDeletionWeight = 1f, correctionInsertionWeight = 0f,
                correctionMaxCandidates = 0, animationMs = 0L, easeOutPower = 1f, fastTypingMs = 0L,
                decoder = TapDecoderConfig(alpha = 0f, lambda = 0f, minPrior = 1f, coreFraction = 1f, maxBoundaryShift = 0.5f),
            )
            AdaptationConfig(spaceGain = AdaptationConfig.MAX_SPACE_GAIN, opacityBase = 0f, opacitySlope = 1f)
            ModeProfile(1f, 1f)
        }

        @Test
        fun `rejette une espace au-delà de sa croissance maximale`() {
            rejects("spaceGain") { AdaptationConfig(spaceGain = AdaptationConfig.MAX_SPACE_GAIN + 0.01f) }
        }

        @Test
        fun `la configuration est une valeur`() {
            assertThat(AdaptationConfig()).isEqualTo(AdaptationConfig())
            assertThat(AdaptationConfig().copy(mode = AdaptationMode.ELASTIC).mode).isEqualTo(AdaptationMode.ELASTIC)
        }
    }
}
