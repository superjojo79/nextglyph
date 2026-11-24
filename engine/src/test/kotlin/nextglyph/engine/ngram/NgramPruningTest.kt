package nextglyph.engine.ngram

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NgramPruningTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `les valeurs par défaut sont celles de la conception`() {
            val pruning = NgramPruning()

            assertThat(pruning.minBigramCount).isEqualTo(2)
            assertThat(pruning.minTrigramCount).isEqualTo(2)
            assertThat(pruning.maxBigramFollowers).isEqualTo(8)
            assertThat(pruning.maxVocabulary).isEqualTo(30_000)
        }

        @Test
        fun `un mot proposé garde son texte et sa probabilité`() {
            val word = NextWord("je", 0.5)

            assertThat(word.text).isEqualTo("je")
            assertThat(word.probability).isEqualTo(0.5)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un seuil de bigrammes nul est refusé`() {
            assertThatThrownBy { NgramPruning(minBigramCount = 0) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("bigrammes")
        }

        @Test
        fun `un seuil de trigrammes nul est refusé`() {
            assertThatThrownBy { NgramPruning(minTrigramCount = 0) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("trigrammes")
        }

        @Test
        fun `un nombre de suivants hors limites est refusé`() {
            assertThatThrownBy { NgramPruning(maxBigramFollowers = 0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { NgramPruning(maxBigramFollowers = 256) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { NgramPruning(maxTrigramFollowers = 0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { NgramPruning(maxTrigramFollowers = 256) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `un vocabulaire hors limites est refusé`() {
            assertThatThrownBy { NgramPruning(maxVocabulary = 0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { NgramPruning(maxVocabulary = NgramFormat.MAX_VOCABULARY + 1) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("vocabulaire")
        }

        @Test
        fun `un gain négatif ou infini est refusé`() {
            assertThatThrownBy { NgramPruning(minTrigramGain = -0.1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("gain")
            assertThatThrownBy { NgramPruning(minTrigramGain = Double.POSITIVE_INFINITY) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { NgramPruning(minTrigramGain = Double.NaN) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `les bornes exactes sont acceptées`() {
            NgramPruning(minBigramCount = 1, minTrigramCount = 1, maxBigramFollowers = 1, maxTrigramFollowers = 255)
            NgramPruning(maxBigramFollowers = 255, maxTrigramFollowers = 1, maxVocabulary = NgramFormat.MAX_VOCABULARY)
            NgramPruning(maxVocabulary = 1, minTrigramGain = 0.0)
        }
    }
}
