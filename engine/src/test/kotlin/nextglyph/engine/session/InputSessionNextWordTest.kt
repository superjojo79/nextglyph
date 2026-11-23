package nextglyph.engine.session

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramFixtures
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InputSessionNextWordTest {

    private val french = listOf(
        "bonjour" to 2000L, "comment" to 1000L, "ça" to 3000L, "va" to 2500L, "le" to 10000L, "chat" to 800L,
        "dort" to 300L, "la" to 9000L, "vie" to 500L, "est" to 9000L, "belle" to 200L, "j'" to 5000L, "ai" to 6000L,
        "faim" to 400L,
    )
    private val english = listOf("hello" to 1000L, "how" to 3000L, "are" to 4000L, "you" to 6000L, "I" to 5000L)

    private val sentences = listOf(
        "bonjour comment ça va" to 5,
        "le chat dort" to 3,
        "la vie est belle" to 2,
        "oui j' ai faim" to 4,
    )

    private val englishSentences = listOf("hello how are you" to 4, "I am here" to 3)

    private fun predictor() = Predictor(
        linkedMapOf(
            "fr" to LexiconSet(LexiconFixtures.lexicon(french, "fr")),
            "en" to LexiconSet(LexiconFixtures.lexicon(english, "en")),
        ),
        KeyboardLayouts.AZERTY,
    )

    private fun models(): Map<String, NgramModel> = mapOf(
        "fr" to NgramFixtures.model(sentences, "fr"),
        "en" to NgramFixtures.model(englishSentences, "en"),
    )

    private fun field(
        inputType: Int = C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES,
        nextWord: Boolean = true,
    ) = SessionField(InputProfileResolver.resolve(inputType, 0, null), nextWord = nextWord)

    private class Setup(val editor: FakeEditor, val session: InputSession)

    private fun setup(
        initial: String = "",
        field: SessionField = field(),
        withModels: Boolean = true,
    ): Setup {
        val editor = FakeEditor(initial)
        val session = InputSession(editor)
        session.setPredictor(predictor(), "fr")
        if (withModels) session.setNextWordModels(models())
        session.onStartInput(field, initial.length, initial.length)
        return Setup(editor, session)
    }

    private fun Setup.type(text: String) {
        for (c in text) {
            when {
                c == ' ' -> session.onSpace()
                c.isLetter() -> session.onLetter(c.lowercase(), upper = c.isUpperCase())
                else -> session.onCharacter(c.toString())
            }
            editor.deliver(session)
        }
    }

    private fun Setup.items() = session.state.bar.items.map { it?.text }

    private fun Setup.tap(text: String) {
        session.onBarItem(session.state.bar.items.indexOfFirst { it?.text == text })
        editor.deliver(session)
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `en début de champ, la barre propose les débuts de phrase, avec une majuscule`() {
            val s = setup()

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.WORDS)
            assertThat(s.items()).containsExactly("Oui", "Bonjour", "Bonjour comment")
        }

        @Test
        fun `après un mot validé, la barre propose le mot suivant à la place de la ponctuation`() {
            val s = setup()

            s.type("bonj ")

            assertThat(s.editor.toString()).isEqualTo("Bonjour ")
            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.WORDS)
            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("comment")
        }

        @Test
        fun `un tap sur le mot suivant l'insère avec une espace et en propose un autre`() {
            val s = setup()
            s.type("bonj ")

            s.tap("comment")

            assertThat(s.editor.toString()).isEqualTo("Bonjour comment ")
            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("ça")
            s.tap("ça")
            assertThat(s.editor.toString()).isEqualTo("Bonjour comment ça ")
            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("va")
        }

        @Test
        fun `un tap sur un groupe insère les deux mots`() {
            val s = setup()

            s.tap("Bonjour comment")

            assertThat(s.editor.toString()).isEqualTo("Bonjour comment ")
        }

        @Test
        fun `le premier mot de la barre au début de champ est capitalisé, pas le suivant`() {
            val s = setup()

            s.tap("Oui")

            assertThat(s.editor.toString()).isEqualTo("Oui ")
            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("j'")
        }

        @Test
        fun `la ponctuation tapée après un mot suivant retire l'espace automatique`() {
            val s = setup()
            s.tap("Oui")

            s.type(",")

            assertThat(s.editor.toString()).isEqualTo("Oui, ")
        }

        @Test
        fun `un mot suivant inséré ne s'annule pas par l'effacement, qui retire seulement l'espace`() {
            val s = setup()
            s.tap("Oui")

            s.session.onBackspace()
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo("Oui")
        }

        @Test
        fun `glisser vers le haut sur l'espace insère le mot central de la barre`() {
            val s = setup()
            s.type("bonj ")

            s.session.onSpaceSwipeUp()
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo("Bonjour comment ")
        }

        @Test
        fun `une élision est insérée sans espace et le mot qui la suit est proposé`() {
            val s = setup("Oui ")

            assertThat(s.items()).containsExactly(null, "j'", null)
            s.tap("j'")

            assertThat(s.editor.toString()).isEqualTo("Oui j'")
            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("ai")
            s.tap("ai")
            assertThat(s.editor.toString()).isEqualTo("Oui j'ai ")
            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("faim")
        }

        @Test
        fun `le texte existant du champ donne le contexte`() {
            val s = setup("La vie ")

            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("est")
        }

        @Test
        fun `après une phrase finie, la barre propose un début de phrase capitalisé`() {
            val s = setup("Le chat dort. ")

            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("Bonjour")
        }

        @Test
        fun `la langue anglaise active fait proposer des mots anglais`() {
            val s = setup("")
            s.session.lockLanguage("en")

            assertThat(s.items()).contains("Hello", "I")
        }

        @Test
        fun `le choix d'un mot anglais met à jour le score de langue`() {
            val s = setup("")
            val before = s.session.language!!.weight("en")
            s.session.lockLanguage("en")

            repeat(3) {
                s.tap("I")
                s.session.onCharacter(".")
                s.editor.deliver(s.session)
                s.type(" ")
            }
            s.session.lockLanguage(null)

            assertThat(s.session.language!!.weight("en")).isGreaterThan(before)
        }

        @Test
        fun `retirer les modèles rend la barre de ponctuation`() {
            val s = setup()
            s.type("bonj ")

            s.session.setNextWordModels(emptyMap())

            assertThat(s.session.nextWords).isNull()
            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.PUNCTUATION)
        }

        @Test
        fun `la barre des suggestions du mot en cours prime sur les mots suivants`() {
            val s = setup()

            s.type("bo")
            s.tap("Bonjour")

            assertThat(s.editor.toString()).isEqualTo("Bonjour ")
            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("comment")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `sans modèle, la barre garde la ponctuation après un mot validé et reste vide au départ`() {
            val s = setup(withModels = false)
            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.EMPTY)

            s.type("bonj ")

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.PUNCTUATION)
        }

        @Test
        fun `réglage désactivé, la barre garde la ponctuation`() {
            val s = setup(field = field(nextWord = false))
            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.EMPTY)

            s.type("bonj ")

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.PUNCTUATION)
        }

        @Test
        fun `aucun mot suivant dans un mot de passe, une adresse ou un champ sans suggestions`() {
            for (type in listOf(
                C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD,
                C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_URI,
                C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                C.TYPE_CLASS_NUMBER,
                C.TYPE_CLASS_PHONE,
            )) {
                val s = setup("Le ", field = field(type))

                assertThat(s.session.state.bar.mode).`as`("$type").isNotEqualTo(BarMode.WORDS)
                assertThat(s.session.state.bar.items.filterNotNull().map { it.text }).`as`("$type").doesNotContain("chat")
            }
        }

        @Test
        fun `aucun mot suivant quand une lettre suit le curseur`() {
            val s = setup("Le chat")
            s.editor.moveCursor(s.session, 3)

            assertThat(s.session.state.bar.mode).isNotEqualTo(BarMode.WORDS)
        }

        @Test
        fun `aucun mot suivant quand du texte est sélectionné`() {
            val editor = FakeEditor("Le ")
            val session = InputSession(editor)
            session.setPredictor(predictor(), "fr")
            session.setNextWordModels(models())

            session.onStartInput(field(), 0, 3)

            assertThat(session.state.bar.mode).isNotEqualTo(BarMode.WORDS)
        }

        @Test
        fun `aucun mot suivant quand l'éditeur ne donne pas le texte`() {
            val editor = FakeEditor("Le ", providesText = false)
            val session = InputSession(editor)
            session.setPredictor(predictor(), "fr")
            session.setNextWordModels(models())

            session.onStartInput(field(), 3, 3)

            assertThat(session.state.bar.mode).isNotEqualTo(BarMode.WORDS)
        }

        @Test
        fun `un mot inconnu avant le curseur ne donne ni mot suivant ni erreur`() {
            val s = setup("Zorglub ")

            assertThat(s.session.state.bar.mode).isNotEqualTo(BarMode.WORDS)
        }

        @Test
        fun `un champ à raccourcis garde sa barre`() {
            val s = setup("Le ", field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.SHORTCUTS)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un texte sans espace final n'a pas de mot suivant mais les suggestions du mot`() {
            val s = setup("Le cha")

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.WORDS)
            assertThat(s.items()).doesNotContain("dort")
        }

        @Test
        fun `après une espace tapée à la main, les mots suivants sont proposés`() {
            val s = setup("Le chat ")

            assertThat(s.items()[InputSession.CENTER_ITEM]).isEqualTo("dort")
        }

        @Test
        fun `un mot suivant inséré au milieu du texte garde une espace tapée`() {
            val s = setup("Le  vie")
            s.editor.moveCursor(s.session, 3)

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.WORDS)
            s.tap("chat")
            assertThat(s.editor.toString()).isEqualTo("Le chat  vie")
        }

        @Test
        fun `au bout de la chaîne, un mot sans suite connue laisse la ponctuation`() {
            val s = setup("Le chat dort ")

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.EMPTY)
        }

        @Test
        fun `un tap sur une case vide de la barre ne fait rien`() {
            val s = setup("La vie est belle ")
            val before = s.editor.toString()

            s.session.onBarItem(1)
            s.session.onBarItem(5)

            assertThat(s.editor.toString()).isEqualTo(before)
        }

        @Test
        fun `glisser vers le haut sans mot suivant vaut un appui sur l'espace`() {
            val s = setup("Le chat dort ")

            s.session.onSpaceSwipeUp()
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo("Le chat dort  ")
        }
    }
}
