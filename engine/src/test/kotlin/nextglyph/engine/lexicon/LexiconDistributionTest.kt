package nextglyph.engine.lexicon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LexiconDistributionTest {

    /** Tolérance due à la quantification des masses (au plus 0,07 % par masse). */
    private val quantization = within(1e-3)

    private val sample = LexiconFixtures.sample()

    private fun sumOf(distribution: Distribution): Double =
        LexiconAlphabet.CHARS.sumOf { distribution.probability(it) } + distribution.endProbability

    @Nested
    inner class HappyPath {

        @Test
        fun `le préfixe vide donne la distribution des initiales`() {
            val distribution = sample.distribution("")

            assertThat(distribution.isUnknownPrefix).isFalse()
            assertThat(distribution.continuations).containsExactly('a', 'c', 'p')
            assertThat(distribution.probability('a')).isCloseTo(115.0 / 220, quantization)
            assertThat(distribution.probability('c')).isCloseTo(80.0 / 220, quantization)
            assertThat(distribution.probability('p')).isCloseTo(25.0 / 220, quantization)
            assertThat(distribution.endProbability).isZero()
            assertThat(distribution.totalMass).isCloseTo(220.0, within(0.2))
        }

        @Test
        fun `un préfixe qui est un mot donne P(fin) et ses continuations`() {
            val distribution = sample.distribution("a")

            assertThat(distribution.endProbability).isEqualTo(80.0 / 115)
            assertThat(distribution.probability('b')).isEqualTo(20.0 / 115)
            assertThat(distribution.probability('u')).isEqualTo(15.0 / 115)
            assertThat(distribution.totalMass).isEqualTo(115.0)
            assertThat(distribution.continuations).containsExactly('b', 'u')
        }

        @Test
        fun `un mot préfixe d'un autre partage sa masse avec ses continuations`() {
            val distribution = sample.distribution("chat")

            assertThat(distribution.endProbability).isEqualTo(0.8)
            assertThat(distribution.probability('s')).isEqualTo(0.2)
            assertThat(distribution.totalMass).isEqualTo(50.0)
        }

        @Test
        fun `un préfixe à l'intérieur d'une étiquette n'a qu'une continuation`() {
            val distribution = sample.distribution("c")

            assertThat(distribution.continuations).containsExactly('h')
            assertThat(distribution.probability('h')).isEqualTo(1.0)
            assertThat(distribution.endProbability).isZero()
            assertThat(distribution.totalMass).isCloseTo(80.0, within(0.1))
        }

        @Test
        fun `un préfixe dans une étiquette de feuille garde la masse exacte`() {
            val distribution = sample.distribution("peut-")

            assertThat(distribution.continuations).containsExactly('e')
            assertThat(distribution.totalMass).isEqualTo(25.0)
        }

        @Test
        fun `l'apostrophe et le trait d'union sont des continuations`() {
            assertThat(sample.distribution("aujourd").continuations).containsExactly('\'')
            assertThat(sample.distribution("aujourd").probability('\'')).isEqualTo(1.0)
            assertThat(sample.distribution("peut").continuations).containsExactly('-')
        }

        @Test
        fun `un nœud interne distribue sa masse entre ses enfants`() {
            val distribution = sample.distribution("ch")

            assertThat(distribution.continuations).containsExactly('a', 'i')
            assertThat(distribution.probability('a')).isCloseTo(50.0 / 80, quantization)
            assertThat(distribution.probability('i')).isCloseTo(30.0 / 80, quantization)
            assertThat(distribution.endProbability).isZero()
        }

        @Test
        fun `les probabilités et P(fin) somment à 1 sur un lexique synthétique`() {
            val entries = SyntheticLexicon.entries(3_000)
            val lexicon = LexiconFixtures.lexicon(entries)
            val keys = entries.map { nextglyph.engine.text.TextNormalizer.normalize(it.first) }

            for (key in keys.take(500)) {
                for (length in 0..key.length) {
                    assertThat(sumOf(lexicon.distribution(key.substring(0, length)))).isCloseTo(1.0, within(1e-9))
                }
            }
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["x", "chx", "chatsx", "abrix", "cx", "peut-etra", "ab'"])
        fun `un préfixe absent est inconnu et toutes ses probabilités sont nulles`(prefix: String) {
            val distribution = sample.distribution(prefix)

            assertThat(distribution.isUnknownPrefix).isTrue()
            assertThat(distribution.continuations).isEmpty()
            assertThat(distribution.endProbability).isZero()
            assertThat(distribution.totalMass).isZero()
            assertThat(sumOf(distribution)).isZero()
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["A", "é", "chat ", "Chat", "ch\u0000", "c'h"])
        fun `un préfixe non normalisé est inconnu, sans exception`(prefix: String) {
            assertThat(sample.distribution(prefix).isUnknownPrefix).isTrue()
        }

        @Test
        fun `la probabilité d'un caractère hors alphabet est nulle`() {
            val distribution = sample.distribution("")

            assertThat(distribution.probability('é')).isZero()
            assertThat(distribution.probability('A')).isZero()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un lexique vide ne connaît aucun préfixe, même vide`() {
            val empty = LexiconBuilder("fr").build()

            assertThat(empty.distribution("").isUnknownPrefix).isTrue()
            assertThat(empty.distribution("a").isUnknownPrefix).isTrue()
        }

        @Test
        fun `un lexique d'une seule entrée`() {
            val lexicon = LexiconFixtures.lexicon(listOf("z" to 1L))

            assertThat(lexicon.distribution("").probability('z')).isEqualTo(1.0)
            assertThat(lexicon.distribution("").totalMass).isEqualTo(1.0)
            assertThat(lexicon.distribution("z").endProbability).isEqualTo(1.0)
            assertThat(lexicon.distribution("z").continuations).isEmpty()
        }

        @Test
        fun `un mot complet sans continuation a P(fin) égal à 1`() {
            val distribution = sample.distribution("chats")

            assertThat(distribution.endProbability).isEqualTo(1.0)
            assertThat(distribution.totalMass).isEqualTo(10.0)
            assertThat(distribution.continuations).isEmpty()
            assertThat(distribution.isUnknownPrefix).isFalse()
        }

        @Test
        fun `un préfixe de longueur maximale est reconnu, un caractère de plus ne l'est pas`() {
            val key = "oe".repeat(64)
            val lexicon = LexiconFixtures.lexicon(listOf("œ".repeat(64) to 9L, "oe" to 1L))

            assertThat(lexicon.distribution(key).endProbability).isEqualTo(1.0)
            assertThat(lexicon.distribution(key.dropLast(1)).continuations).containsExactly('e')
            assertThat(lexicon.distribution(key + "e").isUnknownPrefix).isTrue()
        }

        @Test
        fun `les fréquences extrêmes ne débordent pas`() {
            val max = Long.MAX_VALUE
            val lexicon = LexiconFixtures.lexicon(listOf("a" to max, "aa" to max, "ab" to max, "b" to 1L))

            val root = lexicon.distribution("")
            val a = lexicon.distribution("a")

            assertThat(root.probability('b')).isPositive().isLessThan(1e-18)
            assertThat(root.continuations).containsExactly('a', 'b')
            assertThat(root.totalMass).isCloseTo(3.0 * max, within(3.0 * max * 1e-3))
            assertThat(a.endProbability).isCloseTo(1.0 / 3, within(1e-12))
            assertThat(a.probability('a')).isCloseTo(1.0 / 3, within(1e-12))
            assertThat(sumOf(root)).isCloseTo(1.0, within(1e-12))
        }

        @Test
        fun `la plus petite fréquence garde une probabilité non nulle`() {
            val lexicon = LexiconFixtures.lexicon(listOf("a" to 1L, "b" to Long.MAX_VALUE))

            assertThat(lexicon.distribution("").probability('a')).isEqualTo(1.0 / Long.MAX_VALUE.toDouble())
        }

        @Test
        fun `le premier et le dernier caractère de l'alphabet sont des continuations`() {
            val lexicon = LexiconFixtures.lexicon(listOf("'s" to 1L, "-t" to 1L, "z" to 2L))

            val distribution = lexicon.distribution("")

            assertThat(distribution.continuations).containsExactly('\'', '-', 'z')
            assertThat(distribution.probability('\'')).isEqualTo(0.25)
            assertThat(distribution.probability('z')).isEqualTo(0.5)
        }
    }
}
