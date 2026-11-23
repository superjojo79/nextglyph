package nextglyph.engine.predict

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class PredictionSettingsTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `profil texte par défaut - tout actif`() {
            val s = PredictionSettings()

            assertThat(s.suggestionsEnabled).isTrue()
            assertThat(s.autocommitEnabled).isTrue()
            assertThat(s.learningEnabled).isTrue()
            assertThat(s.prudence).isEqualTo(Prudence.NORMAL)
            assertThat(PredictionSettings(profile = FieldProfile.PERSON_NAME).autocommitEnabled).isTrue()
        }

        @Test
        fun `prudence - seuils 0,8 0,7 0,6`() {
            assertThat(Prudence.entries.map { it.minProb }).containsExactly(0.80, 0.70, 0.60)
            assertThat(Prudence.entries.map { it.minRatio }).containsExactly(3.0, 2.0, 1.5)
        }

        @Test
        fun `mémoire des refus - compte par paire, sans tenir compte de la casse`() {
            val store = InMemoryRejectionStore()

            store.recordRejection("Cest", "C'est")
            store.recordRejection("cest", "c'est")

            assertThat(store.rejections("cest", "c'est")).isEqualTo(2)
            assertThat(store.rejections("cest", "cent")).isZero()
            store.clear()
            assertThat(store.rejections("cest", "c'est")).isZero()
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @EnumSource(value = FieldProfile::class, names = ["PASSWORD", "EMAIL", "URL", "NUMERIC"])
        fun `profils sans suggestions ni validation`(profile: FieldProfile) {
            val s = PredictionSettings(profile = profile)

            assertThat(s.suggestionsEnabled).isFalse()
            assertThat(s.autocommitEnabled).isFalse()
        }

        @Test
        fun `mot de passe et incognito - aucun apprentissage`() {
            assertThat(PredictionSettings(profile = FieldProfile.PASSWORD).learningEnabled).isFalse()
            assertThat(PredictionSettings(incognito = true).learningEnabled).isFalse()
            assertThat(PredictionSettings(profile = FieldProfile.EMAIL).learningEnabled).isTrue()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `NO_SUGGESTIONS et réglage utilisateur`() {
            assertThat(PredictionSettings(noSuggestions = true).suggestionsEnabled).isFalse()
            assertThat(PredictionSettings(noSuggestions = true).autocommitEnabled).isFalse()
            assertThat(PredictionSettings(autocommit = false).suggestionsEnabled).isTrue()
            assertThat(PredictionSettings(autocommit = false).autocommitEnabled).isFalse()
        }

        @Test
        fun `configuration - valeurs par défaut et bornes`() {
            val c = PredictionConfig()

            assertThat(c.spatialSigma).isEqualTo(0.35)
            assertThat(c.spatialScale).isEqualTo(0.6)
            assertThat(c.rejectLimit).isEqualTo(2)
            assertThat(c.languageWindow).isEqualTo(4)
            assertThat(c.correction).isEqualTo(CorrectionWeights(0.01, 0.0005, 0.002, 0.002, 0.002, 0.5))
            assertThat(PredictionConfig(accentDominance = 1.0, spatialScale = 1.0, preferredLanguageShare = 0.0).accentDominance).isEqualTo(1.0)
            for (bad in listOf<() -> Any>(
                { PredictionConfig(neighborDistance = 0.0) },
                { PredictionConfig(formsPerPrefix = 0) },
                { PredictionConfig(spatialSigma = 0.0) },
                { PredictionConfig(spatialScale = 0.0) },
                { PredictionConfig(spatialScale = 1.01) },
                { PredictionConfig(rejectLimit = 0) },
                { PredictionConfig(languageWindow = 0) },
                { PredictionConfig(languagePrior = 0.0) },
                { PredictionConfig(preferredLanguageShare = 1.01) },
                { PredictionConfig(accentDominance = 0.5) },
                { PredictionConfig(accentDominance = 1.01) },
                { CorrectionWeights(separator = 1.01) },
                { CorrectionWeights(omission = -0.1) },
            )) {
                assertThatThrownBy { bad() }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }
}
