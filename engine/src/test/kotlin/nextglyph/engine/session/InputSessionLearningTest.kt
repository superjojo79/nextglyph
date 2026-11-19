package nextglyph.engine.session

import nextglyph.engine.contacts.ContactName
import nextglyph.engine.edit.EditConfig
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Apprentissage (§5.3, §10.5), contacts (§6.3) et réglages de l'édition branchés dans la session. */
class InputSessionLearningTest {

    private val french = listOf(
        "bonjour" to 2000L, "bon" to 1000L, "le" to 10000L, "la" to 9000L, "rose" to 300L, "est" to 9000L,
        "avec" to 3000L, "merci" to 1500L, "ça" to 3000L, "va" to 2500L,
    )
    private val english = listOf("hello" to 1000L, "the" to 9000L, "you" to 6000L)

    private val base: Map<String, LexiconSet> = linkedMapOf(
        "fr" to LexiconSet(LexiconFixtures.lexicon(french, "fr")),
        "en" to LexiconSet(LexiconFixtures.lexicon(english, "en")),
    )

    private val data = LearnedData()

    private fun field(inputType: Int = C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES, imeOptions: Int = 0) =
        SessionField(InputProfileResolver.resolve(inputType, imeOptions, null), EnterBehavior.EnterKey)

    private class Setup(val editor: FakeEditor, val session: InputSession)

    private fun stack(contacts: List<ContactName> = emptyList()) =
        PredictionStack.build(base, KeyboardLayouts.AZERTY, data.personal, contacts, rejections = data.rejections)

    private fun setup(field: SessionField = field(), contacts: List<ContactName> = emptyList(), initial: String = ""): Setup {
        val editor = FakeEditor(initial)
        val session = InputSession(editor)
        session.learning = SessionLearning(data)
        session.setPredictors(stack(contacts), "fr")
        session.onStartInput(field, initial.length, initial.length)
        return Setup(editor, session)
    }

    /** Recharge les prédicteurs après un changement du dictionnaire personnel, comme l'IME. */
    private fun Setup.reload(contacts: List<ContactName> = emptyList()) = session.setPredictors(stack(contacts), "fr")

    private val centered = TapOffset(0.0, 0.0)

    private fun Setup.type(text: String, offset: TapOffset? = centered) {
        for (c in text) {
            when {
                c == ' ' -> session.onSpace()
                c.isLetter() -> session.onLetter(c.lowercase(), upper = c.isUpperCase(), offset = offset)
                else -> session.onCharacter(c.toString())
            }
            editor.deliver(session)
        }
    }

    private fun Setup.backspace() {
        session.onBackspace()
        editor.deliver(session)
    }

    private fun Setup.barTexts() = session.state.bar.items.map { it?.text }

    @Nested
    inner class HappyPath {

        @Test
        fun `un mot inconnu tapé deux fois est appris puis complété`() {
            val s = setup()

            s.type("zorglub le zorglub ")
            assertThat(data.personal.contains("zorglub", "fr")).isTrue()
            s.reload()
            s.type("zorgl")

            assertThat(s.session.state.spaceLabel).isEqualTo("␣ zorglub")
            s.type(" ")
            assertThat(s.editor.toString()).isEqualTo("Zorglub le zorglub zorglub ")
        }

        @Test
        fun `la majuscule de début de phrase n'est pas apprise`() {
            val s = setup()

            s.type("zorglub. zorglub ")

            assertThat(data.personal.words().map { it.form }).containsExactly("zorglub")
        }

        @Test
        fun `les frappes d'un mot validé sont intégrées au mot suivant`() {
            val s = setup()

            s.type("le ", TapOffset(0.2, 0.1))
            assertThat(data.spatial.sampleCount('l')).isZero()
            s.type("l")

            assertThat(data.spatial.sampleCount('l')).isEqualTo(1)
            assertThat(data.spatial.sampleCount('e')).isEqualTo(1)
            assertThat(data.spatial.offsetX('l')).isPositive()
        }

        @Test
        fun `quitter le champ intègre les frappes du mot validé`() {
            val s = setup()
            s.type("la ")

            s.session.onFinishInput()

            assertThat(data.spatial.sampleCount('a')).isEqualTo(1)
        }

        @Test
        fun `un mot choisi dans la barre alors qu'un candidat dominait est compté comme inconnu`() {
            val s = setup()

            repeat(2) {
                s.type("bonj")
                s.session.onBarItem(s.session.state.bar.items.indexOfFirst { it?.typed == true })
                s.editor.deliver(s.session)
            }

            assertThat(data.personal.contains("bonj", "fr")).isTrue()
        }

        @Test
        fun `la validation par ponctuation est observée`() {
            val s = setup(field(C.TYPE_CLASS_TEXT))

            s.type("zorglub, zorglub.")

            assertThat(data.personal.contains("zorglub", "fr")).isTrue()
        }

        @Test
        fun `un prénom de contact est proposé après bonjour`() {
            val s = setup(contacts = listOf(ContactName(givenName = "Gwenaëlle", familyName = "Rose")))

            s.type("bonjour gwen")

            assertThat(s.barTexts()).contains("Gwenaëlle")
            assertThat(s.session.state.spaceLabel).isEqualTo("␣ Gwenaëlle")
        }

        @Test
        fun `le remplacement par l'espace est signalé, pas un mot gardé tel quel`() {
            val s = setup()
            val replaced = mutableListOf<String>()
            s.session.onWordReplaced = { replaced += it }

            s.type("bonj le ")

            assertThat(replaced).containsExactly("Bonjour")
        }

        @Test
        fun `la typographie française se coupe à chaud`() {
            val s = setup(field(C.TYPE_CLASS_TEXT))
            s.session.setEditConfig(EditConfig(frenchTypography = false))

            s.type("merci!")

            assertThat(s.editor.toString()).isEqualTo("merci! ")
        }

        @Test
        fun `en e-mail, les frappes sont apprises à la fin du mot, sans dictionnaire`() {
            val s = setup(field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))

            s.type("zorglub zorglub ")

            assertThat(data.spatial.sampleCount('z')).isEqualTo(2)
            assertThat(data.personal.pendingSize + data.personal.size).isZero()
        }

