package nextglyph.tools.ngrams

import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramPruning
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.WordEntry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class NgramEvaluatorTest {

    private val keepAll = NgramPruning(minBigramCount = 1, minTrigramCount = 1, minTrigramGain = 0.0)

    private val vocabulary = NgramVocabulary(
        Language.FR,
        listOf("je", "tu", "suis", "es", "là", "ici", "Paris").map { WordEntry(it, 10) },
    )

    private fun model(vararg lines: String): NgramModel {
        val trainer = NgramTrainer(Language.FR, vocabulary, keepAll)
        lines.forEach(trainer::add)
        return NgramModel.load(ByteBuffer.wrap(trainer.build()))
    }

    private fun evaluate(model: NgramModel, vararg lines: String): NgramHitRates {
        val evaluator = NgramEvaluator(model, vocabulary, Language.FR)
        lines.forEach(evaluator::add)
        return evaluator.result()
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `une phrase du modèle est prédite mot à mot`() {
            val model = model("Je suis là", "Je suis là", "Je suis là")

            val hits = evaluate(model, "Je suis là.")

            assertThat(hits).isEqualTo(NgramHitRates(positions = 3, eligible = 3, top1 = 3, top3 = 3))
            assertThat(hits.top1OfEligible).isEqualTo(1.0)
            assertThat(hits.top3OfEligible).isEqualTo(1.0)
            assertThat(hits.top1OfAll).isEqualTo(1.0)
            assertThat(hits.top3OfAll).isEqualTo(1.0)
        }

        @Test
        fun `un mot en deuxième ou troisième proposition compte pour le top 3 seulement`() {
            val model = model("Je suis là", "Je suis là", "Je suis là", "Tu es là")

            val hits = evaluate(model, "Tu suis là")

            // tu : 2e proposition du début de phrase (top 3 seulement) ; suis : jamais après tu, raté ;
            // là : après « suis », le repli sur le bigramme le propose en tête (top 1 et top 3).
            assertThat(hits.positions).isEqualTo(3)
            assertThat(hits.eligible).isEqualTo(3)
            assertThat(hits.top1).isEqualTo(1)
            assertThat(hits.top3).isEqualTo(2)
        }

        @Test
        fun `le contexte du deuxième mot est le début de phrase et le premier mot`() {
            val model = model("Je suis là", "Je suis là", "Je suis là", "Je suis là", "Tu suis là", "Tu es ici", "Tu es ici")

            val hits = evaluate(model, "Je es")

            // je : en tête du début de phrase ; es : après [début, je] seul suis est proposé.
            assertThat(hits.eligible).isEqualTo(2)
            assertThat(hits.top1).isEqualTo(1)
            assertThat(hits.top3).isEqualTo(1)
        }

        @Test
        fun `les phrases d'une ligne sont évaluées séparément`() {
            val model = model("Je suis là", "Je suis là")

            val hits = evaluate(model, "Je suis là. Je suis là.")

            assertThat(hits.positions).isEqualTo(6)
            assertThat(hits.top1).isEqualTo(6)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un mot inconnu, non proposable ou hors du modèle n'est pas éligible mais compte comme position`() {
            val model = model("Je suis là", "Je suis là")

            val hits = evaluate(model, "Zorglub Paris ici")

            assertThat(hits.positions).isEqualTo(3)
            assertThat(hits.eligible).isZero()
            assertThat(hits.top1OfEligible).isZero()
            assertThat(hits.top3OfEligible).isZero()
            assertThat(hits.top1OfAll).isZero()
        }

        @Test
        fun `un mot absent du vocabulaire du modèle à cause de l'élagage n'est pas éligible`() {
            val model = model("Je suis là")
            val evaluator = NgramEvaluator(model, vocabulary, Language.FR)

            evaluator.add("Je ici")

            assertThat(evaluator.result().positions).isEqualTo(2)
            assertThat(evaluator.result().eligible).isEqualTo(1)
        }

        @Test
        fun `sans position, les taux valent zéro`() {
            val hits = NgramHitRates(0, 0, 0, 0)

            assertThat(hits.top1OfAll).isZero()
            assertThat(hits.top3OfAll).isZero()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un taux est le rapport exact des comptes`() {
            val hits = NgramHitRates(positions = 8, eligible = 4, top1 = 1, top3 = 3)

            assertThat(hits.top1OfEligible).isCloseTo(0.25, within(1e-12))
            assertThat(hits.top3OfEligible).isCloseTo(0.75, within(1e-12))
            assertThat(hits.top1OfAll).isCloseTo(0.125, within(1e-12))
            assertThat(hits.top3OfAll).isCloseTo(0.375, within(1e-12))
        }

        @Test
        fun `une ligne vide n'ajoute aucune position`() {
            val model = model("Je suis là")

            assertThat(evaluate(model, "", "  ").positions).isZero()
        }

        @Test
        fun `l'évaluation ne modifie pas le modèle`() {
            val model = model("Je suis là", "Je suis là")
            val before = model.next(emptyList(), 3)

            evaluate(model, "Tu es ici")

            assertThat(model.next(emptyList(), 3)).isEqualTo(before)
        }
    }
}
