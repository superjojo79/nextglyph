package nextglyph.tools.lexicons

import nextglyph.tools.wordlists.WordEntry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WordListTsvTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `lit des paires forme et fréquence dans l'ordre`() {
            val entries = WordListTsv.parse(listOf("école\t1200", "l'\t5", "Paris\t9223372036854775807"), "fr.tsv")

            assertThat(entries).containsExactly(
                WordEntry("école", 1200L), WordEntry("l'", 5L), WordEntry("Paris", Long.MAX_VALUE),
            )
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `ligne sans tabulation - numéro de ligne et contenu cités`() {
            assertThatThrownBy { WordListTsv.parse(listOf("a\t1", "abc"), "fr.tsv") }
                .isInstanceOf(LexiconToolException::class.java)
                .hasMessage("fr.tsv, ligne 2 : deux champs séparés par une tabulation attendus (« abc »).")
        }

        @Test
        fun `ligne avec trois champs`() {
            assertThatThrownBy { WordListTsv.parse(listOf("a\t1\t2"), "x") }
                .isInstanceOf(LexiconToolException::class.java).hasMessageContaining("x, ligne 1")
        }

        @Test
        fun `ligne vide`() {
            assertThatThrownBy { WordListTsv.parse(listOf(""), "x") }
                .isInstanceOf(LexiconToolException::class.java).hasMessageContaining("deux champs")
        }

        @Test
        fun `forme vide`() {
            assertThatThrownBy { WordListTsv.parse(listOf("\t3"), "x") }
                .isInstanceOf(LexiconToolException::class.java).hasMessage("x, ligne 1 : forme vide.")
        }

        @Test
        fun `fréquence non numérique`() {
            assertThatThrownBy { WordListTsv.parse(listOf("a\tbeaucoup"), "x") }
                .isInstanceOf(LexiconToolException::class.java)
                .hasMessage("x, ligne 1 : fréquence invalide « beaucoup » (entier strictement positif attendu).")
        }

        @Test
        fun `fréquence décimale`() {
            assertThatThrownBy { WordListTsv.parse(listOf("a\t1.5"), "x") }
                .isInstanceOf(LexiconToolException::class.java).hasMessageContaining("fréquence invalide")
        }

        @Test
        fun `fréquence nulle ou négative`() {
            assertThatThrownBy { WordListTsv.parse(listOf("a\t0"), "x") }
                .isInstanceOf(LexiconToolException::class.java).hasMessageContaining("« 0 »")
            assertThatThrownBy { WordListTsv.parse(listOf("a\t-4"), "x") }
                .isInstanceOf(LexiconToolException::class.java).hasMessageContaining("« -4 »")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `fichier vide - aucune entrée`() {
            assertThat(WordListTsv.parse(emptyList(), "x")).isEmpty()
        }

        @Test
        fun `fréquence minimale 1 acceptée`() {
            assertThat(WordListTsv.parse(listOf("a\t1"), "x")).containsExactly(WordEntry("a", 1L))
        }

        @Test
        fun `fréquence au-delà de Long - refusée`() {
            assertThatThrownBy { WordListTsv.parse(listOf("a\t9223372036854775808"), "x") }
                .isInstanceOf(LexiconToolException::class.java)
        }
    }
}
