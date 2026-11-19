package nextglyph.tools.analysis

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.tools.wordlists.Language
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BilingualTest {

    private fun lexicon(tag: String, vararg words: Pair<String, Long>): Lexicon {
        val builder = LexiconBuilder(tag)
        for ((w, f) in words) builder.add(w, f)
        return builder.build()
    }

    private val fr = lexicon("fr", "le" to 10, "la" to 10, "chat" to 5, "table" to 5)
    private val en = lexicon("en", "the" to 10, "cat" to 5, "table" to 5)
    private val membership = LanguageEvidence(fr, en, EvidenceMode.MEMBERSHIP)
    private val frequency = LanguageEvidence(fr, en, EvidenceMode.FREQUENCY)

    private fun score(window: Int = 2, initialFr: Double = 0.75) = LanguageScore(window, initialFr, membership::of)

    private fun words(vararg w: Pair<String, Language>) = w.map { MixedWord(it.first, it.second) }

    @Nested
    inner class HappyPath {
        @Test
        fun `indice par appartenance - mot commun compté pour les deux langues`() {
            assertThat(membership.of("le")).isEqualTo(1.0 to 0.0)
            assertThat(membership.of("the")).isEqualTo(0.0 to 1.0)
            assertThat(membership.of("table")).isEqualTo(1.0 to 1.0)
            assertThat(membership.mode).isEqualTo(EvidenceMode.MEMBERSHIP)
        }

        @Test
        fun `indice par fréquence relative - mot commun réparti`() {
            // table : 5 / 30 en français, 5 / 20 en anglais → (0,4 ; 0,6).
            val (f, e) = frequency.of("table")

            assertThat(f).isCloseTo(0.4, within(1e-3))
            assertThat(e).isCloseTo(0.6, within(1e-3))
            assertThat(frequency.of("le")).isEqualTo(1.0 to 0.0)
            assertThat(frequency.of("cat")).isEqualTo(0.0 to 1.0)
        }

        @Test
        fun `score de langue - fenêtre glissante et langue préférée`() {
            val s = score()

            assertThat(s.piFr).isEqualTo(0.75)
            s.observe("the")
            assertThat(s.piFr).isEqualTo(0.375)
            assertThat(s.dominant).isEqualTo(Language.EN)
            s.observe("the")
            assertThat(s.piFr).isEqualTo(0.25)
            s.observe("le") // la fenêtre de 2 oublie le premier « the »
            assertThat(s.piFr).isCloseTo(1.75 / 3, within(1e-12))
            assertThat(s.dominant).isEqualTo(Language.FR)
        }

        @Test
        fun `convergence - délais calculés à la main`() {
            val sequence = words(
                "le" to Language.FR, "la" to Language.FR,
                "the" to Language.EN, "cat" to Language.EN, "the" to Language.EN,
                "le" to Language.FR,
            )

            val c = BilingualAnalysis.convergence(sequence) { score() }

            assertThat(c.words).isEqualTo(6)
            assertThat(c.accuracy).isEqualTo(0.5)
            assertThat(c.delays).containsExactly(2, 1)
            assertThat(c.censored).isEqualTo(1)
            assertThat(c.switches).isEqualTo(2)
            assertThat(c.mean).isEqualTo(1.5)
            assertThat(c.percentile(50)).isEqualTo(1)
            assertThat(c.percentile(100)).isEqualTo(2)
        }

        @Test
        fun `fausse validation due à la mauvaise langue`() {
            // « bonjou » (anglais, hors lexique) : complété en « bonjour » tant que le français domine ;
            // avec l'anglais seul, aucun candidat : inséré tel quel.
            val sources = mapOf(
                Language.FR to CandidateSource(lexicon("fr", "bonjour" to 90, "bonne" to 10, "le" to 100), KeyboardLayout.AZERTY),
                Language.EN to CandidateSource(lexicon("en", "bonus" to 50, "the" to 100), KeyboardLayout.AZERTY),
            )

            val c = BilingualAnalysis.falseValidations(words("bonjou" to Language.EN), sources, listOf(Thresholds(0.6, 2.0)), { score() }).single()

            assertThat(c.words).isEqualTo(1)
            assertThat(c.automatic).isEqualTo(1)
            assertThat(c.falseValidations).isEqualTo(1)
            assertThat(c.referenceAutomatic).isZero()
            assertThat(c.referenceFalse).isZero()
            assertThat(c.attributable).isEqualTo(1)
            assertThat(c.falseRate).isEqualTo(1.0)
            assertThat(c.referenceFalseRate).isZero()
            assertThat(c.attributableShare).isEqualTo(1.0)
        }

        @Test
        fun `complétion correcte dans les deux réglages`() {
            val sources = mapOf(
                Language.FR to CandidateSource(lexicon("fr", "bonjour" to 90, "bonne" to 10, "le" to 100), KeyboardLayout.AZERTY),
                Language.EN to CandidateSource(lexicon("en", "bonus" to 50, "the" to 100), KeyboardLayout.AZERTY),
            )

            val c = BilingualAnalysis.falseValidations(
                words("bonjour" to Language.FR, "le" to Language.FR), sources, listOf(Thresholds(0.6, 2.0)), { score() },
            ).single()

            // bonjour complété dès « b », le dès « l » (« l » n'est pas un mot), dans les deux réglages.
            assertThat(c.words).isEqualTo(2)
            assertThat(c.automatic).isEqualTo(2)
            assertThat(c.referenceAutomatic).isEqualTo(2)
            assertThat(c.falseValidations).isZero()
            assertThat(c.attributableShare).isZero()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `le score suit les mots validés pendant la simulation`() {
            var created: LanguageScore? = null
            val sources = mapOf(Language.FR to CandidateSource(fr, KeyboardLayout.AZERTY), Language.EN to CandidateSource(en, KeyboardLayout.AZERTY))

            BilingualAnalysis.falseValidations(words("the" to Language.EN, "cat" to Language.EN), sources, listOf(Thresholds(0.6, 2.0)), {
                score().also { created = it }
            })

            assertThat(created!!.piFr).isEqualTo(0.25) // (0 + 0,75) / (2 + 1)
        }

        @Test
        fun `paramètres du score invalides refusés`() {
            assertThatThrownBy { score(window = 0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("fenêtre")
            assertThatThrownBy { score(initialFr = 1.01) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("français")
            assertThatThrownBy { score(initialFr = -0.01) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `mot inconnu des deux lexiques - aucun indice, score inchangé`() {
            val s = score()

            assertThat(frequency.of("xyz")).isEqualTo(0.0 to 0.0)
            s.observe("xyz")
            assertThat(s.piFr).isEqualTo(0.75)
        }

        @Test
        fun `une langue sans source est refusée`() {
            val only = mapOf(Language.FR to CandidateSource(fr, KeyboardLayout.AZERTY))

            assertThatThrownBy { BilingualAnalysis.falseValidations(words("le" to Language.FR), only, listOf(Thresholds(0.6, 2.0)), { score() }) }
                .isInstanceOf(NoSuchElementException::class.java)
        }

        @Test
        fun `un changement jamais rattrapé puis un autre - deux censures`() {
            val c = BilingualAnalysis.convergence(words("the" to Language.EN, "le" to Language.FR, "the" to Language.EN)) { score() }

            assertThat(c.accuracy).isZero()
            assertThat(c.delays).containsExactly(1, 1)
            assertThat(c.censored).isEqualTo(2)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `corpus vide`() {
            val c = BilingualAnalysis.convergence(emptyList()) { score() }

            assertThat(c.accuracy).isZero()
            assertThat(c.switches).isZero()
            assertThat(c.mean).isZero()
            assertThat(c.percentile(90)).isZero()
        }

        @Test
        fun `égalité des deux langues - le français domine`() {
            assertThat(score(initialFr = 0.5).dominant).isEqualTo(Language.FR)
            assertThat(score(initialFr = 0.49).dominant).isEqualTo(Language.EN)
        }

        @Test
        fun `fenêtre d'un mot - seul le dernier compte`() {
            val s = score(window = 1, initialFr = 0.0)

            s.observe("le")
            s.observe("table")
            assertThat(s.piFr).isCloseTo(1.0 / 3, within(1e-12)) // (1 + 0) / (1 + 1 + 1)
        }

        @Test
        fun `fautes simulées - chaque mot est compté une fois`() {
            val sources = mapOf(Language.FR to CandidateSource(fr, KeyboardLayout.AZERTY), Language.EN to CandidateSource(en, KeyboardLayout.AZERTY))

            val c = BilingualAnalysis.falseValidations(
                words("chat" to Language.FR, "cat" to Language.EN), sources, listOf(Thresholds(0.6, 2.0), Thresholds(0.9, 5.0)),
                { score() }, TypoModel(KeyboardLayout.AZERTY, 1.0), Random(3),
            )

            assertThat(c).hasSize(2).allMatch { it.words == 2L }
        }

        @Test
        fun `fausse aussi dans la référence - non attribuable`() {
            val c = MixedCounters(Thresholds(0.6, 2.0))

            c.add(SpaceOutcome.AUTO_WRONG, SpaceOutcome.AUTO_WRONG_TYPO)
            c.add(SpaceOutcome.LITERAL_OTHER, SpaceOutcome.AUTO_CORRECTION)

            assertThat(c.falseValidations).isEqualTo(1)
            assertThat(c.attributable).isZero()
            assertThat(c.referenceFalseRate).isEqualTo(0.5)
            assertThat(c.automatic).isEqualTo(1)
        }
    }
}
