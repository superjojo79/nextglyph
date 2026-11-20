package nextglyph.ime.settings

import nextglyph.engine.adapt.AdaptationMode
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.predict.Prudence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyboardSettingsTest {

    private fun read(vararg values: Pair<String, Any?>) = KeyboardSettings.read(mapOf(*values)::get)

    @Nested
    inner class HappyPath {

        @Test
        fun `sans rien d'enregistré, les valeurs par défaut du §14`() {
            val settings = read()

            assertThat(settings).isEqualTo(KeyboardSettings())
            assertThat(settings.layout).isNull()
            assertThat(settings.languages).containsExactly("fr", "en")
            assertThat(settings.adaptationMode).isEqualTo(AdaptationMode.CALM_REINFORCED)
            assertThat(settings.rowHeightDp).isEqualTo(38)
            assertThat(settings.autocommit).isTrue()
            assertThat(settings.prudence).isEqualTo(Prudence.NORMAL)
            assertThat(settings.frenchTypography).isTrue()
            assertThat(settings.doubleSpacePeriod).isTrue()
            assertThat(settings.contacts).isFalse()
            assertThat(settings.haptics).isEqualTo(HapticLevel.MEDIUM)
            assertThat(settings.bottomMarginDp).isZero()
            assertThat(settings.wideSpace).isFalse()
        }

        @Test
        fun `toutes les valeurs enregistrées sont lues`() {
            val settings = read(
                KeyboardSettings.KEY_LAYOUT to "qwerty",
                KeyboardSettings.KEY_LANGUAGES to "en",
                KeyboardSettings.KEY_PREFERRED_LANGUAGE to "en",
                KeyboardSettings.KEY_ADAPTATION to "elastic",
                KeyboardSettings.KEY_ROW_HEIGHT to 44,
                KeyboardSettings.KEY_AUTOCOMMIT to false,
                KeyboardSettings.KEY_PRUDENCE to "bold",
                KeyboardSettings.KEY_FRENCH_TYPOGRAPHY to false,
                KeyboardSettings.KEY_DOUBLE_SPACE to false,
                KeyboardSettings.KEY_CONTACTS to true,
                KeyboardSettings.KEY_HAPTICS to "off",
                KeyboardSettings.KEY_BOTTOM_MARGIN to 24,
                KeyboardSettings.KEY_WIDE_SPACE to true,
            )

            assertThat(settings).isEqualTo(
                KeyboardSettings(
                    LayoutKind.QWERTY, listOf("en"), "en", AdaptationMode.ELASTIC, 44, false, Prudence.BOLD,
                    frenchTypography = false, doubleSpacePeriod = false, contacts = true, haptics = HapticLevel.OFF,
                    bottomMarginDp = 24, wideSpace = true,
                ),
            )
            assertThat(settings.editConfig.frenchTypography).isFalse()
            assertThat(settings.editConfig.doubleSpacePeriod).isFalse()
        }

        @Test
        fun `disposition selon la langue du système`() {
            assertThat(read().layoutFor("fr")).isEqualTo(LayoutKind.AZERTY)
            assertThat(read().layoutFor("de")).isEqualTo(LayoutKind.QWERTY)
            assertThat(read(KeyboardSettings.KEY_LAYOUT to "azerty").layoutFor("en")).isEqualTo(LayoutKind.AZERTY)
        }

        @Test
        fun `langue préférée choisie, sinon celle du sous-type`() {
            assertThat(read().preferredFor("en-US", "fr-FR")).isEqualTo("en")
            assertThat(read(KeyboardSettings.KEY_PREFERRED_LANGUAGE to "fr").preferredFor("en-US")).isEqualTo("fr")
            assertThat(read(KeyboardSettings.KEY_LANGUAGES to "fr").preferredFor("en-US")).isEqualTo("fr")
        }

        @Test
        fun `nom enregistré d'une énumération`() {
            assertThat(KeyboardSettings.storedName(AdaptationMode.CALM_REINFORCED)).isEqualTo("calm_reinforced")
            assertThat(KeyboardSettings.KEYS).hasSize(13).doesNotHaveDuplicates()
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `valeurs inconnues ou d'un autre type - valeurs par défaut`() {
            val settings = read(
                KeyboardSettings.KEY_LAYOUT to "dvorak",
                KeyboardSettings.KEY_LANGUAGES to 3,
                KeyboardSettings.KEY_PREFERRED_LANGUAGE to "de",
                KeyboardSettings.KEY_ADAPTATION to "ELASTIC",
                KeyboardSettings.KEY_ROW_HEIGHT to "40",
                KeyboardSettings.KEY_AUTOCOMMIT to "false",
                KeyboardSettings.KEY_HAPTICS to null,
                KeyboardSettings.KEY_BOTTOM_MARGIN to "24",
            )

            assertThat(settings).isEqualTo(KeyboardSettings())
        }

        @Test
        fun `construction directe invalide refusée`() {
            assertThatThrownBy { KeyboardSettings(rowHeightDp = 33) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { KeyboardSettings(bottomMarginDp = -1) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { KeyboardSettings(bottomMarginDp = 49) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { KeyboardSettings(languages = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { KeyboardSettings(languages = listOf("de")) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { KeyboardSettings(preferredLanguage = "de") }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {

        @ParameterizedTest
        @ValueSource(ints = [34, 46])
        fun `hauteurs extrêmes acceptées`(height: Int) {
            assertThat(read(KeyboardSettings.KEY_ROW_HEIGHT to height).rowHeightDp).isEqualTo(height)
        }

        @ParameterizedTest
        @ValueSource(ints = [33, 47, 0, -1])
        fun `hauteurs hors bornes ramenées au défaut`(height: Int) {
            assertThat(read(KeyboardSettings.KEY_ROW_HEIGHT to height).rowHeightDp).isEqualTo(38)
        }

        @ParameterizedTest
        @ValueSource(ints = [0, 48])
        fun `marges basses extrêmes acceptées`(margin: Int) {
            assertThat(read(KeyboardSettings.KEY_BOTTOM_MARGIN to margin).bottomMarginDp).isEqualTo(margin)
        }

        @ParameterizedTest
        @ValueSource(ints = [-1, 49])
        fun `marges basses hors bornes ramenées à zéro`(margin: Int) {
            assertThat(read(KeyboardSettings.KEY_BOTTOM_MARGIN to margin).bottomMarginDp).isZero()
        }

        @Test
        fun `deux langues explicitement`() {
            assertThat(read(KeyboardSettings.KEY_LANGUAGES to "both").languages).containsExactly("fr", "en")
            assertThat(read(KeyboardSettings.KEY_LANGUAGES to "fr").languages).containsExactly("fr")
        }

        @Test
        fun `préférée inactive - première langue active`() {
            val settings = read(KeyboardSettings.KEY_LANGUAGES to "en", KeyboardSettings.KEY_PREFERRED_LANGUAGE to "fr")

            assertThat(settings.preferredFor("fr-FR")).isEqualTo("en")
        }
    }
}
