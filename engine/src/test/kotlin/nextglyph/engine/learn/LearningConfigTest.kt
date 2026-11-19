package nextglyph.engine.learn

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LearningConfigTest {

    private fun rejects(message: String, build: () -> Any) {
        assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `valeurs par défaut documentées`() {
            val config = LearningConfig()

            assertThat(config.minOccurrences).isEqualTo(2)
            assertThat(config.minWordLength).isEqualTo(2)
            assertThat(config.maxWordLength).isEqualTo(32)
            assertThat(config.maxWords).isEqualTo(2000)
            assertThat(config.maxPending).isEqualTo(500)
            assertThat(config.maxFormsPerWord).isEqualTo(3)
            assertThat(config.maxCount).isEqualTo(1_000_000)
            assertThat(config.baseShare).isEqualTo(1e-5)
            assertThat(config.maxUsageBoost).isEqualTo(4.0)
            assertThat(config.maxRejections).isEqualTo(2000)
            assertThat(config.maxRejectionCount).isEqualTo(1000)
            assertThat(config.maxImportLines).isEqualTo(50_000)
            assertThat(config.maxImportBytes).isEqualTo(2 * 1024 * 1024)
            assertThat(config.maxFileBytes).isEqualTo(4 * 1024 * 1024)
        }

        @Test
        fun `valeurs par défaut du modèle spatial`() {
            val spatial = SpatialConfig()

            assertThat(spatial.window).isEqualTo(300)
            assertThat(spatial.priorWeight).isEqualTo(20.0)
            assertThat(spatial.maxOffset).isEqualTo(0.25)
            assertThat(spatial.maxSampleOffset).isEqualTo(0.5)
            assertThat(spatial.expectedSpread).isEqualTo(0.23)
            assertThat(spatial.minSigmaScale).isEqualTo(0.7)
            assertThat(spatial.maxSigmaScale).isEqualTo(1.6)
            assertThat(spatial.maxStaged).isEqualTo(64)
            assertThat(LearningConfig().spatial).isEqualTo(spatial)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette les valeurs invalides de l'apprentissage`() {
            rejects("minOccurrences") { LearningConfig(minOccurrences = 0) }
            rejects("minWordLength") { LearningConfig(minWordLength = 0) }
            rejects("maxWordLength") { LearningConfig(minWordLength = 3, maxWordLength = 2) }
            rejects("maxWords") { LearningConfig(maxWords = 0) }
            rejects("maxPending") { LearningConfig(maxPending = 0) }
            rejects("maxFormsPerWord") { LearningConfig(maxFormsPerWord = 0) }
            rejects("maxCount") { LearningConfig(minOccurrences = 3, maxCount = 2) }
            rejects("baseShare") { LearningConfig(baseShare = 0.0) }
            rejects("baseShare") { LearningConfig(baseShare = 1.1) }
            rejects("maxUsageBoost") { LearningConfig(maxUsageBoost = 0.9) }
            rejects("maxUsageBoost") { LearningConfig(maxUsageBoost = Double.POSITIVE_INFINITY) }
            rejects("maxRejections") { LearningConfig(maxRejections = 0) }
            rejects("maxRejectionCount") { LearningConfig(maxRejectionCount = 0) }
            rejects("maxImportLines") { LearningConfig(maxImportLines = 0) }
            rejects("maxImportBytes") { LearningConfig(maxImportBytes = 0) }
            rejects("maxFileBytes") { LearningConfig(maxFileBytes = 0) }
        }

        @Test
        fun `rejette les valeurs invalides du modèle spatial`() {
            rejects("fenêtre") { SpatialConfig(window = 0) }
            rejects("poids") { SpatialConfig(priorWeight = -1.0) }
            rejects("poids") { SpatialConfig(priorWeight = Double.NaN) }
            rejects("maxOffset") { SpatialConfig(maxOffset = -0.1) }
            rejects("maxOffset") { SpatialConfig(maxOffset = 0.51) }
            rejects("maxSampleOffset") { SpatialConfig(maxSampleOffset = -0.1) }
            rejects("maxSampleOffset") { SpatialConfig(maxSampleOffset = 0.51) }
            rejects("expectedSpread") { SpatialConfig(expectedSpread = 0.0) }
            rejects("expectedSpread") { SpatialConfig(expectedSpread = Double.POSITIVE_INFINITY) }
            rejects("minSigmaScale") { SpatialConfig(minSigmaScale = 0.0) }
            rejects("minSigmaScale") { SpatialConfig(minSigmaScale = 1.1) }
            rejects("maxSigmaScale") { SpatialConfig(maxSigmaScale = 0.9) }
            rejects("maxSigmaScale") { SpatialConfig(maxSigmaScale = Double.POSITIVE_INFINITY) }
            rejects("frappe en attente") { SpatialConfig(maxStaged = 0) }
        }
    }

    @Nested
    inner class BoundaryValues {

        @ParameterizedTest
        @ValueSource(ints = [1, 2, 100])
        fun `accepte les minimums exacts`(minimum: Int) {
            val config = LearningConfig(minOccurrences = minimum, maxCount = minimum, minWordLength = 1, maxWordLength = 1, maxWords = minimum)

            assertThat(config.minOccurrences).isEqualTo(minimum)
        }

        @Test
        fun `accepte les bornes exactes`() {
            LearningConfig(baseShare = 1.0, maxUsageBoost = 1.0)
            SpatialConfig(maxOffset = 0.0, maxSampleOffset = 0.5, minSigmaScale = 1.0, maxSigmaScale = 1.0, priorWeight = 0.0, maxStaged = 1, window = 1)
            SpatialConfig(maxOffset = 0.5, maxSampleOffset = 0.0)
        }
    }
}
