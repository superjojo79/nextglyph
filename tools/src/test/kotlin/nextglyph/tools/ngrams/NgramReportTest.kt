package nextglyph.tools.ngrams

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NgramReportTest {

    private val hits = NgramHitRates(positions = 1000, eligible = 800, top1 = 200, top3 = 400)

    private fun measure(tag: String = "fr", size: Long = 2L * 1024 * 1024) = NgramMeasure(
        languageTag = tag,
        fileName = "$tag.ngrm",
        sizeBytes = size,
        vocabulary = 30_000,
        bigrams = 100_000,
        trigramContexts = 50_000,
        trigrams = 120_000,
        trainSentences = 400_000,
        trainTokens = 3_000_000,
        loadNanos = 1_500_000,
        hits = hits,
    )

    @Nested
    inner class HappyPath {
        @Test
        fun `le rapport donne les tailles, les compteurs et les taux`() {
            val report = NgramReport.render(listOf(measure("fr"), measure("en", 1024)))

            assertThat(report).contains("# Rapport des modèles de mot suivant")
            assertThat(report).contains("| fr | 2097152 octets (2.00 Mio) : dans le budget | 25.00 % | 50.00 % |")
            assertThat(report).contains("| en | 1024 octets (0.00 Mio) : dans le budget |")
            assertThat(report).contains("## fr : `fr.ngrm`")
            assertThat(report).contains("| Vocabulaire | 30000 mots |")
            assertThat(report).contains("| Bigrammes gardés | 100000 |")
            assertThat(report).contains("| Contextes de trigrammes | 50000 |")
            assertThat(report).contains("| Trigrammes gardés | 120000 |")
            assertThat(report).contains("| Phrases d'entraînement | 400000 |")
            assertThat(report).contains("| Mots d'entraînement | 3000000 |")
            assertThat(report).contains("| Chargement (map + `NgramModel.load`) | 1.50 ms |")
            assertThat(report).contains("| Positions du test | 1000 |")
            assertThat(report).contains("| Positions éligibles (mot proposable, dans le vocabulaire) | 800 |")
            assertThat(report).contains("| Top-1, positions éligibles | 25.00 % |")
            assertThat(report).contains("| Top-3, positions éligibles | 50.00 % |")
            assertThat(report).contains("| Top-1, toutes positions | 20.00 % |")
            assertThat(report).contains("| Top-3, toutes positions | 40.00 % |")
        }

        @Test
        fun `le budget par défaut est de 5 Mio`() {
            assertThat(NGRAM_SIZE_BUDGET_BYTES).isEqualTo(5L * 1024 * 1024)
            assertThat(NgramReport.render(listOf(measure()))).contains("≤ 5.00 Mio par langue")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un modèle au-delà du budget est signalé hors budget`() {
            val report = NgramReport.render(listOf(measure(size = NGRAM_SIZE_BUDGET_BYTES + 1)))

            assertThat(report).contains("HORS BUDGET")
            assertThat(report).doesNotContain("dans le budget")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un modèle d'exactement le budget est dans le budget`() {
            val report = NgramReport.render(listOf(measure(size = NGRAM_SIZE_BUDGET_BYTES)))

            assertThat(report).contains("dans le budget")
            assertThat(report).doesNotContain("HORS BUDGET")
        }

        @Test
        fun `un budget explicite remplace celui par défaut`() {
            val report = NgramReport.render(listOf(measure(size = 2000)), sizeBudget = 1999)

            assertThat(report).contains("HORS BUDGET")
        }

        @Test
        fun `sans mesure, seul l'en-tête est écrit`() {
            val report = NgramReport.render(emptyList())

            assertThat(report).contains("# Rapport").doesNotContain("##")
        }
    }
}
