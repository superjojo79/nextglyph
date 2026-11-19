package nextglyph.engine.session

import nextglyph.engine.edit.ShiftState
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Lots d'édition, mises à jour de sélection, barre et état : cas de bord de la session. */
class InputSessionEventsTest {

    private val french = listOf(
        "bonjour" to 2000L, "bon" to 1000L, "ça" to 3000L, "va" to 2500L, "le" to 10000L, "l'" to 7000L,
        "école" to 500L, "est" to 9000L, "chat" to 800L, "jusqu'" to 400L, "jusque" to 50L,
    )
    private val english = listOf("hello" to 1000L, "how" to 3000L, "are" to 4000L, "you" to 6000L, "the" to 9000L)

    private fun predictor() = Predictor(
        linkedMapOf(
            "fr" to LexiconSet(LexiconFixtures.lexicon(french, "fr")),
            "en" to LexiconSet(LexiconFixtures.lexicon(english, "en")),
        ),
        KeyboardLayouts.AZERTY,
    )

    private fun field(inputType: Int = C.TYPE_CLASS_TEXT, enter: EnterBehavior = EnterBehavior.NewLine) =
        SessionField(InputProfileResolver.resolve(inputType, 0, null), enter)

    private fun start(initial: String = "", field: SessionField = field(), preferred: String = "fr"): Pair<FakeEditor, InputSession> {
        val editor = FakeEditor(initial)
        val session = InputSession(editor)
        session.setPredictor(predictor(), preferred)
        session.onStartInput(field, initial.length, initial.length)
        return editor to session
    }

    private fun InputSession.typeWord(word: String) = word.forEach { onLetter(it.toString()) }

    /** Une action de la session, désignée par son nom dans les tests paramétrés. */
    private fun InputSession.act(action: String) {
        when (action) {
            "retour arrière" -> onBackspace()
            "entrée" -> onEnter()
            "virgule" -> onCharacter(",")
            "parenthèse" -> onCharacter("(")
            "chiffre" -> onCharacter("7")
            "apostrophe dans un mot" -> {
                typeWord("l")
                onCharacter("'")
            }
            "suggestion" -> {
                typeWord("cha")
                onBarItem(state.bar.items.indexOfFirst { it != null })
            }
            "raccourci" -> onShortcut("x")
            "lettre" -> onLetter("a")
            else -> error(action)
        }
    }

    @Nested
    inner class HappyPath {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["retour arrière", "entrée", "virgule", "parenthèse", "chiffre", "apostrophe dans un mot", "suggestion", "raccourci", "lettre"])
        fun `mises à jour livrées en retard après chaque action - reconnues, l'annulation reste possible`(action: String) {
            val (editor, session) = start("x ")

            session.act(action)
            session.onSpace()
            session.typeWord("bonj")
            session.onSpace()
            editor.deliver(session)
            val before = editor.toString()
            session.onBackspace()

            assertThat(before).endsWith("bonjour ")
            assertThat(editor.toString()).isEqualTo(before.removeSuffix("bonjour ") + "bonj")
        }

        @Test
        fun `chaque modification se fait dans un lot d'édition`() {
            val (editor, session) = start()

            session.typeWord("bonj")
            session.onSpace()
            session.onCharacter(",")
            session.onBackspace()
            session.onCharacter("(")
            session.onCharacter("7")
            session.onEnter()
            session.onShortcut("a")

            assertThat(editor.outsideBatch).isZero()
            assertThat(editor.batches).isEqualTo(11)
        }

        @Test
        fun `langue préférée anglaise - indicateur EN, typographie anglaise`() {
            val (editor, session) = start(preferred = "en")

            session.typeWord("hello")
            session.onCharacter("?")

            assertThat(session.language!!.dominant).isEqualTo("en")
            assertThat(editor.toString()).isEqualTo("hello? ")
        }

        @Test
        fun `l'installation du prédicteur publie un nouvel état`() {
            val editor = FakeEditor()
            val session = InputSession(editor)
            session.onStartInput(field(), 0, 0)
            val states = mutableListOf<KeyboardState>()
            session.onStateChanged = { states += it }

            session.setPredictor(predictor())

            assertThat(states).hasSize(1)
            assertThat(states[0].languageLabel).isEqualTo("FR")
        }

        @Test
        fun `fin de phrase par point, entrée ou double espace - la phrase suivante repart du score courant`() {
            val (_, session) = start()
            session.lockLanguage("en")
            val versions = mutableListOf<Int>()
            session.onStateChanged = { versions += it.languageVersion }

            session.onCharacter(".")
            session.onEnter()
            session.typeWord("a")
            session.onSpace()
            session.onSpace()

            assertThat(versions.zipWithNext().count { (a, b) -> b > a }).isGreaterThanOrEqualTo(3)
        }

        @Test
        fun `déplacement externe - état publié, composition validée`() {
            val (editor, session) = start("le chat")
            session.typeWord("s")
            val states = mutableListOf<KeyboardState>()
            session.onStateChanged = { states += it }

            session.onSelectionUpdate(2, 2, -1, -1)

            assertThat(editor.calls.last()).isEqualTo("finish")
            assertThat(states).hasSize(1)
        }

