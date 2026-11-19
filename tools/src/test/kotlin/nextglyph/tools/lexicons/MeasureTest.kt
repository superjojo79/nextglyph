package nextglyph.tools.lexicons

import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.tools.wordlists.Language
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Statistiques de latence, échantillonnage de préfixes, chronométrage et rapport. */
class MeasureTest {

    private val lexicon = LexiconBuilder("en").add("cat", 5).add("car", 3).build()

    private fun measure(
        sizeBytes: Long = 1024L * 1024,
        skipped: List<String> = emptyList(),
        distribution: LatencyStats? = LatencyStats(4, 1500.0, 2500),
    ) = LexiconMeasure("fr", "fr.nglx", 12, 15, sizeBytes, skipped, 2_500_000, 4, distribution).let { LexiconReport.render(listOf(it)) }

    @Nested
    inner class HappyPath {
        @Test
        fun `latence - moyenne et p99 sur 100 échantillons mélangés`() {
            val samples = (100 downTo 1).map { it.toLong() }.shuffled(java.util.Random(1)).toLongArray()

            val stats = LatencyStats.of(samples)

            assertThat(stats.count).isEqualTo(100)
            assertThat(stats.meanNanos).isEqualTo(50.5)
            assertThat(stats.p99Nanos).isEqualTo(99L)
        }

        @Test
        fun `préfixes - tous les préfixes non vides de chaque mot valide, répétitions comprises`() {
            val prefixes = PrefixSampler.sample(listOf("Été à l'école.", "Été !"), Language.FR, 100)

            assertThat(prefixes).containsExactly(
                "e", "et", "ete", "a", "l", "l'", "e", "ec", "eco", "ecol", "ecole", "e", "et", "ete",
            )
        }

        @Test
        fun `préfixes - un mot hors alphabet est ignoré`() {
            assertThat(PrefixSampler.sample(listOf("3D ok Straße"), Language.EN, 100)).containsExactly("o", "ok")
        }

        @Test
        fun `chronométrage - une durée par préfixe, lue sur l'horloge fournie`() {
            var now = 0L
            var calls = 0
            // Chaque appel d'horloge avance de 10 ns, puis de 5 ns de plus tous les deux appels.
            val clock = { now += if (calls++ % 2 == 0) 10 else 20; now }

            val stats = LexiconBenchmark.distributionLatency(lexicon, listOf("c", "ca", "cat"), clock)

            // Départ : +10, fin : +20 -> 20 ns par préfixe.
            assertThat(stats.count).isEqualTo(3)
            assertThat(stats.meanNanos).isEqualTo(20.0)
            assertThat(calls).isEqualTo(6)
        }

        @Test
        fun `chronométrage - l'horloge par défaut donne des durées positives ou nulles`() {
            val stats = LexiconBenchmark.distributionLatency(lexicon, listOf("c"))

            assertThat(stats.p99Nanos).isGreaterThanOrEqualTo(0)
        }

        @Test
        fun `rapport - tableau complet`() {
            val text = measure(skipped = listOf("3D", "Straße"))

            assertThat(text).isEqualTo(
                """
                # Rapport des lexiques binaires

                Budgets (spec §11.3) : taille ≤ 8 Mio par langue (tous ses fichiers) ; `distribution` < 1 ms.

                | Langue | Taille totale |
                |---|---|
                | fr | 1048576 octets (1.00 Mio) : dans le budget |

                ## fr : `fr.nglx`

                | Mesure | Valeur |
                |---|---|
                | Fichier | `fr.nglx` |
                | Clés | 12 |
                | Formes d'affichage | 15 |
                | Taille | 1048576 octets (1.00 Mio) |
                | Chargement (map + `Lexicon.load`) | 2.50 ms |
                | Préfixes mesurés | 4 |
                | `distribution` moyenne | 1.50 µs |
                | `distribution` p99 | 2.50 µs : dans le budget |
                | Formes ignorées (hors alphabet) | 2 |

                Exemples de formes ignorées : `3D`, `Straße`

                """.trimIndent(),
            )
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `latence - aucun échantillon refusé`() {
            assertThatThrownBy { LatencyStats.of(LongArray(0)) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `préfixes - limite négative refusée`() {
            assertThatThrownBy { PrefixSampler.sample(listOf("a"), Language.EN, -1) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("-1")
        }

        @Test
        fun `chronométrage - aucun préfixe refusé`() {
            assertThatThrownBy { LexiconBenchmark.distributionLatency(lexicon, emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rapport - taille hors budget signalée`() {
            assertThat(measure(sizeBytes = SIZE_BUDGET_BYTES + 1)).contains("HORS BUDGET").doesNotContain("octets (8.00 Mio) : dans")
        }

        @Test
        fun `rapport - p99 de 1 ms exactement hors budget`() {
            assertThat(measure(distribution = LatencyStats(1, 1.0, 1_000_000))).contains("1000.00 µs : HORS BUDGET")
        }

        @Test
        fun `rapport - sans préfixe, distribution non mesurée`() {
            assertThat(measure(distribution = null)).contains("| `distribution` | non mesuré (aucun préfixe) |")
                .doesNotContain("moyenne")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `latence - un seul échantillon`() {
            assertThat(LatencyStats.of(longArrayOf(7))).isEqualTo(LatencyStats(1, 7.0, 7))
        }

        @Test
        fun `latence - 101 échantillons, p99 au rang 100`() {
            assertThat(LatencyStats.of(LongArray(101) { it + 1L }).p99Nanos).isEqualTo(100L)
        }

        @Test
        fun `latence - 100 échantillons, p99 au rang 99`() {
            assertThat(LatencyStats.of(LongArray(100) { it + 1L }).p99Nanos).isEqualTo(99L)
        }

        @Test
        fun `préfixes - limite 0 et limite atteinte au milieu d'un mot`() {
            assertThat(PrefixSampler.sample(listOf("chat"), Language.EN, 0)).isEmpty()
            assertThat(PrefixSampler.sample(listOf("chat"), Language.EN, 2)).containsExactly("c", "ch")
            assertThat(PrefixSampler.sample(listOf("chat"), Language.EN, 4)).containsExactly("c", "ch", "cha", "chat")
        }

        @Test
        fun `préfixes - aucune phrase`() {
            assertThat(PrefixSampler.sample(emptyList(), Language.FR, 10)).isEmpty()
        }

        @Test
        fun `rapport - taille exactement au budget acceptée`() {
            assertThat(measure(sizeBytes = SIZE_BUDGET_BYTES)).contains("(8.00 Mio) : dans le budget")
        }

        @Test
        fun `rapport - p99 juste sous 1 ms accepté`() {
            assertThat(measure(distribution = LatencyStats(1, 1.0, 999_999))).contains(": dans le budget |")
                .doesNotContain("HORS BUDGET")
        }

        @Test
        fun `rapport - au plus dix exemples de formes ignorées`() {
            val skipped = (1..11).map { "x$it" }

            val text = measure(skipped = skipped)

            assertThat(text).contains("| Formes ignorées (hors alphabet) | 11 |").contains("`x10`").doesNotContain("`x11`")
        }

        @Test
        fun `rapport - aucune forme ignorée, pas de ligne d'exemples`() {
            assertThat(measure()).doesNotContain("Exemples")
        }

        @Test
        fun `rapport - plusieurs langues dans l'ordre`() {
            val m = LexiconMeasure("fr", "fr.nglx", 1, 1, 10, emptyList(), 1, 0, null)

            val text = LexiconReport.render(listOf(m, m.copy(fileName = "fr-b.nglx", sizeBytes = 5), m.copy(languageTag = "en", fileName = "en.nglx")))

            assertThat(text.indexOf("## fr")).isLessThan(text.indexOf("## en"))
            assertThat(text).contains("| fr | 15 octets", "| en | 10 octets")
            assertThat(LexiconReport.sizesByLanguage(listOf(m, m))).containsExactly(java.util.AbstractMap.SimpleEntry("fr", 20L))
        }
    }
}
