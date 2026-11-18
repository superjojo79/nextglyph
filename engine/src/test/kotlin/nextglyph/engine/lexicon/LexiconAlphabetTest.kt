package nextglyph.engine.lexicon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class LexiconAlphabetTest {

    @Nested
    inner class HappyPath {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource("a, 2", "m, 14", "'-', 1")
        fun `donne l'index d'un caractère de l'alphabet`(c: Char, expected: Int) {
            assertThat(LexiconAlphabet.indexOf(c)).isEqualTo(expected)
        }

        @Test
        fun `l'index de chaque caractère est sa position dans CHARS`() {
            val indexes = LexiconAlphabet.CHARS.map(LexiconAlphabet::indexOf)

            assertThat(indexes).isEqualTo((0 until LexiconAlphabet.SIZE).toList())
        }

        @Test
        fun `accepte une clé avec apostrophe et trait d'union`() {
            assertThat(LexiconAlphabet.isValidKey("aujourd'hui-peut-etre")).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(chars = ['A', 'é', '`', '{', '&', '0', ' ', 'ß'])
        fun `renvoie -1 pour un caractère hors de l'alphabet`(c: Char) {
            assertThat(LexiconAlphabet.indexOf(c)).isEqualTo(-1)
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["3d", "strasse!", "école", "Chat"])
        fun `refuse une clé contenant un caractère hors de l'alphabet`(key: String) {
            assertThat(LexiconAlphabet.isValidKey(key)).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `l'alphabet compte 28 caractères`() {
            assertThat(LexiconAlphabet.SIZE).isEqualTo(28)
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource("'''', 0", "z, 27")
        fun `donne le premier et le dernier index`(c: Char, expected: Int) {
            assertThat(LexiconAlphabet.indexOf(c)).isEqualTo(expected)
        }

        @Test
        fun `l'ordre des index coïncide avec l'ordre naturel des chaînes`() {
            assertThat(LexiconAlphabet.CHARS.toList().sorted().joinToString("")).isEqualTo(LexiconAlphabet.CHARS)
        }

        @Test
        fun `accepte la clé vide`() {
            assertThat(LexiconAlphabet.isValidKey("")).isTrue()
        }
    }
}
