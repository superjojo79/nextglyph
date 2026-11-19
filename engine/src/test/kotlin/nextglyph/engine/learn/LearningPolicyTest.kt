package nextglyph.engine.learn

import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.PredictionSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LearningPolicyTest {

    @Nested
    inner class HappyPath {

        @ParameterizedTest
        @EnumSource(value = FieldProfile::class, names = ["TEXT", "PERSON_NAME"])
        fun `les mots s'apprennent dans les champs de texte et de nom`(profile: FieldProfile) {
            assertThat(LearningPolicy.allowsWords(PredictionSettings(profile = profile))).isTrue()
        }

        @ParameterizedTest
        @EnumSource(value = FieldProfile::class, names = ["TEXT", "PERSON_NAME", "EMAIL", "URL"])
        fun `les frappes s'apprennent là où l'on tape des lettres`(profile: FieldProfile) {
            assertThat(LearningPolicy.allowsTaps(PredictionSettings(profile = profile))).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @EnumSource(FieldProfile::class)
        fun `rien ne s'apprend en incognito`(profile: FieldProfile) {
            val settings = PredictionSettings(profile = profile, incognito = true)

            assertThat(LearningPolicy.allowsWords(settings)).isFalse()
            assertThat(LearningPolicy.allowsTaps(settings)).isFalse()
        }

        @Test
        fun `rien ne s'apprend en mot de passe`() {
            val settings = PredictionSettings(profile = FieldProfile.PASSWORD)

            assertThat(LearningPolicy.allowsWords(settings)).isFalse()
            assertThat(LearningPolicy.allowsTaps(settings)).isFalse()
        }

        @ParameterizedTest
        @EnumSource(value = FieldProfile::class, names = ["EMAIL", "URL", "NUMERIC"])
        fun `aucun mot n'est appris en e-mail, URL ou champ numérique`(profile: FieldProfile) {
            assertThat(LearningPolicy.allowsWords(PredictionSettings(profile = profile))).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `NO_SUGGESTIONS interdit les mots mais pas les frappes`() {
            val settings = PredictionSettings(noSuggestions = true)

            assertThat(LearningPolicy.allowsWords(settings)).isFalse()
            assertThat(LearningPolicy.allowsTaps(settings)).isTrue()
        }

        @Test
        fun `les pavés numériques n'apprennent aucune frappe`() {
            assertThat(LearningPolicy.allowsTaps(PredictionSettings(profile = FieldProfile.NUMERIC))).isFalse()
        }
    }
}
