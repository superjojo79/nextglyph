package nextglyph.engine.session

import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramFixtures
import nextglyph.engine.predict.InMemoryRejectionStore
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.stats.TypingStats
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Erreurs d'espace (issue #52) dans la session : saisie, annulation par ⌫, refus appris, mesures. */
class InputSessionSpaceEditsTest {

    private val french = listOf(
        "de" to 100_000_000L, "je" to 100_000L, "suis" to 50_000L, "bonjour" to 40_000L, "monsieur" to 10_000L,
        "ordinateur" to 5_000L, "ordinal" to 5_000L, "ordinaire" to 5_000L, "bon" to 8_000L, "jour" to 8_000L,
    )

    private val rejections = InMemoryRejectionStore()

    private fun predictor() = Predictor(
        linkedMapOf("fr" to LexiconSet(LexiconFixtures.lexicon(french, "fr"))),
        KeyboardLayouts.AZERTY,
        rejections = rejections,
    )

    private class Setup(val editor: FakeEditor, val session: InputSession)

    private fun setup(withModels: Boolean = false): Setup {
        val editor = FakeEditor("")
        val session = InputSession(editor)
        session.setPredictor(predictor(), "fr")
        if (withModels) session.setNextWordModels(mapOf("fr" to NgramFixtures.model(listOf("je suis" to 3), "fr")))
        val field = SessionField(InputProfileResolver.resolve(C.TYPE_CLASS_TEXT, 0, null), EnterBehavior.EnterKey)
        session.onStartInput(field, 0, 0)
        return Setup(editor, session)
    }

    private val centered = TapOffset(0.0, 0.0)
    private val lowOnKey = TapOffset(0.0, 0.4)

    private fun Setup.type(text: String, offset: (Int, Char) -> TapOffset? = { _, _ -> centered }) {
        text.forEachIndexed { i, c ->
            when {
                c == ' ' -> session.onSpace()
                c.isLetter() -> session.onLetter(c.lowercase(), offset = offset(i, c))
                else -> session.onCharacter(c.toString())
            }
            editor.deliver(session)
        }
    }

