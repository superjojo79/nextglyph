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
        fun `les fichiers des lexiques embarqués, le français séparé par licence`() {
            assertThat(LexiconLanguage.files("fr")).containsExactly(
                LexiconFile("fr-lexique.nglx", "fr", caseless = true),
                LexiconFile("fr-dicollecte.nglx", "fr", caseless = false),
            )
            assertThat(LexiconLanguage.files("en")).containsExactly(LexiconFile("en.nglx", "en"))
            assertThat(LexiconLanguage.ALL_FILES.map { it.baseName }).containsExactly("fr-lexique", "fr-dicollecte", "en")
            assertThat(LexiconLanguage.SUPPORTED).containsExactly("fr", "en")
        }

        @Test
        fun `assemble les fichiers d'une langue en déclarant le fichier sans casse`() {
            val lexique = LexiconFixtures.lexicon(listOf("marseille" to 300L), "fr")
            val dicollecte = LexiconFixtures.lexicon(listOf("Marseille" to 1L), "fr")

            val set = LexiconLanguage.assemble("fr", listOf(lexique, dicollecte))

            assertThat(set.caseless).containsExactly(lexique)
            assertThat(set.displayForms("marseille")).containsExactly(LexiconEntry("Marseille", "marseille", 301L))
            assertThat(LexiconLanguage.assemble("en", listOf(lexique)).caseless).isEmpty()
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(strings = ["de", "fra", "FR ", ""])
        fun `refuse les fichiers d'une langue inconnue`(language: String) {
            assertThatThrownBy { LexiconLanguage.files(language) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("inconnue")
        }

        @Test
        fun `refuse d'assembler un nombre de lexiques différent du nombre de fichiers`() {
            val one = LexiconFixtures.lexicon(listOf("de" to 1L), "fr")

            assertThatThrownBy { LexiconLanguage.assemble("fr", listOf(one)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("2 lexiques attendus")
            assertThatThrownBy { LexiconLanguage.assemble("en", listOf(one, one)) }
                .isInstanceOf(IllegalArgumentException::class.java)
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

        @Test
        fun `nom de base sans extension, inchangé sans extension`() {
            assertThat(LexiconFile("x.nglx", "fr").baseName).isEqualTo("x")
            assertThat(LexiconFile("x", "fr").baseName).isEqualTo("x")
            assertThat(LexiconFile.EXTENSION).isEqualTo(".nglx")
        }
    }
}