        @Test
        fun `entrée termine le mot et intègre ses frappes`() {
            val s = setup()
            s.type("la")

            s.session.onEnter()

            assertThat(data.spatial.sampleCount('l')).isEqualTo(1)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `incognito - rien n'est appris`() {
            val s = setup(field(imeOptions = C.IME_FLAG_NO_PERSONALIZED_LEARNING))

            s.type("zorglub zorglub le ")
            s.session.onFinishInput()

            assertThat(data.isEmpty).isTrue()
        }

        @Test
        fun `mot de passe - rien n'est appris`() {
            val s = setup(field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD))

            s.type("zorglub zorglub ")
            s.session.onFinishInput()

            assertThat(data.isEmpty).isTrue()
        }

        @Test
        fun `une validation annulée par retour arrière n'apprend pas ses frappes`() {
            val s = setup()
            s.type("bonj ")

            s.backspace()
            s.session.onFinishInput()

            assertThat(data.spatial.isEmpty).isTrue()
            assertThat(data.rejections.rejections("Bonj", "Bonjour")).isEqualTo(1)
        }

        @Test
        fun `retour arrière juste après un mot inconnu oublie le mot en attente`() {
            val s = setup()
            s.type("zorglub ")

            s.backspace()
            s.type("s ")

            assertThat(s.editor.toString()).isEqualTo("Zorglubs ")
            assertThat(data.personal.pendingWords().map { it.form }).containsExactly("zorglubs")
        }

        @Test
        fun `une lettre effacée n'est pas apprise`() {
            val s = setup()

            s.type("lx")
            s.backspace()
            s.type("a ")
            s.session.onFinishInput()

            assertThat(data.spatial.sampleCount('x')).isZero()
            assertThat(data.spatial.sampleCount('a')).isEqualTo(1)
        }

        @Test
        fun `curseur déplacé - le mot en cours est abandonné`() {
            val s = setup(initial = "le ")
            s.type("la")

            s.editor.moveCursor(s.session, 0)
            s.session.onFinishInput()

            assertThat(data.spatial.isEmpty).isTrue()
        }

        @Test
        fun `un nom de contact qui est un mot courant ne remplace pas le mot`() {
            val s = setup(contacts = listOf(ContactName(givenName = "Gwenaëlle", familyName = "Rose")))

            s.type("le rose ")

            assertThat(s.editor.toString()).isEqualTo("Le rose ")
        }

        @Test
        fun `sans apprentissage branché, la session fonctionne`() {
            val s = setup()
            s.session.learning = null

            s.type("zorglub zorglub ")
            s.backspace()

            assertThat(data.isEmpty).isTrue()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une frappe sans position (menu, glissé) n'est pas apprise mais garde l'alignement`() {
            val s = setup()

            s.type("l")
            s.type("a", offset = null)
            s.backspace()
            s.type("e ")
            s.session.onFinishInput()

            assertThat(data.spatial.sampleCount('l')).isEqualTo(1)
            assertThat(data.spatial.sampleCount('e')).isEqualTo(1)
            assertThat(data.spatial.sampleCount('a')).isZero()
        }

        @Test
        fun `au niveau 0, un prénom n'est pas inséré par complétion`() {
            val s = setup(contacts = listOf(ContactName(givenName = "Gwenaëlle")))

            s.type("le gwen")

            assertThat(s.barTexts()).contains("Gwenaëlle")
            assertThat(s.session.state.spaceLabel).isNull()
        }

        @Test
        fun `une majuscule tapée est un indice de contexte`() {
            val s = setup(contacts = listOf(ContactName(givenName = "Gwenaëlle")))

            s.type("le Gwen")

            assertThat(s.session.state.spaceLabel).isEqualTo("␣ Gwenaëlle")
        }

        @Test
        fun `un champ de nom de personne est un indice de contexte`() {
            val s = setup(field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME), listOf(ContactName(givenName = "Gwenaëlle")))

            s.type("le gwen")

            assertThat(s.session.state.spaceLabel).isEqualTo("␣ Gwenaëlle")
        }

        @Test
        fun `sans prédicteur installé, aucune prédiction`() {
            val editor = FakeEditor()
            val session = InputSession(editor)
            session.onStartInput(field(), 0, 0)

            session.onLetter("a")

            assertThat(session.prediction).isNull()
            assertThat(session.predictors).isNull()
        }
    }
}