    private fun Setup.backspace() {
        session.onBackspace()
        editor.deliver(session)
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `deux mots collés sont séparés à l'espace`() {
            val s = setup()

            s.type("jesuis ")

            assertThat(s.editor.toString()).isEqualTo("je suis ")
        }

        @Test
        fun `⌫ juste après restitue le texte tapé, sans espace, et rouvre le mot`() {
            val s = setup()
            s.type("jesuis ")

            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("jesuis")
            assertThat(s.session.prediction!!.word).isEqualTo("jesuis")
        }

        @Test
        fun `une espace tapée sur n dans le bas de la touche est corrigée`() {
            val s = setup()

            s.type("bonjournmonsieur ") { i, _ -> if (i == 7) lowOnKey else centered }

            assertThat(s.editor.toString()).isEqualTo("bonjour monsieur ")
        }

        @Test
        fun `la même frappe dans le haut de la touche garde la lettre`() {
            val s = setup()

            s.type("bonjournmonsieur ") { i, _ -> if (i == 7) TapOffset(0.0, 0.1) else centered }

            assertThat(s.editor.toString()).isEqualTo("bonjournmonsieur ")
        }

        @Test
        fun `une espace en trop est supprimée à la validation du second fragment`() {
            val s = setup()

            s.type("ordina teur ")

            assertThat(s.editor.toString()).isEqualTo("ordinateur ")
        }

        @Test
        fun `⌫ après la suppression d'une espace en trop restitue les deux fragments`() {
            val s = setup()
            s.type("ordina teur ")

            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("ordina teur")
            assertThat(s.session.prediction!!.word).isEqualTo("teur")
        }

        @Test
        fun `un deuxième ⌫ efface normalement`() {
            val s = setup()
            s.type("ordina teur ")
            s.backspace()

            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("ordina teu")
        }

        @Test
        fun `les modèles de mot suivant sont transmis aux prédicteurs installés ensuite`() {
            val editor = FakeEditor("")
            val session = InputSession(editor)
            session.setNextWordModels(mapOf("fr" to NgramFixtures.model(listOf("je suis" to 3), "fr")))
            session.setPredictor(predictor(), "fr")
            session.onStartInput(SessionField(InputProfileResolver.resolve(C.TYPE_CLASS_TEXT, 0, null), EnterBehavior.EnterKey), 0, 0)

            "jesuis ".forEach { if (it == ' ') session.onSpace() else session.onLetter(it.toString(), offset = centered) }

            assertThat(editor.toString()).isEqualTo("je suis ")
        }

        @Test
        fun `une coupure est comptée comme une correction, et son annulation n'est pas une faute de frappe`() {
            val s = setup()
            val stats = TypingStats({ 0L })
            s.session.stats = stats

            s.type("jesuis ")
            s.backspace()

            val c = stats.counters
            assertThat(c.normalCorrections).isEqualTo(1)
            assertThat(c.normalUndone).isEqualTo(1)
            assertThat(c.undone).isEqualTo(1)
            assertThat(c.backspaces).isZero()
        }

        @Test
        fun `une coupure annulée deux fois est adoptée dans le dictionnaire personnel`() {
            val s = setup()
            val data = LearnedData()
            s.session.learning = SessionLearning(data)

            s.type("jesuis ")
            s.backspace()
            s.type(" ")
            s.backspace()

            assertThat(rejections.rejections("jesuis", "je suis")).isEqualTo(2)
            assertThat(data.personal.contains("jesuis", "fr")).isTrue()
            s.type(" ")
            assertThat(s.editor.toString()).isEqualTo("jesuis ")
        }

        @Test
        fun `le premier fragment d'une espace en trop supprimée sort de la liste d'attente`() {
            val s = setup()
            val data = LearnedData()
            s.session.learning = SessionLearning(data)

            s.type("ordina teur ")

            assertThat(data.personal.pendingWords("fr")).isEmpty()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une espace en trop annulée deux fois n'adopte rien et n'est plus supprimée`() {
            val s = setup()
            val data = LearnedData()
            s.session.learning = SessionLearning(data)

            s.type("ordina teur ")
            s.backspace()
            s.type(" ")
            s.backspace()

            assertThat(rejections.rejections("ordina teur", "ordinateur")).isEqualTo(2)
            assertThat(data.personal.words()).isEmpty()
            s.type(" ")
            assertThat(s.editor.toString()).isEqualTo("ordina teur ")
        }

        @Test
        fun `sans position de frappe, une lettre voisine de la barre d'espace reste une lettre`() {
            val s = setup()

            s.type("bonjournmonsieur ") { _, _ -> null }

            assertThat(s.editor.toString()).isEqualTo("bonjournmonsieur ")
        }

        @Test
        fun `un mot du lexique est validé tel quel`() {
            val s = setup()

            s.type("bon jour ")

            assertThat(s.editor.toString()).isEqualTo("bon jour ")
        }

        @Test
        fun `sans prédicteur, rien n'est corrigé`() {
            val editor = FakeEditor("")
            val session = InputSession(editor)
            session.onStartInput(SessionField(InputProfileResolver.resolve(C.TYPE_CLASS_TEXT, 0, null), EnterBehavior.EnterKey), 0, 0)

            "jesuis ".forEach { if (it == ' ') session.onSpace() else session.onLetter(it.toString()) }

            assertThat(editor.toString()).isEqualTo("jesuis ")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `retirer les modèles de mot suivant garde la correction par unigrammes`() {
            val s = setup(withModels = true)

            s.session.setNextWordModels(emptyMap())
            s.type("jesuis ")

            assertThat(s.editor.toString()).isEqualTo("je suis ")
        }

        @Test
        fun `une espace en trop après une ponctuation n'est pas supprimée`() {
            val s = setup()

            s.type("ordina, teur ")

            assertThat(s.editor.toString()).isEqualTo("ordina, teur ")
        }

        @Test
        fun `le curseur déplacé fait oublier l'annulation d'une coupure`() {
            val s = setup()
            s.type("jesuis ")

            s.editor.moveCursor(s.session, 2)
            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("j suis ")
        }
    }
}