        @Test
        fun `barre de ponctuation seulement après l'espace automatique et sans mot en cours`() {
            val (_, session) = start()

            session.typeWord("bonjour")
            session.onSpace()
            val after = session.state.bar.mode
            session.typeWord("b")
            val typing = session.state.bar.mode

            assertThat(after).isEqualTo(BarMode.PUNCTUATION)
            assertThat(typing).isEqualTo(BarMode.WORDS)
            assertThat(session.state.bar.items).hasSize(3)
        }

        @Test
        fun `retour arrière sur une espace - le mot précédent est remis en composition`() {
            val (editor, session) = start("le chat ")

            session.onBackspace()
            session.onLetter("s")

            assertThat(editor.toString()).isEqualTo("le chats")
            assertThat(editor.calls).contains("region:3:7")
            assertThat(editor.composeStart to editor.composeEnd).isEqualTo(3 to 8)
        }

        @Test
        fun `les cases de la barre portent le surlignage et le texte tapé`() {
            val (_, session) = start()

            session.typeWord("bonj")

            val items = session.state.bar.items
            assertThat(items[1]!!.highlighted).isTrue()
            assertThat(items[1]!!.typed).isFalse()
            assertThat(items[0]!!.typed).isTrue()
            assertThat(items[0]!!.highlighted).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `mise à jour inattendue - annulation oubliée, espace automatique oubliée`() {
            val (editor, session) = start()
            session.typeWord("bonj")
            session.onSpace()
            editor.deliver(session)

            session.onSelectionUpdate(3, 3, -1, -1)
            editor.selStart = 8
            editor.selEnd = 8
            session.onSelectionUpdate(8, 8, -1, -1)
            session.onCharacter(",")

            assertThat(editor.toString()).isEqualTo("bonjour , ")
        }

        @Test
        fun `sélection signalée - pas de suggestion ni de composition, la frappe la remplace`() {
            val (editor, session) = start("le chat")
            editor.selStart = 3
            editor.selEnd = 7
            session.onSelectionUpdate(3, 7, -1, -1)

            val bar = session.state.bar
            session.onLetter("x")

            assertThat(bar).isEqualTo(Bar.EMPTY)
            assertThat(session.state.prefix).isEqualTo("x")
            assertThat(editor.toString()).isEqualTo("le x")
            assertThat(editor.calls).containsExactly("commit:x")
        }

        @Test
        fun `sélection initiale inversée ou inconnue`() {
            val editor = FakeEditor("le chat")
            val session = InputSession(editor)
            session.setPredictor(predictor())
            editor.selStart = 3
            editor.selEnd = 7

            session.onStartInput(field(), 7, 3)
            session.onLetter("x")

            assertThat(editor.toString()).isEqualTo("le x")
            session.onStartInput(field(), -1, 3)
            session.onLetter("y")
            assertThat(editor.toString()).isEqualTo("le xy")
        }

        @Test
        fun `composition validée par l'application pendant un mot - oubliée seulement si à jour`() {
            val (editor, session) = start()
            session.typeWord("bon")
            editor.updates.clear()

            session.onSelectionUpdate(3, 3, 0, 2)
            session.onLetter("j")

            assertThat(editor.calls).contains("region:0:3")
            assertThat(editor.toString()).isEqualTo("bonj")
        }

        @Test
        fun `menu de langue sans prédicteur - sans effet`() {
            val session = InputSession(FakeEditor())

            session.lockLanguage("en")
            session.toggleShift()
            session.toggleShift()

            assertThat(session.language).isNull()
            assertThat(session.state.shift).isEqualTo(ShiftState.OFF)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `élision validée par l'espace - pas d'espace après l'apostrophe`() {
            val (editor, session) = start()

            session.typeWord("jusqu")
            session.onSpace()

            assertThat(editor.toString()).isEqualTo("jusqu'")
            assertThat(session.state.bar.mode).isNotEqualTo(BarMode.PUNCTUATION)
        }

        @Test
        fun `retour arrière sur la dernière lettre en composition - composition close`() {
            val (editor, session) = start()
            session.onLetter("a")

            session.onBackspace()
            session.onBackspace()

            assertThat(editor.toString()).isEmpty()
            assertThat(editor.calls).containsExactly("compose:a", "compose:", "finish", "key:backspace")
        }

        @Test
        fun `mot en cours suivi de texte - pas de remise en composition`() {
            val (editor, session) = start("lechat")
            editor.moveCursor(session, 2)

            session.onBackspace()

            assertThat(editor.toString()).isEqualTo("lchat")
            assertThat(editor.calls).noneMatch { it.startsWith("region") }
        }

        @Test
        fun `plus de trente-deux mises à jour en attente - les plus anciennes sont oubliées`() {
            val (editor, session) = start()
            repeat(40) { session.onLetter("a") }

            editor.deliver(session)
            session.onLetter("b")

            assertThat(editor.toString()).isEqualTo("a".repeat(40) + "b")
        }

        @Test
        fun `espace dans un champ sans suggestions - double espace et fin de phrase`() {
            val (editor, session) = start(field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_NO_SUGGESTIONS or C.TYPE_TEXT_FLAG_CAP_SENTENCES))

            session.onLetter("a")
            session.onSpace()
            session.onSpace()

            assertThat(editor.toString()).isEqualTo("A. ")
            assertThat(session.state.shift).isEqualTo(ShiftState.ONCE)
            assertThat(session.state.languageLabel).isNull()
        }

        @Test
        fun `distribution sans état de langue`() {
            val session = InputSession(FakeEditor())

            assertThat(session.distribution("a").isUnknownPrefix).isTrue()
        }
    }
}
