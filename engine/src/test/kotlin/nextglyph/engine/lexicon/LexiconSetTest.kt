package nextglyph.engine.lexicon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LexiconSetTest {

    /** Fichier « fréquences » : chat 40, chien 30, à 30. */
    private val main = LexiconFixtures.lexicon(listOf("chat" to 40L, "chien" to 30L, "à" to 30L), "fr")

    /** Fichier « couverture » : chaton 5, chat 10 (complément de compte), a 20. */
    private val coverage = LexiconFixtures.lexicon(listOf("chaton" to 5L, "chat" to 10L, "a" to 20L), "fr-CA")

    private val set = LexiconSet(main, coverage)

    @Nested
    inner class HappyPath {
        @Test
        fun `les masses s'additionnent dans la distribution`() {
            val d = set.distribution("cha")

            // chat 50 (40 + 10) dont 0 de fin à « cha », chaton 5 : seule continuation « t ».
            assertThat(d.isUnknownPrefix).isFalse()
            assertThat(d.totalMass).isCloseTo(55.0, within(0.1))
            assertThat(d.probability('t')).isCloseTo(1.0, within(1e-9))
            assertThat(set.distribution("chat").endProbability).isCloseTo(50.0 / 55.0, within(1e-3))
            assertThat(set.distribution("").probability('a')).isCloseTo(50.0 / 135.0, within(1e-3))
            assertThat(set.totalMass).isCloseTo(135.0, within(0.1))
        }

        @Test
        fun `masse d'un préfixe - sans lire ses enfants`() {
            assertThat(main.prefixMass("")).isCloseTo(100.0, within(0.1))
            assertThat(main.prefixMass("ch")).isCloseTo(70.0, within(0.1))
            assertThat(main.prefixMass("chi")).isCloseTo(30.0, within(0.1))
            assertThat(main.prefixMass("chat")).isCloseTo(40.0, within(0.1))
            assertThat(main.prefixMass("zz")).isZero()
            assertThat(set.prefixMass("chat")).isCloseTo(55.0, within(0.1))
        }

        @Test
        fun `les fréquences d'une même forme s'additionnent`() {
            assertThat(set.displayForms("chat")).containsExactly(LexiconEntry("chat", "chat", 50L))
            assertThat(set.displayForms("a")).containsExactly(LexiconEntry("à", "a", 30L), LexiconEntry("a", "a", 20L))
        }

        @Test
        fun `complétions fusionnées et ordonnées`() {
            assertThat(set.completions("ch", 3).map { it.form to it.frequency })
                .containsExactly("chat" to 50L, "chien" to 30L, "chaton" to 5L)
            assertThat(set.completions("ch", 1).map { it.form }).containsExactly("chat")
        }

        @Test
        fun `appartenance, langue et ajout d'un membre`() {
            val personal = LexiconFixtures.lexicon(listOf("chouquette" to 3L), "fr")

            val extended = set + personal

            assertThat(set.containsKey("chaton")).isTrue()
            assertThat(set.containsKey("cha")).isFalse()
            assertThat(set.language).isEqualTo("fr")
            assertThat(set.languageTag).isEqualTo("fr")
            assertThat(extended.members).hasSize(3)
            assertThat(extended.completions("cho", 2).map { it.form }).containsExactly("chouquette")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette un ensemble vide`() {
            assertThatThrownBy { LexiconSet(emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("au moins un lexique")
        }

        @Test
        fun `rejette des langues différentes`() {
            val english = LexiconFixtures.lexicon(listOf("cat" to 1L), "en")

            assertThatThrownBy { LexiconSet(main, english) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("même langue")
        }

        @Test
        fun `rejette une limite négative`() {
            assertThatThrownBy { set.completions("ch", -1) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `préfixe inconnu de tous les membres`() {
            assertThat(set.distribution("zz").isUnknownPrefix).isTrue()
            assertThat(set.completions("zz", 3)).isEmpty()
            assertThat(set.displayForms("zz")).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un seul membre - mêmes réponses que le lexique`() {
            val single = LexiconSet(main)

            assertThat(single.distribution("ch").totalMass).isEqualTo(main.distribution("ch").totalMass)
            assertThat(single.completions("ch", 2)).isEqualTo(main.completions("ch", 2))
            assertThat(single.displayForms("a")).isEqualTo(main.displayForms("a"))
        }

        @Test
        fun `préfixe connu d'un seul membre`() {
            assertThat(set.distribution("chato").totalMass).isCloseTo(5.0, within(0.01))
            assertThat(set.completions("chi", 5).map { it.form }).containsExactly("chien")
        }

        @Test
        fun `limite nulle et fréquences saturées`() {
            val huge = LexiconFixtures.lexicon(listOf("x" to Long.MAX_VALUE), "fr")
            val saturated = LexiconSet(huge, LexiconFixtures.lexicon(listOf("x" to 5L), "fr"))

            assertThat(set.completions("ch", 0)).isEmpty()
            assertThat(saturated.displayForms("x").single().frequency).isEqualTo(Long.MAX_VALUE)
        }
    }
}
