package nextglyph.tools.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SpaceSimulationTest {

    private val spec = Thresholds(0.6, 2.0)
    private val strict = Thresholds(0.95, 2.0)
    private val none = Decision(false, null, 0.0, 0.0)

    /** « bon » → bonjour (0,9 / 0,1) ; « bonjour » et « bonne » sont des mots ; « bonjoux », « bonjoir » → bonjour. */
    private val decide: (String) -> Decision = { t ->
        when (t) {
            "bon", "bonjoux", "bonjoir" -> Decision(false, "bonjour", 0.9, 0.1)
            "bonjour", "bonne" -> Decision(true, null, 0.0, 0.0)
            else -> none
        }
    }

    private fun counts(sim: SpaceSimulation, t: Thresholds) = sim.counters.single { it.thresholds == t }

    @Nested
    inner class HappyPath {
        @Test
        fun `complétion à la troisième lettre - frappes et KSPC`() {
            val sim = SpaceSimulation(listOf(spec, strict))

            sim.run(mapOf("bonjour" to 2L), decide)

            val c = counts(sim, spec)
            assertThat(c.words).isEqualTo(2)
            assertThat(c.attentive[SpaceOutcome.AUTO_COMPLETION.ordinal]).isEqualTo(2)
            assertThat(c.keystrokes).isEqualTo(8)
            assertThat(c.baselineKeystrokes).isEqualTo(16)
            assertThat(c.kspc).isEqualTo(0.5)
            assertThat(c.kspcWithRepair).isEqualTo(0.5)
            assertThat(c.correctAutoRate).isEqualTo(1.0)
            assertThat(c.falseRate).isZero()
            val s = counts(sim, strict)
            assertThat(s.attentive[SpaceOutcome.LITERAL_INTENDED.ordinal]).isEqualTo(2)
            assertThat(s.kspc).isEqualTo(1.0)
            assertThat(s.automatic).isZero()
        }

        @Test
        fun `espace à chaque k - issues par nombre de lettres`() {
            val sim = SpaceSimulation(listOf(spec))

            sim.run(mapOf("bonjour" to 1L), decide)

            val rows = counts(sim, spec).everyPrefix
            assertThat(rows[0][SpaceOutcome.LITERAL_OTHER.ordinal]).isEqualTo(1)
            assertThat(rows[2][SpaceOutcome.AUTO_COMPLETION.ordinal]).isEqualTo(1)
            assertThat(rows[6][SpaceOutcome.LITERAL_INTENDED.ordinal]).isEqualTo(1)
            assertThat(rows.sumOf { it.sum() }).isEqualTo(7)
            assertThat(counts(sim, spec).everyPrefixFalseRate()).isZero()
        }

        @Test
        fun `mot hors lexique - fausse validation à la dernière lettre`() {
            val sim = SpaceSimulation(listOf(spec))

            sim.run(mapOf("bonjour" to 3L, "bonjoux" to 1L), decide)

            val c = counts(sim, spec)
            assertThat(c.attentive[SpaceOutcome.AUTO_WRONG.ordinal]).isEqualTo(1)
            // bonjoux : complétion fausse à k = 3 (non suivie), puis fausse à k = 7.
            assertThat(c.automatic).isEqualTo(4)
            assertThat(c.falseValidations).isEqualTo(1)
            assertThat(c.falseRate).isEqualTo(0.25)
            assertThat(c.falsePerWord).isEqualTo(0.25)
            assertThat(c.correctAutoRate).isEqualTo(0.75)
            assertThat(c.keystrokes).isEqualTo(3 * 4L + 8)
            assertThat(c.kspcWithRepair).isCloseTo((20.0 + 2) / 32, within(1e-12))
            assertThat(c.falsePerWordByLength(6)).isEqualTo(0.25)
            assertThat(c.falsePerWordByLength(0)).isZero()
            // Espace à tout k : 4 complétions correctes à k = 3, 2 fausses (k = 3 et 7) pour bonjoux.
            assertThat(c.everyPrefixFalseRate()).isCloseTo(2.0 / 5, within(1e-12))
            assertThat(c.everyPrefixFalseRate(2)).isCloseTo(1.0 / 4, within(1e-12))
            assertThat(c.everyPrefixFalseRate(6)).isEqualTo(1.0)
        }

        @Test
        fun `faute corrigée et faute mal corrigée`() {
            val sim = SpaceSimulation(listOf(spec))

            sim.record("bonjour", "bonjoir", 1) { if (it == "bonjoir") Decision(false, "bonjour", 0.9, 0.1) else none }
            // L'utilisateur attentif s'arrête dès « bon » (bonjour affiché) : la faute n'est jamais tapée.
            sim.record("bonjour", "bonjoir", 1, decide)
            sim.record("bonsoir", "bonjoir", 1, decide)

            val c = counts(sim, spec)
            assertThat(c.attentive[SpaceOutcome.AUTO_CORRECTION.ordinal]).isEqualTo(1)
            assertThat(c.attentive[SpaceOutcome.AUTO_COMPLETION.ordinal]).isEqualTo(1)
            // « bonsoir » tapé « bonjoir » : k = 3 propose bonjour (faux, ignoré), k = 7 aussi : fausse validation.
            assertThat(c.attentive[SpaceOutcome.AUTO_WRONG_TYPO.ordinal]).isEqualTo(1)
        }

        @Test
        fun `fautes simulées - chaque occurrence compte une fois`() {
            val sim = SpaceSimulation(listOf(spec))

            sim.run(mapOf("bonjour" to 5L, "bonne" to 4L), decide, TypoModel(KeyboardLayout.AZERTY, 0.5), Random(1))

            val c = counts(sim, spec)
            assertThat(c.words).isEqualTo(9)
            assertThat(c.baselineKeystrokes).isEqualTo(5 * 8L + 4 * 6)
            assertThat(c.attentive[SpaceOutcome.LITERAL_OTHER.ordinal]).isPositive()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `longueurs différentes refusées`() {
            assertThatThrownBy { SpaceSimulation(listOf(spec)).record("bonjour", "bonjou", 1, decide) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("bonjou")
            assertThatThrownBy { SpaceSimulation(listOf(spec)).record("", "", 1, decide) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `aucun mot - taux nuls sans division par zéro`() {
            val c = SpaceSimulation(listOf(spec)).counters.single()

            assertThat(c.falseRate).isZero()
            assertThat(c.kspc).isZero()
            assertThat(c.everyPrefixFalseRate()).isZero()
            assertThat(c.falsePerWordByLength(3)).isZero()
            assertThat(ThresholdCounters.ratio(5, 0)).isZero()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `zéro occurrence - rien n'est compté`() {
            val sim = SpaceSimulation(listOf(spec))

            sim.record("bonjour", "bonjour", 0, decide)

            assertThat(sim.counters.single().words).isZero()
            assertThat(sim.counters.single().baselineKeystrokes).isZero()
        }

        @Test
        fun `les mots de 8 lettres et plus partagent la dernière tranche`() {
            val sim = SpaceSimulation(listOf(spec))

            sim.run(mapOf("abcdefgh" to 1L, "abcdefghij" to 1L), { none })

            val c = sim.counters.single()
            assertThat(c.attentiveByLength[7].sum()).isEqualTo(2)
            assertThat(c.everyPrefix[7].sum()).isEqualTo(1 + 3L)
            assertThat(c.everyPrefix[6].sum()).isEqualTo(2)
        }

        @Test
        fun `taux de fautes nul passé explicitement - identique à sans faute`() {
            val a = SpaceSimulation(listOf(spec)).apply { run(mapOf("bonjour" to 3L), decide, TypoModel(KeyboardLayout.AZERTY, 0.0)) }
            val b = SpaceSimulation(listOf(spec)).apply { run(mapOf("bonjour" to 3L), decide) }

            assertThat(a.counters.single().attentive).isEqualTo(b.counters.single().attentive)
            assertThat(a.counters.single().keystrokes).isEqualTo(b.counters.single().keystrokes)
        }

        @Test
        fun `mot d'une lettre`() {
            val sim = SpaceSimulation(listOf(spec))

            sim.run(mapOf("a" to 1L), { Decision(true, null, 0.0, 0.0) })

            assertThat(sim.counters.single().attentiveByLength[0][SpaceOutcome.LITERAL_INTENDED.ordinal]).isEqualTo(1)
            assertThat(sim.counters.single().kspc).isEqualTo(1.0)
        }
    }
}
