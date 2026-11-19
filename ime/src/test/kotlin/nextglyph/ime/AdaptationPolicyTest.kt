package nextglyph.ime

import android.text.InputType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AdaptationPolicyTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Nested
    inner class HappyPath {

        @ParameterizedTest
        @ValueSource(
            ints = [
                InputType.TYPE_CLASS_TEXT,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            ],
        )
        fun `un champ de texte ordinaire est adapté`(inputType: Int) {
            assertThat(AdaptationPolicy.allowsAdaptation(inputType)).isTrue()
        }

        @Test
        fun `l'absence de suggestions garde l'adaptation (§4)`() {
            assertThat(AdaptationPolicy.allowsAdaptation(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)).isTrue()
            assertThat(AdaptationPolicy.allowsAdaptation(text or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(
            ints = [
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ],
        )
        fun `mot de passe, e-mail et adresse web ne sont ni adaptés ni lus`(inputType: Int) {
            assertThat(AdaptationPolicy.allowsAdaptation(inputType)).isFalse()
        }

        @ParameterizedTest
        @ValueSource(
            ints = [
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME,
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
            ],
        )
        fun `les champs numériques, téléphone et date ne sont pas adaptés`(inputType: Int) {
            assertThat(AdaptationPolicy.allowsAdaptation(inputType)).isFalse()
        }

        @Test
        fun `un drapeau ne rend pas un mot de passe adaptable`() {
            val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

            assertThat(AdaptationPolicy.allowsAdaptation(password)).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un type nul (terminal, champ sans type) est traité comme du texte`() {
            assertThat(AdaptationPolicy.allowsAdaptation(InputType.TYPE_NULL)).isTrue()
        }

        @Test
        fun `les drapeaux d'un champ numérique ne changent rien`() {
            assertThat(AdaptationPolicy.allowsAdaptation(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)).isFalse()
        }
    }
}
