package nextglyph.engine.session

import nextglyph.engine.contacts.ContactContext
import nextglyph.engine.contacts.ContactDirectory
import nextglyph.engine.contacts.ContactName
import nextglyph.engine.contacts.ContactsConfig
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.learn.PersonalDictionary
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramFixtures
import nextglyph.engine.predict.PredictionConfig
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.SpaceEditConfig
import nextglyph.engine.predict.Predictor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PredictionStackTest {

    private val base: Map<String, LexiconSet> = linkedMapOf(
        "fr" to LexiconSet(LexiconFixtures.lexicon(listOf("le" to 1000L, "bonjour" to 500L), "fr")),
        "en" to LexiconSet(LexiconFixtures.lexicon(listOf("the" to 1000L), "en")),
    )
    private val layout = KeyboardLayouts.AZERTY
    private val gwen = listOf(ContactName(givenName = "Gwenaëlle"))

    private fun Predictor.decision(word: String) =
        predict(word, initialLanguageState("fr"), PredictionSettings())

    @Nested
    inner class HappyPath {

        @Test
        fun `sans contact ni mot appris, un seul prédicteur sur les lexiques d'origine`() {
            val stack = PredictionStack.build(base, layout)

            assertThat(stack.directory).isNull()
            assertThat(stack.languages).containsExactly("fr", "en")
            assertThat(stack.forContext(ContactContext(atMessageStart = true, typedCapital = true))).isSameAs(stack.base)
        }

        @Test
        fun `avec des contacts, un prédicteur par niveau`() {
            val stack = PredictionStack.build(base, layout, contacts = gwen)

            assertThat(stack.directory!!.isName("gwenaelle")).isTrue()
            val level0 = stack.forContext(ContactContext())
            val level1 = stack.forContext(ContactContext(atMessageStart = true))
            val level2 = stack.forContext(ContactContext(atMessageStart = true, typedCapital = true))
            assertThat(level0).isSameAs(stack.base)
            assertThat(listOf(level0, level1, level2).distinct()).hasSize(3)
            assertThat(level0.decision("gwen").inserted).isEqualTo("gwen")
            assertThat(level1.decision("gwen").inserted).isEqualTo("Gwenaëlle")
        }

        @Test
        fun `les mots promus du dictionnaire personnel sont prédits`() {
            val personal = PersonalDictionary()
            personal.importWord("zorglub", "fr", 3)

            val stack = PredictionStack.build(base, layout, personal)

            assertThat(stack.base.decision("zorgl").inserted).isEqualTo("zorglub")
        }

        @Test
        fun `les modèles de mot suivant sont donnés à tous les niveaux`() {
            val lexicons = linkedMapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("zz" to 100_000_000L, "ab" to 1000L, "cde" to 1000L, "abc" to 1000L, "de" to 1000L), "fr")))
            val stack = PredictionStack.build(lexicons, layout, contacts = gwen, config = PredictionConfig(spaceEdits = SpaceEditConfig(minScore = 1e-12)))
            val model = NgramFixtures.model(listOf("abc de" to 5, "abc zz" to 5, "ab cde" to 1, "ab zz" to 9))
            val state = stack.base.initialLanguageState("fr")
            assertThat(stack.base.predict("abcde", state, PredictionSettings()).case).isEqualTo(SpaceCase.LITERAL)

            stack.useNgrams(mapOf("fr" to model))

            for (context in listOf(ContactContext(), ContactContext(atMessageStart = true), ContactContext(atMessageStart = true, typedCapital = true))) {
                assertThat(stack.forContext(context).predict("abcde", state, PredictionSettings()).inserted).isEqualTo("abc de")
            }
        }

        @Test
        fun `of enveloppe un prédicteur existant`() {
            val predictor = Predictor(base, layout)

            assertThat(PredictionStack.of(predictor).base).isSameAs(predictor)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `une liste vide est refusée`() {
            assertThatThrownBy { PredictionStack(emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Au moins un")
        }

        @Test
        fun `le nombre de prédicteurs doit suivre les niveaux des contacts`() {
            val directory = ContactDirectory(gwen, base)

            assertThatThrownBy { PredictionStack(listOf(Predictor(base, layout)), directory) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("3 prédicteurs attendus, 1 reçus")
            assertThatThrownBy { PredictionStack(listOf(Predictor(base, layout), Predictor(base, layout))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("1 prédicteurs attendus, 2 reçus")
        }

        @Test
        fun `les prédicteurs doivent porter sur les mêmes langues`() {
            val french = mapOf("fr" to base.getValue("fr"))
            val directory = ContactDirectory(gwen, base, ContactsConfig(maxBoostLevel = 1))

            assertThatThrownBy { PredictionStack(listOf(Predictor(base, layout), Predictor(french, layout)), directory) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("mêmes langues")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `des contacts sans nom indexable donnent un seul prédicteur`() {
            val stack = PredictionStack.build(base, layout, contacts = listOf(ContactName(givenName = "X"), ContactName()))

            assertThat(stack.directory).isNull()
        }

        @Test
        fun `un seul niveau de contexte`() {
            val stack = PredictionStack.build(base, layout, contacts = gwen, contactsConfig = ContactsConfig(maxBoostLevel = 0))

            assertThat(stack.forContext(ContactContext(atMessageStart = true))).isSameAs(stack.base)
        }

        @Test
        fun `un dictionnaire personnel vide ne change pas les lexiques`() {
            val stack = PredictionStack.build(base, layout, PersonalDictionary())

            assertThat(stack.base.decision("zorgl").inserted).isEqualTo("zorgl")
        }
    }
}
