package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.Distribution
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.exp

class KeyWeightCalculatorTest {

    private val eps = Offset.offset(1e-4f)

    /** Les masses des nœuds internes sont quantifiées à 0,07 % près. */
    private val mass = Offset.offset(1e-3f)

    /** `ch…` : a = 50 (chat 40, chats 10), i = 30 ; `car` 20 ; `pomme` 10 ; `aujourd'hui` 15. */
    private val lexicon: Lexicon = LexiconFixtures.lexicon(
        listOf(
            "chat" to 40L,
            "chats" to 10L,
            "chien" to 30L,
            "car" to 20L,
            "pomme" to 10L,
            "aujourd'hui" to 15L,
        ),
    )

    private val azerty = KeyboardLayouts.AZERTY

    /** Source qui consigne chaque préfixe interrogé. */
    private class RecordingSource(private val lexicon: Lexicon) : DistributionSource {
        val queries = mutableListOf<String>()
        override fun distribution(prefix: String): Distribution {
            queries += prefix
            return lexicon.distribution(prefix)
        }
    }

    private fun compute(
        prefix: String,
        config: AdaptationConfig = AdaptationConfig(),
        layout: KeyboardLayout = azerty,
        lex: Lexicon = lexicon,
    ): KeyWeights {
        val out = KeyWeights(layout)
        KeyWeightCalculator(layout, config).compute(prefix, lex, out)
        return out
    }

    /** `r(c)` de la lettre [c] dans AZERTY. */
    private fun KeyWeights.r(c: Char): Float {
        layout.rows.forEachIndexed { row, keyRow ->
            val k = keyRow.keys.indexOfFirst { it.label == c.toString() }
            if (k >= 0) return relativeWeight(row, k)
        }
        error("Lettre absente : $c")
    }

