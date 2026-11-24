package nextglyph.tools.ngrams

import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramPruning
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.WordEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class NgramTrainerTest {

    private val keepAll = NgramPruning(minBigramCount = 1, minTrigramCount = 1, minTrigramGain = 0.0)

    private fun vocabulary(language: Language, vararg forms: String) =
        NgramVocabulary(language, forms.map { WordEntry(it, 10) })

    private fun trained(language: Language, vocabulary: NgramVocabulary, pruning: NgramPruning, vararg lines: String): NgramModel {
        val trainer = NgramTrainer(language, vocabulary, pruning)
        lines.forEach(trainer::add)
        return NgramModel.load(ByteBuffer.wrap(trainer.build()))
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `les phrases d'une ligne sont apprises séparément, avec leur début de phrase`() {
            val vocabulary = vocabulary(Language.FR, "je", "suis", "là", "tu", "es")
            val trainer = NgramTrainer(Language.FR, vocabulary, keepAll)

            trainer.add("Je suis là. Tu es là.")

            assertThat(trainer.sentences).isEqualTo(2)
            assertThat(trainer.tokens).isEqualTo(6)
            val model = NgramModel.load(ByteBuffer.wrap(trainer.build()))
            assertThat(model.next(emptyList(), 5).map { it.text }).containsExactlyInAnyOrder("je", "tu")
            assertThat(model.next(listOf("là"), 5)).isEmpty()
        }

        @Test
        fun `les élisions françaises sont des mots à part`() {
            val vocabulary = vocabulary(Language.FR, "l'", "école", "est", "là")

            val model = trained(Language.FR, vocabulary, keepAll, "L'école est là")

            assertThat(model.next(emptyList(), 1).map { it.text }).containsExactly("l'")
            assertThat(model.next(listOf("l'"), 1).map { it.text }).containsExactly("école")
        }

        @Test
        fun `les noms propres servent de contexte sans être proposés`() {
            val vocabulary = vocabulary(Language.EN, "I", "see", "Tom", "and", "run")

            val model = trained(Language.EN, vocabulary, keepAll, "I see Tom and run", "I see Tom and run")

            assertThat(model.next(listOf("see"), 5)).isEmpty()
            assertThat(model.next(listOf("Tom"), 5).map { it.text }).containsExactly("and")
            assertThat(model.next(listOf("I", "see"), 5)).isEmpty()
        }

        @Test
        fun `les mots inconnus de la liste cassent le contexte et ne sont pas proposés`() {
            val vocabulary = vocabulary(Language.FR, "je", "suis", "là")

            val model = trained(Language.FR, vocabulary, keepAll, "Je zorglub suis là")

            assertThat(model.next(listOf("je"), 5)).isEmpty()
            assertThat(model.next(listOf("suis"), 5).map { it.text }).containsExactly("là")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une ligne vide ou sans mot n'ajoute rien`() {
            val trainer = NgramTrainer(Language.FR, vocabulary(Language.FR, "je"), keepAll)

            trainer.add("")
            trainer.add("  ")
            trainer.add("123 !")

            assertThat(trainer.tokens).isZero()
        }

        @Test
        fun `un mot rare est élagué avec l'élagage par défaut`() {
            val vocabulary = vocabulary(Language.FR, "je", "suis")

            val model = trained(Language.FR, vocabulary, NgramTrainer.DEFAULT_PRUNING, "Je suis")

            assertThat(model.next(listOf("je"), 5)).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `l'élagage par défaut est celui du format`() {
            assertThat(NgramTrainer.DEFAULT_PRUNING).isEqualTo(NgramPruning())
        }

        @Test
        fun `un mot compté deux fois est gardé avec l'élagage par défaut`() {
            val vocabulary = vocabulary(Language.FR, "je", "suis")

            val model = trained(Language.FR, vocabulary, NgramTrainer.DEFAULT_PRUNING, "Je suis", "Je suis")

            assertThat(model.next(listOf("je"), 5).map { it.text }).containsExactly("suis")
        }

        @Test
        fun `une phrase d'un seul mot`() {
            val vocabulary = vocabulary(Language.FR, "oui")

            val model = trained(Language.FR, vocabulary, keepAll, "Oui !")

            assertThat(model.next(emptyList(), 5).map { it.text }).containsExactly("oui")
        }
    }
}
