package nextglyph.tools.analysis

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.ln

class PrefixAnalysisTest {

    private fun lexicon(vararg words: Pair<String, Long>): Lexicon {
        val builder = LexiconBuilder("fr")
        for ((w, f) in words) builder.add(w, f)
        return builder.build()
    }

    // l → e (le 6, les 2 : masse 8), a (la 2) ; P(e | l) = 0,8, P(a | l) = 0,2 ; P(fin | le) = 0,75.
    private val small = lexicon("le" to 6, "la" to 2, "les" to 2)

    @Nested
    inner class HappyPath {
        @Test
        fun `position 1 - une seule initiale possible, certaine`() {
            val report = PrefixAnalysis.analyze(small, mapOf("le" to 3L, "la" to 1L, "lu" to 1L))
            val first = report.positions[0]

            assertThat(first.position).isEqualTo(1)
            assertThat(first.weight).isEqualTo(5)
            assertThat(first.meanContinuations).isEqualTo(1.0)
            assertThat(first.meanEntropy).isEqualTo(0.0)
            assertThat(first.meanProbability).isEqualTo(1.0)
            assertThat(first.meanRelative).isEqualTo(1.0)
            assertThat(first.meanRank).isEqualTo(1.0)
            assertThat(first.top3Share).isEqualTo(1.0)
            assertThat(first.top5Share).isEqualTo(1.0)
            assertThat(first.fadedShare).isEqualTo(0.0)
            assertThat(first.unknownShare).isEqualTo(0.0)
        }

        @Test
        fun `position 2 - moyennes pondérées calculées à la main`() {
            val second = PrefixAnalysis.analyze(small, mapOf("le" to 3L, "la" to 1L, "lu" to 1L)).positions[1]

            assertThat(second.weight).isEqualTo(5)
            assertThat(second.absentWeight).isEqualTo(1) // u n'est pas une continuation de « l »
            assertThat(second.meanContinuations).isCloseTo(2.0, within(1e-9))
            val h = -(0.8 * ln(0.8) + 0.2 * ln(0.2)) / ln(2.0)
            assertThat(second.meanEntropy).isCloseTo(h, within(1e-3))
            assertThat(second.meanProbability).isCloseTo((3 * 0.8 + 0.2) / 5, within(1e-3))
            assertThat(second.meanRelative).isCloseTo((3 + 0.25) / 5, within(1e-3))
            assertThat(second.meanRank).isCloseTo((3 * 1 + 2) / 4.0, within(1e-9))
            assertThat(second.top3Share).isCloseTo(0.8, within(1e-9))
            assertThat(second.top5Share).isCloseTo(0.8, within(1e-9))
            assertThat(second.fadedShare).isCloseTo(0.2, within(1e-9))
        }

        @Test
        fun `P(fin) moyen sur les mots du lexique`() {
            val report = PrefixAnalysis.analyze(small, mapOf("le" to 3L, "la" to 1L, "lu" to 1L))

            assertThat(report.endWeight).isEqualTo(4)
            assertThat(report.meanEndProbability).isCloseTo((3 * 0.75 + 1.0) / 4, within(1e-3))
        }

        @Test
        fun `entropie de distributions connues`() {
            assertThat(PrefixAnalysis.entropyBits(listOf(0.5, 0.5))).isCloseTo(1.0, within(1e-12))
            assertThat(PrefixAnalysis.entropyBits(listOf(0.25, 0.25, 0.25, 0.25))).isCloseTo(2.0, within(1e-12))
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un mot hors lexique rend ses préfixes suivants inconnus`() {
            val report = PrefixAnalysis.analyze(small, mapOf("lux" to 2L))
            val third = report.positions[2]

            assertThat(report.positions[1].absentWeight).isEqualTo(2)
            assertThat(third.weight).isEqualTo(2)
            assertThat(third.unknownWeight).isEqualTo(2)
            assertThat(third.unknownShare).isEqualTo(1.0)
            assertThat(report.endWeight).isZero()
            assertThat(report.meanEndProbability).isZero()
        }

        @Test
        fun `un mot dont l'initiale est absente est inconnu dès la position 2`() {
            val report = PrefixAnalysis.analyze(small, mapOf("zz" to 1L))

            assertThat(report.positions[0].absentWeight).isEqualTo(1)
            assertThat(report.positions[0].meanProbability).isZero()
            assertThat(report.positions[0].meanRelative).isZero()
            assertThat(report.positions[0].top5Share).isZero()
            assertThat(report.positions[1].unknownWeight).isEqualTo(1)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `égalité de probabilité - rang 1 pour les deux lettres`() {
            val report = PrefixAnalysis.analyze(lexicon("ab" to 1, "ac" to 1), mapOf("ac" to 1L))

            assertThat(report.positions[1].meanRank).isEqualTo(1.0)
            assertThat(report.positions[1].meanRelative).isCloseTo(1.0, within(1e-9))
            assertThat(report.positions[1].meanEntropy).isCloseTo(1.0, within(1e-3))
        }

        @Test
        fun `les positions au-delà de 8 sont regroupées dans la dernière tranche`() {
            val word = "abcdefghij" // 10 lettres : positions 8, 9 et 10 dans la tranche « 8+ »
            val report = PrefixAnalysis.analyze(lexicon(word to 1), mapOf(word to 1L))

            assertThat(report.positions).hasSize(PrefixAnalysis.MAX_POSITION)
            assertThat(report.positions.last().position).isEqualTo(8)
            assertThat(report.positions.last().weight).isEqualTo(3)
            assertThat(report.positions[6].weight).isEqualTo(1)
        }

        @Test
        fun `rang 4 et 6 - dans le top 5 puis hors du top 5, atténuation au seuil`() {
            // Après « x » : a 43, b 30, c 20, d 3, e 2, f 2 (masse 100).
            val lex = lexicon("xa" to 43, "xb" to 30, "xc" to 20, "xd" to 3, "xe" to 2, "xf" to 2)
            val fourth = PrefixAnalysis.analyze(lex, mapOf("xd" to 1L)).positions[1]
            val sixth = PrefixAnalysis.analyze(lex, mapOf("xf" to 1L)).positions[1]

            assertThat(fourth.meanRank).isEqualTo(4.0)
            assertThat(fourth.top3Share).isZero()
            assertThat(fourth.top5Share).isEqualTo(1.0)
            assertThat(fourth.fadedShare).isEqualTo(1.0) // r = 3 / 43 < 0,1
            assertThat(sixth.meanRank).isEqualTo(5.0) // e et f à égalité : rang 5
            assertThat(sixth.top5Share).isEqualTo(1.0)
            val third = PrefixAnalysis.analyze(lex, mapOf("xc" to 1L)).positions[1]
            assertThat(third.meanRank).isEqualTo(3.0)
            assertThat(third.top3Share).isEqualTo(1.0)
            assertThat(third.fadedShare).isZero()
        }

        @Test
        fun `rang 6 hors du top 5, r exactement 0,1 non atténuée`() {
            // Après « y » : a 50, b 20, c 10, d 9, e 6, f 5 (masse 100) ; r(f) = 0,05 / 0,5 = 0,1.
            val lex = lexicon("ya" to 50, "yb" to 20, "yc" to 10, "yd" to 9, "ye" to 6, "yf" to 5)

            val sixth = PrefixAnalysis.analyze(lex, mapOf("yf" to 1L)).positions[1]

            assertThat(sixth.meanRank).isEqualTo(6.0)
            assertThat(sixth.top5Share).isZero()
            assertThat(sixth.meanRelative).isEqualTo(0.1)
            assertThat(sixth.fadedShare).isZero()
        }

        @Test
        fun `entropie - termes nuls ignorés, distribution certaine nulle`() {
            assertThat(PrefixAnalysis.entropyBits(listOf(1.0, 0.0))).isEqualTo(0.0)
            assertThat(PrefixAnalysis.entropyBits(emptyList())).isEqualTo(0.0)
        }

        @Test
        fun `corpus vide - aucune position renseignée`() {
            val report = PrefixAnalysis.analyze(small, emptyMap())

            assertThat(report.positions).allMatch { it.weight == 0L }
            assertThat(report.endWeight).isZero()
        }
    }
}
