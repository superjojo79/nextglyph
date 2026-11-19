package nextglyph.engine.lexicon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class LexiconLanguageTest {

    @Nested
    inner class HappyPath {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource("fr, fr", "fr_FR, fr", "fr-CA, fr", "FR_be, fr", "en, en", "en_US, en", "en-GB, en")
        fun `choisit la langue d'une locale prise en charge`(tag: String, expected: String) {
            assertThat(LexiconLanguage.choose(tag)).isEqualTo(expected)
        }

        @Test
        fun `le premier choix pris en charge l'emporte`() {
            assertThat(LexiconLanguage.choose("en_US", "fr_FR")).isEqualTo("en")
            assertThat(LexiconLanguage.choose("fr_FR", "en_US")).isEqualTo("fr")
        }

        @Test
        fun `une langue non prise en charge cède la place à la suivante`() {
            assertThat(LexiconLanguage.choose("de_DE", null, "", "fr")).isEqualTo("fr")
        }

        @Test
        fun `les noms de fichiers des lexiques embarqués`() {
            assertThat(LexiconLanguage.fileName("fr")).isEqualTo("fr.nglx")
            assertThat(LexiconLanguage.fileName("en")).isEqualTo("en.nglx")
            assertThat(LexiconLanguage.SUPPORTED).containsExactly("fr", "en")
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(strings = ["de", "fra", "FR ", ""])
        fun `refuse un nom de fichier pour une langue inconnue`(language: String) {
            assertThatThrownBy { LexiconLanguage.fileName(language) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("inconnue")
        }

        @Test
        fun `aucune langue prise en charge, l'anglais`() {
            assertThat(LexiconLanguage.choose("de_DE", "ja")).isEqualTo("en")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `sans aucune locale, l'anglais`() {
            assertThat(LexiconLanguage.choose()).isEqualTo("en")
            assertThat(LexiconLanguage.choose(null)).isEqualTo("en")
            assertThat(LexiconLanguage.choose("", null)).isEqualTo("en")
        }

        @Test
        fun `un code de trois lettres n'est pas le français`() {
            assertThat(LexiconLanguage.choose("fra")).isEqualTo("en")
            assertThat(LexiconLanguage.choose("f")).isEqualTo("en")
        }

        @Test
        fun `le séparateur est le trait d'union ou le soulignement`() {
            assertThat(LexiconLanguage.choose("fr-")).isEqualTo("fr")
            assertThat(LexiconLanguage.choose("fr_")).isEqualTo("fr")
        }
    }
}
