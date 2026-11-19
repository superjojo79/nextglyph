package nextglyph.tools.analysis

import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.Tokenizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CorpusWordsTest {

    private val fr = Tokenizer(Language.FR)

    @Nested
    inner class HappyPath {
        @Test
        fun `compte les clés normalisées, triées par occurrences puis clé`() {
            val counts = CorpusWords.count(sequenceOf("L'école, l'École !", "Bonjour l'ami"), fr)

            assertThat(counts.counts.toList()).containsExactly("l'" to 3L, "ecole" to 2L, "ami" to 1L, "bonjour" to 1L)
            assertThat(counts.tokens).isEqualTo(7)
            assertThat(counts.rejected).isZero()
            assertThat(counts.analyzed).isEqualTo(7)
        }

        @Test
        fun `keys rend une clé par mot`() {
            assertThat(CorpusWords.keys("Œuvre naïve", fr)).containsExactly("oeuvre", "naive")
        }

        @Test
        fun `la liste de mots devient un lexique`() {
            val lexicon = WordListLoader.load(sequenceOf("le\t10", "la\t5", "Là\t2", "école\t3"), "fr")

            assertThat(lexicon.keyCount).isEqualTo(3)
            assertThat(lexicon.displayForms("la").map { it.form to it.frequency }).containsExactly("la" to 5L, "Là" to 2L)
            assertThat(lexicon.languageTag).isEqualTo("fr")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un mot hors alphabet est compté comme rejeté`() {
            val counts = CorpusWords.count(sequenceOf("Straße ok"), Tokenizer(Language.EN))

            assertThat(counts.counts).containsOnlyKeys("ok")
            assertThat(counts.rejected).isEqualTo(1)
            assertThat(counts.analyzed).isEqualTo(1)
            assertThat(CorpusWords.keyOf("straße")).isNull()
        }

        @Test
        fun `un mot que le normaliseur refuse n'a pas de clé`() {
            assertThat(CorpusWords.keyOf("a".repeat(65))).isNull()
            assertThat(CorpusWords.keyOf("")).isNull()
        }

        @Test
        fun `ligne sans tabulation, fréquence absente, nulle ou négative refusées avec leur numéro`() {
            for (line in listOf("mot", "mot\t", "mot\tx", "mot\t0", "mot\t-3", "\t5")) {
                assertThatThrownBy { WordListLoader.load(sequenceOf("le\t1", line), "fr") }
                    .isInstanceOf(AnalysisException::class.java)
                    .hasMessageContaining("ligne 2").hasMessageContaining("(fr)")
            }
        }

        @Test
        fun `une forme non indexable est ignorée sans erreur`() {
            val lexicon = WordListLoader.load(sequenceOf("3D\t5", "ok\t1"), "en")

            assertThat(lexicon.keyCount).isEqualTo(1)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `corpus vide`() {
            val counts = CorpusWords.count(emptySequence(), fr)

            assertThat(counts.counts).isEmpty()
            assertThat(counts.tokens).isZero()
        }

        @Test
        fun `les lignes vides de la liste sont ignorées, fréquence 1 acceptée`() {
            val lexicon = WordListLoader.load(sequenceOf("", "a\t1", ""), "fr")

            assertThat(lexicon.keyCount).isEqualTo(1)
            assertThat(lexicon.displayForms("a").single().frequency).isEqualTo(1)
        }

        @Test
        fun `mot de 64 caractères accepté, apostrophe et trait d'union conservés`() {
            assertThat(CorpusWords.keyOf("a".repeat(64))).hasSize(64)
            assertThat(CorpusWords.keyOf("Peut-être")).isEqualTo("peut-etre")
            assertThat(CorpusWords.keyOf("aujourd’hui")).isEqualTo("aujourd'hui")
        }
    }
}
