package nextglyph.tools.analysis

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SpaceRuleTest {

    private val spec = Thresholds(0.6, 2.0)
    private val none = Decision(false, null, 0.0, 0.0)
    private val word = Decision(true, null, 0.0, 0.0)
    private fun toward(w: String, p1: Double = 0.9, p2: Double = 0.1) = Decision(false, w, p1, p2)

    @Nested
    inner class HappyPath {
        @Test
        fun `cas 1 - un mot du lexique est inséré tel quel`() {
            assertThat(SpaceRule.outcome(word, "le", "le", spec)).isEqualTo(SpaceOutcome.LITERAL_INTENDED)
            assertThat(SpaceRule.outcome(word, "bon", "bonjour", spec)).isEqualTo(SpaceOutcome.LITERAL_OTHER)
        }

        @Test
        fun `cas 2 - complétion, correction, fausses validations`() {
            assertThat(SpaceRule.outcome(toward("bonjour"), "bonj", "bonjour", spec)).isEqualTo(SpaceOutcome.AUTO_COMPLETION)
            assertThat(SpaceRule.outcome(toward("bonjour"), "bonjoir", "bonjour", spec)).isEqualTo(SpaceOutcome.AUTO_CORRECTION)
            assertThat(SpaceRule.outcome(toward("bonjour"), "bonjoux", "bonjoux", spec)).isEqualTo(SpaceOutcome.AUTO_WRONG)
            assertThat(SpaceRule.outcome(toward("bonsoir"), "bonjoir", "bonjour", spec)).isEqualTo(SpaceOutcome.AUTO_WRONG_TYPO)
        }

        @Test
        fun `cas 3 - aucun candidat dominant, texte inséré tel quel`() {
            assertThat(SpaceRule.outcome(toward("bonjour", 0.5, 0.1), "bonj", "bonjour", spec)).isEqualTo(SpaceOutcome.LITERAL_OTHER)
            assertThat(SpaceRule.outcome(none, "xyz", "xyz", spec)).isEqualTo(SpaceOutcome.LITERAL_INTENDED)
        }

        @Test
        fun `utilisateur attentif - espace dès que le mot voulu s'affiche`() {
            val decisions = listOf(none, none, toward("bonjour"), toward("bonjour"), word)

            assertThat(SpaceRule.attentive(decisions, "bonjo", "bonjo", spec)).isEqualTo(SpaceOutcome.LITERAL_INTENDED to 5)
            val full = listOf(none, none, toward("bonjour"), none, none, none, word)
            assertThat(SpaceRule.attentive(full, "bonjour", "bonjour", spec)).isEqualTo(SpaceOutcome.AUTO_COMPLETION to 3)
        }

        @Test
        fun `utilisateur attentif - ignore un mauvais candidat puis tape tout`() {
            val decisions = listOf(none, none, toward("bonjour"), none, word)

            assertThat(SpaceRule.attentive(decisions, "bonne", "bonne", spec)).isEqualTo(SpaceOutcome.LITERAL_INTENDED to 5)
        }

        @Test
        fun `utilisateur attentif - une faute corrigée avant la fin`() {
            val decisions = listOf(none, none, none, toward("bonjour"), none, none, none)

            assertThat(SpaceRule.attentive(decisions, "bonhour", "bonjour", spec)).isEqualTo(SpaceOutcome.AUTO_CORRECTION to 4)
        }

        @Test
        fun `fautes - une substitution par une voisine, apostrophe intacte`() {
            val typos = TypoModel(KeyboardLayout.AZERTY, 1.0)

            val typed = typos.apply("j'ai", Random(1))

            assertThat(typed).hasSize(4)
            assertThat(typed[1]).isEqualTo('\'')
            assertThat(KeyboardLayout.AZERTY.areNeighbors('j', typed[0])).isTrue()
            assertThat(KeyboardLayout.AZERTY.areNeighbors('a', typed[2])).isTrue()
            assertThat(KeyboardLayout.AZERTY.areNeighbors('i', typed[3])).isTrue()
        }

        @Test
        fun `fautes - reproductibles pour une même graine`() {
            val typos = TypoModel(KeyboardLayout.QWERTY, 0.3)

            val a = (1..50).map { typos.apply("keyboard", Random(7)) }
            assertThat(a.toSet()).hasSize(1)
            val varied = Random(7).let { r -> (1..200).map { typos.apply("keyboard", r) } }
            assertThat(varied.toSet().size).isGreaterThan(10)
            assertThat(varied).contains("keyboard")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `seuils invalides refusés`() {
            assertThatThrownBy { Thresholds(1.1, 2.0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minProb")
            assertThatThrownBy { Thresholds(-0.1, 2.0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minProb")
            assertThatThrownBy { Thresholds(0.5, 0.99) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minRatio")
            assertThatThrownBy { Thresholds(0.5, 2.0, 0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minLength")
        }

        @Test
        fun `taux de fautes hors de 0-1 refusé`() {
            assertThatThrownBy { TypoModel(KeyboardLayout.AZERTY, 1.01) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { TypoModel(KeyboardLayout.AZERTY, -0.01) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `utilisateur attentif - nombre de décisions incohérent refusé`() {
            assertThatThrownBy { SpaceRule.attentive(listOf(none), "ab", "ab", spec) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("ab")
            assertThatThrownBy { SpaceRule.attentive(emptyList(), "", "", spec) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `un mot sans lettre de la disposition n'est jamais modifié`() {
            assertThat(TypoModel(KeyboardLayout.AZERTY, 1.0).apply("'-", Random(0))).isEqualTo("'-")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `seuils exactement atteints - le candidat domine`() {
            assertThat(SpaceRule.outcome(toward("bonjour", 0.6, 0.3), "bon", "bonjour", spec)).isEqualTo(SpaceOutcome.AUTO_COMPLETION)
            assertThat(SpaceRule.outcome(toward("bonjour", 0.6, 0.31), "bon", "bonjour", spec)).isEqualTo(SpaceOutcome.LITERAL_OTHER)
        }

        @Test
        fun `longueur minimale - k égal à la limite autorisé, k inférieur refusé`() {
            val t = Thresholds(0.6, 2.0, 3)

            assertThat(SpaceRule.outcome(toward("bonjour"), "bon", "bonjour", t)).isEqualTo(SpaceOutcome.AUTO_COMPLETION)
            assertThat(SpaceRule.outcome(toward("bonjour"), "bo", "bonjour", t)).isEqualTo(SpaceOutcome.LITERAL_OTHER)
        }

        @Test
        fun `mot d'une lettre - l'unique décision est la dernière`() {
            assertThat(SpaceRule.attentive(listOf(toward("a")), "z", "a", spec)).isEqualTo(SpaceOutcome.AUTO_CORRECTION to 1)
            assertThat(SpaceRule.attentive(listOf(word), "a", "a", spec)).isEqualTo(SpaceOutcome.LITERAL_INTENDED to 1)
        }

        @Test
        fun `taux de fautes nul - aucun changement`() {
            assertThat(TypoModel(KeyboardLayout.AZERTY, 0.0).apply("bonjour", Random(3))).isEqualTo("bonjour")
        }

        @Test
        fun `nature des issues`() {
            assertThat(SpaceOutcome.entries.filter { it.isAutomatic })
                .containsExactly(SpaceOutcome.AUTO_COMPLETION, SpaceOutcome.AUTO_CORRECTION, SpaceOutcome.AUTO_WRONG, SpaceOutcome.AUTO_WRONG_TYPO)
            assertThat(SpaceOutcome.entries.filter { it.isFalse }).containsExactly(SpaceOutcome.AUTO_WRONG, SpaceOutcome.AUTO_WRONG_TYPO)
            assertThat(Thresholds(0.6, 2.0).toString()).isEqualTo("minProb=0.6 minRatio=2.0 minLength=1")
        }
    }
}
