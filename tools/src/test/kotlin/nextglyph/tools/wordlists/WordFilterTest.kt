package nextglyph.tools.wordlists

import nextglyph.tools.wordlists.WordFilter.Rejection
import nextglyph.tools.wordlists.WordFilter.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class WordFilterTest {

    @Nested
    inner class HappyPath {
        @ParameterizedTest
        @CsvSource("école,ecole", "œuf,oeuf", "peut-être,peut-etre", "Tom,tom")
        fun `accepte une forme et donne sa clé normalisée`(form: String, key: String) {
            assertThat(WordFilter.check(form)).isEqualTo(Verdict.Accepted(key))
        }

        @Test
        fun `la clé garde l'apostrophe droite d'une forme typographique`() {
            assertThat(WordFilter.check("Aujourd’hui")).isEqualTo(Verdict.Accepted("aujourd'hui"))
            assertThat(WordFilter.check("I'm")).isEqualTo(Verdict.Accepted("i'm"))
        }

        @Test
        fun `ramène les apostrophes typographiques à l'apostrophe droite`() {
            assertThat(canonicalApostrophes("l’école ʼx")).isEqualTo("l'école 'x")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `refuse ce que normalize refuse`() {
            assertThat(WordFilter.check("")).isEqualTo(Verdict.Rejected(Rejection.NORMALIZER_REFUSED))
            assertThat(WordFilter.check("a b")).isEqualTo(Verdict.Rejected(Rejection.NORMALIZER_REFUSED))
            assertThat(WordFilter.check("́")).isEqualTo(Verdict.Rejected(Rejection.NORMALIZER_REFUSED))
        }

        @ParameterizedTest
        @ValueSource(strings = ["abc1", "can't!", "straße", "€", "a.b", "x_y", "日本"])
        fun `refuse un caractère hors a-z apostrophe trait d'union`(form: String) {
            assertThat(WordFilter.check(form)).isEqualTo(Verdict.Rejected(Rejection.OUT_OF_ALPHABET))
        }

        @ParameterizedTest
        @ValueSource(strings = ["-", "'", "-'-"])
        fun `refuse une clé sans lettre`(form: String) {
            assertThat(WordFilter.check(form)).isEqualTo(Verdict.Rejected(Rejection.NO_LETTER))
        }

        @Test
        fun `chaque motif de rejet a un libellé distinct et non vide`() {
            val labels = Rejection.entries.map { it.label }
            assertThat(labels).doesNotContain("").doesNotHaveDuplicates()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte 64 caractères et refuse 65`() {
            assertThat(WordFilter.check("a".repeat(64))).isEqualTo(Verdict.Accepted("a".repeat(64)))
            assertThat(WordFilter.check("a".repeat(65))).isEqualTo(Verdict.Rejected(Rejection.NORMALIZER_REFUSED))
        }

        @Test
        fun `une seule lettre suffit, même précédée d'une apostrophe ou d'un trait d'union`() {
            assertThat(WordFilter.check("a")).isEqualTo(Verdict.Accepted("a"))
            assertThat(WordFilter.check("'tis")).isEqualTo(Verdict.Accepted("'tis"))
            assertThat(WordFilter.check("-z")).isEqualTo(Verdict.Accepted("-z"))
            assertThat(WordFilter.check("z")).isEqualTo(Verdict.Accepted("z"))
        }

        @Test
        fun `les bornes a et z de l'alphabet sont acceptées`() {
            assertThat(WordFilter.check("az")).isEqualTo(Verdict.Accepted("az"))
            assertThat(WordFilter.check("`")).isEqualTo(Verdict.Rejected(Rejection.OUT_OF_ALPHABET))
            assertThat(WordFilter.check("{")).isEqualTo(Verdict.Rejected(Rejection.OUT_OF_ALPHABET))
        }
    }
}