    private fun KeyWeights.atRest(c: Char): Boolean {
        layout.rows.forEachIndexed { row, keyRow ->
            val k = keyRow.keys.indexOfFirst { it.label == c.toString() }
            if (k >= 0) return isAtRest(row, k)
        }
        error("Lettre absente : $c")
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `préfixe connu, r(c) relatif au maximum et P(fin)`() {
            val weights = compute("ch")

            assertThat(weights.source).isEqualTo(WeightSource.PREFIX)
            assertThat(weights.intensity).isEqualTo(1f)
            assertThat(weights.r('a')).isEqualTo(1f)
            assertThat(weights.r('i')).isCloseTo(0.6f, mass)
            assertThat(weights.r('e')).isEqualTo(0f)
            assertThat(weights.atRest('e')).isFalse()
            assertThat(weights.endProbability).isEqualTo(0f)
        }

        @Test
        fun `mot complet, P(fin) pilote l'espace`() {
            val weights = compute("chat")

            assertThat(weights.endProbability).isCloseTo(0.8f, eps)
            assertThat(weights.r('s')).isEqualTo(1f)
        }

        @Test
        fun `début de mot, initiales et adaptation atténuée de moitié`() {
            val weights = compute("")

            assertThat(weights.source).isEqualTo(WeightSource.WORD_START)
            assertThat(weights.intensity).isEqualTo(0.5f)
            // Initiales : c = 100, a = 15, p = 10.
            assertThat(weights.r('c')).isEqualTo(1f)
            assertThat(weights.r('a')).isCloseTo(0.15f, eps)
            assertThat(weights.r('p')).isCloseTo(0.10f, eps)
        }

        @Test
        fun `préfixe inconnu, substitution par une touche voisine`() {
            val weights = compute("cgat")

            assertThat(weights.source).isEqualTo(WeightSource.CORRECTION)
            assertThat(weights.intensity).isEqualTo(0.5f)
            assertThat(weights.r('s')).isEqualTo(1f)
            assertThat(weights.endProbability).isCloseTo(0.8f, eps)
        }

        @Test
        fun `préfixe inconnu, lettre en trop`() {
            val weights = compute("chxat")

            assertThat(weights.source).isEqualTo(WeightSource.CORRECTION)
            assertThat(weights.r('s')).isEqualTo(1f)
            assertThat(weights.endProbability).isCloseTo(0.8f, eps)
        }

        @Test
        fun `préfixe inconnu, mélange de la lettre en trop et de la lettre manquante`() {
            val weights = compute("cht")

            // « ch » (0,3 × 80) et « chat » (0,3 × 50).
            assertThat(weights.r('a')).isEqualTo(1f)
            assertThat(weights.r('i')).isCloseTo(0.6f, mass)
            assertThat(weights.r('s')).isCloseTo(0.2f, eps)
            assertThat(weights.endProbability).isCloseTo(12f / 39f, eps)
        }

        @Test
        fun `la correction explore les positions de la fin vers le début, dans l'ordre`() {
            val source = RecordingSource(lexicon)

            KeyWeightCalculator(azerty).compute("cht", lexicon.distribution("cht"), source, KeyWeights(azerty))

            // Préfixes propres, puis position 2 (insertions ; la suppression réutilise « ch »),
            // position 1 (suppression, insertions), position 0 (suppression, insertions).
            assertThat(source.queries).containsExactly(
                "", "c", "ch",
                "chat", "chit",
                "ct", "caht", "chht",
                "ht", "acht", "ccht", "pcht",
            )
        }

        @Test
        fun `les préfixes propres ne sont lus que jusqu'au premier inconnu`() {
            val source = RecordingSource(lexicon)

            KeyWeightCalculator(azerty).compute("chxat", lexicon.distribution("chxat"), source, KeyWeights(azerty))

            assertThat(source.queries).startsWith("", "c", "ch", "chx").doesNotContain("chxa")
        }

        @Test
        fun `les candidats sont réutilisables d'un appel à l'autre sans fuite d'état`() {
            val calculator = KeyWeightCalculator(azerty)
            val reused = KeyWeights(azerty)

            calculator.compute("ch", lexicon, reused)
            calculator.compute("cgat", lexicon, reused)
            val fresh = compute("cgat")

            for (c in 'a'..'z') assertThat(reused.r(c)).isEqualTo(fresh.r(c))
            assertThat(reused.endProbability).isEqualTo(fresh.endProbability)
            calculator.compute("zzzz", lexicon, reused)
            assertThat(reused.isNeutral).isTrue()
            assertThat(reused.atRest('s')).isTrue()
        }

        @Test
        fun `la substitution est pondérée par la distance entre les touches`() {
            val lex = LexiconFixtures.lexicon(listOf("cab" to 10L, "sac" to 10L))

            val weights = compute("xa", AdaptationConfig(correctionAdjacencySigma = 0.8f), lex = lex)

            // x → c : même rangée, 0,85 u ; x → s : rangée au-dessus, (0,775 u ; 1 u).
            val toC = exp(-(0.85 * 0.85) / (2 * 0.64))
            val toS = exp(-(0.775 * 0.775 + 1) / (2 * 0.64))
            assertThat(weights.r('b')).isEqualTo(1f)
            assertThat(weights.r('c').toDouble()).isCloseTo(toS / toC, Offset.offset(1e-4))
        }

        @Test
        fun `la distribution déjà calculée est réutilisée et la source n'est pas interrogée`() {
            val source = RecordingSource(lexicon)
            val out = KeyWeights(azerty)

            KeyWeightCalculator(azerty).compute("ch", lexicon.distribution("ch"), source, out)

            assertThat(source.queries).isEmpty()
            assertThat(out.r('a')).isEqualTo(1f)
        }

        @Test
        fun `la correction n'interroge jamais deux fois le même candidat`() {
            for (typo in listOf("chhat", "pome", "cht", "cgat", "pommme", "chirn")) {
                val source = RecordingSource(lexicon)
                val out = KeyWeights(azerty)

                KeyWeightCalculator(azerty).compute(typo, lexicon.distribution(typo), source, out)

                assertThat(source.queries).doesNotHaveDuplicates()
                assertThat(out.source).isEqualTo(WeightSource.CORRECTION)
            }
        }

        @Test
        fun `le calculateur se réutilise avec un autre lexique`() {
            val calculator = KeyWeightCalculator(azerty)
            val out = KeyWeights(azerty)
            val other = LexiconFixtures.lexicon(listOf("zoo" to 1L))

            calculator.compute("", lexicon, out)
            calculator.compute("", other, out)

            assertThat(out.r('z')).isEqualTo(1f)
            assertThat(out.r('c')).isEqualTo(0f)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette des poids d'une autre disposition`() {
            val calculator = KeyWeightCalculator(azerty)

            assertThatThrownBy { calculator.compute("ch", lexicon, KeyWeights(KeyboardLayouts.QWERTY)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("disposition")
        }

        @Test
        fun `préfixe inconnu sans correction possible, état neutre`() {
            val weights = compute("zzzz")

            assertThat(weights.isNeutral).isTrue()
            assertThat(weights.intensity).isEqualTo(0f)
            assertThat(weights.atRest('a')).isTrue()
        }

        @Test
        fun `préfixe avec un caractère hors alphabet, correction par suppression`() {
            val weights = compute("ch1at")

            assertThat(weights.source).isEqualTo(WeightSource.CORRECTION)
            assertThat(weights.r('s')).isEqualTo(1f)
        }

        @Test
        fun `distribution inconnue fournie pour un préfixe que la source connaît, jamais relu`() {
            val source = RecordingSource(lexicon)
            val out = KeyWeights(azerty)

            KeyWeightCalculator(azerty).compute("ch", lexicon.distribution("zzzz"), source, out)

            assertThat(source.queries).doesNotContain("ch").startsWith("", "c")
            assertThat(out.source).isEqualTo(WeightSource.CORRECTION)
        }

        @Test
        fun `une lettre adaptée à la main hors alphabet revient au repos au calcul suivant`() {
            val layout = AdaptFixtures.WITH_FOREIGN_LETTER
            val out = KeyWeights(layout)
            out.setLetterWeight('é', 0.2f)

            KeyWeightCalculator(layout).compute("", lexicon, out)

            assertThat(out.isAtRest(0, 4)).isTrue()
            assertThat(out.relativeWeight(0, 4)).isEqualTo(1f)
        }

        @Test
        fun `lexique vide, état neutre même en début de mot`() {
            val empty = LexiconFixtures.lexicon(emptyList())

            assertThat(compute("", lex = empty).isNeutral).isTrue()
            assertThat(compute("a", lex = empty).isNeutral).isTrue()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `aucun candidat autorisé, état neutre`() {
            val weights = compute("cgat", AdaptationConfig(correctionMaxCandidates = 0))

            assertThat(weights.isNeutral).isTrue()
        }

        @Test
        fun `un seul candidat autorisé, le plus proche de l'erreur`() {
            val weights = compute("cht", AdaptationConfig(correctionMaxCandidates = 1))

            // Seul « ch » (suppression du t) est interrogé.
            assertThat(weights.r('a')).isEqualTo(1f)
            assertThat(weights.r('s')).isEqualTo(0f)
        }

        @Test
        fun `poids de suppression et d'insertion nuls, seules les substitutions comptent`() {
            val config = AdaptationConfig(correctionDeletionWeight = 0f, correctionInsertionWeight = 0f)

            assertThat(compute("cht", config).isNeutral).isTrue()
            assertThat(compute("cgat", config).source).isEqualTo(WeightSource.CORRECTION)
            val source = RecordingSource(lexicon)
            KeyWeightCalculator(azerty, config).compute("cht", lexicon.distribution("cht"), source, KeyWeights(azerty))
            assertThat(source.queries).containsExactly("", "c", "ch")
        }

        @Test
        fun `voisine à exactement le rayon, substitution envisagée`() {
            // q est exactement sous a : 1 u.
            val lex = LexiconFixtures.lexicon(listOf("ab" to 1L))
            val config = AdaptationConfig(correctionNeighborRadius = 1f, correctionDeletionWeight = 0f, correctionInsertionWeight = 0f)

            val weights = compute("qb", config, lex = lex)

            assertThat(weights.source).isEqualTo(WeightSource.CORRECTION)
            assertThat(compute("qb", config.copy(correctionNeighborRadius = 0.99f), lex = lex).isNeutral).isTrue()
        }

        @Test
        fun `rayon de voisinage nul, aucune substitution`() {
            val config = AdaptationConfig(correctionNeighborRadius = 0f, correctionDeletionWeight = 0f, correctionInsertionWeight = 0f)

            assertThat(compute("cgat", config).isNeutral).isTrue()
        }

        @Test
        fun `seule continuation hors des lettres, toutes les lettres à zéro`() {
            val weights = compute("aujourd")

            assertThat(weights.source).isEqualTo(WeightSource.PREFIX)
            assertThat(('a'..'z').map { weights.r(it) }).containsOnly(0f)
            assertThat(weights.endProbability).isEqualTo(0f)
        }

        @Test
        fun `préfixe d'une lettre inconnue, correction vers les initiales`() {
            val weights = compute("x")

            // Suppression → « » (initiales) ; substitution x → c (voisine) → « c ».
            assertThat(weights.source).isEqualTo(WeightSource.CORRECTION)
            assertThat(weights.r('h')).isEqualTo(1f)
        }

        @Test
        fun `une lettre hors de l'alphabet du lexique reste au repos`() {
            val weights = compute("", layout = AdaptFixtures.WITH_FOREIGN_LETTER)

            assertThat(weights.isAtRest(0, 4)).isTrue()
            assertThat(weights.relativeWeight(0, 4)).isEqualTo(1f)
            assertThat(weights.isAtRest(0, 2)).isFalse()
            assertThat(weights.relativeWeight(0, 2)).isEqualTo(1f)
            assertThat(weights.isAtRest(0, 9)).isTrue()
        }

        @Test
        fun `préfixe long inconnu, au-delà de la capacité initiale`() {
            val long = "a".repeat(40)
            val lex = LexiconFixtures.lexicon(listOf(long + "b" to 1L))

            val weights = compute(long + "x", lex = lex)

            assertThat(weights.source).isEqualTo(WeightSource.CORRECTION)
            assertThat(weights.r('b')).isEqualTo(1f)
        }
    }
}
