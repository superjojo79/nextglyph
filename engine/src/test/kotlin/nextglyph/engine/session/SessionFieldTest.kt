package nextglyph.engine.session

import nextglyph.engine.edit.CapsMode
import nextglyph.engine.edit.TextProfile
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SessionFieldTest {

    private fun field(inputType: Int, imeOptions: Int = 0, autocommit: Boolean = true) =
        SessionField(InputProfileResolver.resolve(inputType, imeOptions, null), EnterBehavior.NewLine, autocommit)

    @Nested
    inner class HappyPath {
        @Test
        fun `texte avec majuscules de phrase - tout actif`() {
            val f = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES)

            assertThat(f.composes).isTrue()
            assertThat(f.readsText).isTrue()
            assertThat(f.adaptation).isTrue()
            assertThat(f.textProfile).isEqualTo(TextProfile.TEXT)
            assertThat(f.capsMode).isEqualTo(CapsMode.SENTENCES)
            assertThat(f.prediction.profile).isEqualTo(FieldProfile.TEXT)
            assertThat(f.prediction.learningEnabled).isTrue()
            assertThat(f.prediction.autocommitEnabled).isTrue()
            assertThat(f.shortcuts).isEmpty()
            assertThat(f.enter).isEqualTo(EnterBehavior.NewLine)
        }

        @Test
        fun `e-mail - raccourcis, lecture du texte, ni composition ni adaptation`() {
            val f = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

            assertThat(f.shortcuts).containsExactly("@", ".", ".com", ".fr", "-", "_")
            assertThat(f.readsText).isTrue()
            assertThat(f.composes).isFalse()
            assertThat(f.adaptation).isFalse()
            assertThat(f.textProfile).isEqualTo(TextProfile.EMAIL)
            assertThat(f.prediction.profile).isEqualTo(FieldProfile.EMAIL)
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(
            "${C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_URI}, URL, URL",
            "${C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME}, PERSON_NAME, PERSON_NAME",
            "${C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD}, PASSWORD, PASSWORD",
            "${C.TYPE_CLASS_NUMBER}, PASSWORD, NUMERIC",
            "${C.TYPE_CLASS_PHONE}, PASSWORD, NUMERIC",
            "${C.TYPE_CLASS_DATETIME}, PASSWORD, NUMERIC",
            "${C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD}, PASSWORD, PASSWORD",
        )
        fun `profils d'édition et de prédiction`(inputType: Int, text: TextProfile, prediction: FieldProfile) {
            val f = field(inputType)

            assertThat(f.textProfile).isEqualTo(text)
            assertThat(f.prediction.profile).isEqualTo(prediction)
        }

        @ParameterizedTest
        @CsvSource("${C.TYPE_TEXT_FLAG_CAP_WORDS}, WORDS", "${C.TYPE_TEXT_FLAG_CAP_CHARACTERS}, CHARACTERS", "0, NONE")
        fun `majuscules demandées par le champ`(flag: Int, mode: CapsMode) {
            assertThat(field(C.TYPE_CLASS_TEXT or flag).capsMode).isEqualTo(mode)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `mot de passe - aucune lecture, aucune composition, aucun apprentissage`() {
            val f = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD)

            assertThat(f.readsText).isFalse()
            assertThat(f.composes).isFalse()
            assertThat(f.adaptation).isFalse()
            assertThat(f.prediction.learningEnabled).isFalse()
            assertThat(f.prediction.incognito).isTrue()
        }

        @Test
        fun `incognito - aucun refus mémorisé, suggestions conservées`() {
            val f = field(C.TYPE_CLASS_TEXT, C.IME_FLAG_NO_PERSONALIZED_LEARNING)

            assertThat(f.prediction.incognito).isTrue()
            assertThat(f.prediction.learningEnabled).isFalse()
            assertThat(f.composes).isTrue()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `sans suggestions - pas de composition, adaptation conservée`() {
            val f = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_NO_SUGGESTIONS)

            assertThat(f.composes).isFalse()
            assertThat(f.adaptation).isTrue()
            assertThat(f.textProfile).isEqualTo(TextProfile.TEXT)
        }

        @Test
        fun `validation par espace désactivée par le réglage - suggestions conservées`() {
            val f = field(C.TYPE_CLASS_TEXT, autocommit = false)

            assertThat(f.composes).isTrue()
            assertThat(f.prediction.autocommitEnabled).isFalse()
        }

        @Test
        fun `mot suivant - réglage transmis aux réglages de prédiction`() {
            val on = SessionField(InputProfileResolver.resolve(C.TYPE_CLASS_TEXT, 0, null))
            val off = SessionField(InputProfileResolver.resolve(C.TYPE_CLASS_TEXT, 0, null), nextWord = false)

            assertThat(on.prediction.nextWordEnabled).isTrue()
            assertThat(off.prediction.nextWord).isFalse()
            assertThat(off.prediction.nextWordEnabled).isFalse()
            assertThat(off.composes).isTrue()
        }

        @Test
        fun `champ par défaut - texte ordinaire, touche Entrée`() {
            assertThat(SessionField.DEFAULT.textProfile).isEqualTo(TextProfile.TEXT)
            assertThat(SessionField.DEFAULT.enter).isEqualTo(EnterBehavior.EnterKey)
            assertThat(SessionField.DEFAULT.capsMode).isEqualTo(CapsMode.NONE)
        }
    }
}
