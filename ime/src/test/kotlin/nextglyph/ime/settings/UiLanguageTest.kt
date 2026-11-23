package nextglyph.ime.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UiLanguageTest {

    @Nested
    inner class HappyPath {

        @Test
        fun `les valeurs enregistrées system, french et english sont relues`() {
            assertThat(UiLanguage.fromStored("system")).isEqualTo(UiLanguage.SYSTEM)
            assertThat(UiLanguage.fromStored("french")).isEqualTo(UiLanguage.FRENCH)
            assertThat(UiLanguage.fromStored("english")).isEqualTo(UiLanguage.ENGLISH)
        }

        @Test
        fun `la valeur enregistrée est le nom en minuscules, comme les autres réglages`() {
            for (language in UiLanguage.entries) {
                assertThat(UiLanguage.fromStored(KeyboardSettings.storedName(language))).isEqualTo(language)
            }
        }

        @Test
        fun `les étiquettes fr et en donnent le français et l'anglais`() {
            assertThat(UiLanguage.fromTag("fr")).isEqualTo(UiLanguage.FRENCH)
            assertThat(UiLanguage.fromTag("en")).isEqualTo(UiLanguage.ENGLISH)
        }

        @Test
        fun `les étiquettes des langues imposées sont fr et en, celle du système est absente`() {
            assertThat(UiLanguage.SYSTEM.tag).isNull()
            assertThat(UiLanguage.FRENCH.tag).isEqualTo("fr")
            assertThat(UiLanguage.ENGLISH.tag).isEqualTo("en")
        }

        @Test
        fun `la clé des préférences est ui_language`() {
            assertThat(UiLanguage.KEY_UI_LANGUAGE).isEqualTo("ui_language")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `une valeur enregistrée absente donne la langue du système`() {
            assertThat(UiLanguage.fromStored(null)).isEqualTo(UiLanguage.SYSTEM)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", " ", "german", "fr", "en", "FRENCH", "auto"])
        fun `une valeur enregistrée inconnue donne la langue du système`(value: String) {
            assertThat(UiLanguage.fromStored(value)).isEqualTo(UiLanguage.SYSTEM)
        }

        @Test
        fun `une étiquette absente ou vide donne la langue du système`() {
            assertThat(UiLanguage.fromTag(null)).isEqualTo(UiLanguage.SYSTEM)
            assertThat(UiLanguage.fromTag("")).isEqualTo(UiLanguage.SYSTEM)
            assertThat(UiLanguage.fromTag("   ")).isEqualTo(UiLanguage.SYSTEM)
        }

        @ParameterizedTest
        @ValueSource(strings = ["de", "es-ES", "zh-Hans-CN", "und", "!!", "français"])
        fun `une étiquette d'une autre langue ou illisible donne la langue du système`(tag: String) {
            assertThat(UiLanguage.fromTag(tag)).isEqualTo(UiLanguage.SYSTEM)
        }
    }

    @Nested
    inner class BoundaryValues {

        @ParameterizedTest
        @ValueSource(strings = ["fr-CA", "fr-FR", "fr-BE", "fr-Latn-CH"])
        fun `les sous-étiquettes de région et d'écriture sont ignorées pour le français`(tag: String) {
            assertThat(UiLanguage.fromTag(tag)).isEqualTo(UiLanguage.FRENCH)
        }

        @ParameterizedTest
        @ValueSource(strings = ["en-US", "en-GB", "en-AU", "en-Latn-US"])
        fun `les sous-étiquettes de région et d'écriture sont ignorées pour l'anglais`(tag: String) {
            assertThat(UiLanguage.fromTag(tag)).isEqualTo(UiLanguage.ENGLISH)
        }

        @Test
        fun `la casse de l'étiquette est sans effet`() {
            assertThat(UiLanguage.fromTag("FR")).isEqualTo(UiLanguage.FRENCH)
            assertThat(UiLanguage.fromTag("En-us")).isEqualTo(UiLanguage.ENGLISH)
        }

        @Test
        fun `les espaces autour de l'étiquette sont ignorés`() {
            assertThat(UiLanguage.fromTag(" fr ")).isEqualTo(UiLanguage.FRENCH)
        }

        @Test
        fun `le trait de soulignement des locales Java n'est pas une étiquette BCP 47 valide`() {
            assertThat(UiLanguage.fromTag("fr_CA")).isEqualTo(UiLanguage.SYSTEM)
        }

        @Test
        fun `la valeur enregistrée est sensible à la casse`() {
            assertThat(UiLanguage.fromStored("French")).isEqualTo(UiLanguage.SYSTEM)
        }

        @Test
        fun `il y a exactement trois langues, la langue du système en premier`() {
            assertThat(UiLanguage.entries).containsExactly(UiLanguage.SYSTEM, UiLanguage.FRENCH, UiLanguage.ENGLISH)
        }
    }
}
