package nextglyph.engine.session

import nextglyph.engine.edit.ShiftState
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.Distribution
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.InMemoryRejectionStore
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class InputSessionTest {

    private val nnbsp = ' '

    private val french = listOf(
        "bonjour" to 2000L, "bon" to 1000L, "bonne" to 500L, "ça" to 3000L, "va" to 2500L, "vas" to 300L,
        "le" to 10000L, "la" to 9000L, "l'" to 7000L, "j'" to 5000L, "ai" to 6000L, "école" to 500L,
        "est" to 9000L, "a" to 5000L, "à" to 4000L, "comment" to 1000L, "chat" to 800L,
    )
    private val english = listOf(
        "hello" to 1000L, "how" to 3000L, "are" to 4000L, "you" to 6000L, "the" to 9000L, "I" to 5000L,
        "I'm" to 2000L, "is" to 4000L, "it" to 4000L, "it's" to 1500L,
    )

    private val rejections = InMemoryRejectionStore()

    private fun predictor() = Predictor(
        linkedMapOf(
            "fr" to LexiconSet(LexiconFixtures.lexicon(french, "fr")),
            "en" to LexiconSet(LexiconFixtures.lexicon(english, "en")),
        ),
        KeyboardLayouts.AZERTY,
        rejections = rejections,
    )

    private fun field(
        inputType: Int = C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES,
        imeOptions: Int = 0,
        enter: EnterBehavior = EnterBehavior.EnterKey,
    ) = SessionField(InputProfileResolver.resolve(inputType, imeOptions, null), enter)

    private class Setup(val editor: FakeEditor, val session: InputSession)

    private fun setup(
        initial: String = "",
        field: SessionField = field(),
        withPredictor: Boolean = true,
        preferred: String = "fr",
    ): Setup {
        val editor = FakeEditor(initial)
        val session = InputSession(editor)
        if (withPredictor) session.setPredictor(predictor(), preferred)
        session.onStartInput(field, initial.length, initial.length)
        return Setup(editor, session)
    }

    /** Tape [text] : lettres, espace (`␣` ou ` `), autres caractères, en livrant les mises à jour de sélection. */
    private fun Setup.type(text: String) {
        for (c in text) {
            when {
                c == ' ' || c == '␣' -> session.onSpace()
                c.isLetter() -> session.onLetter(c.lowercase(), upper = c.isUpperCase())
                else -> session.onCharacter(c.toString())
            }
            editor.deliver(session)
        }
    }

    private fun Setup.bar(text: String) {
        session.onBarItem(session.state.bar.items.indexOfFirst { it?.text == text })
        editor.deliver(session)
    }

    private fun Setup.backspace() {
        session.onBackspace()
        editor.deliver(session)
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `émoji - le mot en cours est validé sans espace, puis l'émoji inséré`() {
            val s = setup()

            s.type("bonj")
            s.session.onEmoji("👋🏽")
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo("Bonjour👋🏽")
        }

        @Test
        fun `glisser vers le haut sur l'espace - insère la suggestion centrale même si elle ne domine pas`() {
            val s = setup()

            s.type("bo")
            val center = s.session.state.bar.items[InputSession.CENTER_ITEM]
            assertThat(center?.text).isEqualTo("Bonjour")
            assertThat(s.session.state.spaceLabel).isNull()
            s.session.onSpaceSwipeUp()
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo("Bonjour ")
            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.PUNCTUATION)
        }

        @Test
        fun `bonj, virgule, ça va, point d'interrogation par la barre - phrase complète et typographie française`() {
            val s = setup()

            s.type("bonj␣")
            s.bar(",")
            s.type("ça␣va␣")
            s.bar("?")
            s.type(" ")

            assertThat(s.editor.toString()).isEqualTo("Bonjour, ça va$nnbsp? ")
            assertThat(s.session.state.shift).isEqualTo(ShiftState.ONCE)
        }

        @Test
        fun `le mot en cours est en composition, validé par commitText dans un seul lot`() {
            val s = setup()

            s.type("bon")

            assertThat(s.editor.composeStart to s.editor.composeEnd).isEqualTo(0 to 3)
            assertThat(s.editor.calls).containsExactly("compose:B", "compose:Bo", "compose:Bon")
            s.type("j ")
            assertThat(s.editor.calls.drop(4)).containsExactly("finish", "commit:our ")
            assertThat(s.editor.composeStart).isEqualTo(-1)
        }

        @Test
        fun `barre de suggestions, libellé de l'espace et indicateur de langue`() {
            val s = setup()

            s.type("bonj")

            val state = s.session.state
            assertThat(state.bar.mode).isEqualTo(BarMode.WORDS)
            assertThat(state.bar.items[1]).isEqualTo(BarItem("Bonjour", highlighted = true))
            assertThat(state.bar.items[0]).isEqualTo(BarItem("Bonj", typed = true))
            assertThat(state.spaceLabel).isEqualTo("␣ Bonjour")
            assertThat(state.languageLabel).isEqualTo("FR")
            assertThat(state.prefix).isEqualTo("bonj")
        }

        @Test
        fun `après une validation, barre de ponctuation et apostrophe sans espace`() {
            val s = setup()

            s.type("bonjour ")

            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.PUNCTUATION)
            assertThat(s.session.state.bar.items.map { it?.text }).containsExactly(",", ".", "?", "!", ":", "…", "'")
            s.bar("'")
            assertThat(s.editor.toString()).isEqualTo("Bonjour'")
        }

        @Test
        fun `élision omise restituée`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))

            s.type("lecole ")

            assertThat(s.editor.toString()).isEqualTo("l'école ")
        }

        @Test
        fun `phrase anglaise, langue verrouillée, typographie anglaise`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))
            s.session.lockLanguage("en")

            s.type("how are you")
            s.type("?")

            assertThat(s.editor.toString()).isEqualTo("how are you? ")
            assertThat(s.session.state.languageLabel).isEqualTo("[EN]")
            s.session.lockLanguage(null)
            assertThat(s.session.state.languageLabel).isEqualTo("EN")
        }

        @Test
        fun `la langue suit les mots validés sans verrouillage`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))

            s.type("how are you the you ")

            assertThat(s.session.language!!.dominant).isEqualTo("en")
            assertThat(s.session.state.languageLabel).isEqualTo("EN")
        }

        @Test
        fun `annulation par retour arrière immédiat, puis effacement normal`() {
            val s = setup()
            s.type("bonj ")

            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("Bonj")
            assertThat(s.editor.composeStart to s.editor.composeEnd).isEqualTo(0 to 4)
            assertThat(rejections.rejections("Bonj", "Bonjour")).isEqualTo(1)
            s.backspace()
            assertThat(s.editor.toString()).isEqualTo("Bon")
        }

        @Test
        fun `incognito - l'annulation n'est pas mémorisée`() {
            val s = setup(field = field(imeOptions = C.IME_FLAG_NO_PERSONALIZED_LEARNING))
            s.type("bonj ")

            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("Bonj")
            assertThat(rejections.rejections("Bonj", "Bonjour")).isZero()
        }

        @Test
        fun `e-mail - raccourcis, ni majuscule, ni espace automatique, ni suggestion`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or C.TYPE_TEXT_FLAG_CAP_SENTENCES))

            s.type("bonj")
            s.bar("@")
            s.type("ex")
            s.bar(".com")
            s.type(".")

            assertThat(s.editor.toString()).isEqualTo("bonj@ex.com.")
            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.SHORTCUTS)
            assertThat(s.session.state.bar.items.map { it?.text }).containsExactly("@", ".", ".com", ".fr", "-", "_")
            assertThat(s.session.state.spaceLabel).isNull()
            assertThat(s.session.state.prefix).isNull()
            assertThat(s.editor.calls).noneMatch { it.startsWith("compose") }
        }

        @Test
        fun `sans suggestions - l'espace insère une espace, l'adaptation reste`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_NO_SUGGESTIONS))

            s.type("bonj bonj")

            assertThat(s.editor.toString()).isEqualTo("bonj bonj")
            assertThat(s.session.state.bar).isEqualTo(Bar.EMPTY)
            assertThat(s.session.state.prefix).isEqualTo("bonj")
        }

        @Test
        fun `mot de passe - aucune lecture du texte, chaque touche insérée telle quelle`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD))
            s.editor.reads = 0

            s.type("bonj ?1")

            assertThat(s.editor.toString()).isEqualTo("bonj ?1")
            assertThat(s.editor.reads).isZero()
            assertThat(s.session.state).isEqualTo(KeyboardState(Bar.EMPTY, null, null, ShiftState.OFF, null, s.session.state.languageVersion))
        }

        @Test
        fun `majuscules - glissé vers le haut, majuscule manuelle et verrouillée`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))

            s.session.onKey(Key.letter('a'), upper = true)
            s.session.toggleShift()
            s.session.onKey(Key.letter('b'))
            s.session.onKey(Key.letter('c'))
            s.session.toggleCapsLock()
            s.session.onKey(Key.letter('d'))
            s.session.onKey(Key.letter('e'))
            assertThat(s.session.state.shift).isEqualTo(ShiftState.LOCKED)
            s.session.toggleCapsLock()
            s.session.onKey(Key.letter('f'))

            assertThat(s.editor.toString()).isEqualTo("ABcDEf")
        }

        @Test
        fun `touches de la disposition - lettre, caractère, espace, effacement, entrée`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT, enter = EnterBehavior.NewLine))

            s.session.onKey(Key.letter('a'))
            s.session.onKey(Key.character('1'))
            s.session.onKey(Key(KeyKind.SPACE, "", 1f))
            s.session.onKey(Key(KeyKind.BACKSPACE, "⌫", 1f))
            s.session.onKey(Key(KeyKind.ENTER, "↵", 1f))
            s.session.onKey(Key(KeyKind.SYMBOLS, "?123", 1f))

            assertThat(s.editor.toString()).isEqualTo("a1\n")
        }

        @Test
        fun `entrée - action de l'éditeur ou touche entrée, mot en cours validé tel quel`() {
            val action = setup(field = field(C.TYPE_CLASS_TEXT, enter = EnterBehavior.EditorAction(3)))
            action.type("bonj")
            action.session.onEnter()

            val key = setup(field = field(C.TYPE_CLASS_TEXT))
            key.session.onEnter()

            assertThat(action.editor.toString()).isEqualTo("bonj")
            assertThat(action.editor.calls.takeLast(2)).containsExactly("finish", "action:3")
            assertThat(key.editor.calls).containsExactly("key:enter")
        }

        @Test
        fun `un signe de la couche symboles valide d'abord le mot`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))

            s.type("bonj(")

            assertThat(s.editor.toString()).isEqualTo("bonjour(")
        }

        @Test
        fun `apostrophe et trait d'union à l'intérieur d'un mot restent dans la composition`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))

            s.type("j'ai ")

            assertThat(s.editor.toString()).isEqualTo("j'ai ")
            assertThat(s.editor.calls).contains("compose:j'", "compose:j'a")
        }

        @Test
        fun `suggestion touchée - remplace le mot et ajoute l'espace automatique`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))
            s.type("bonj")

            s.bar("bonj")

            assertThat(s.editor.toString()).isEqualTo("bonj ")
            assertThat(s.session.state.bar.mode).isEqualTo(BarMode.PUNCTUATION)
        }

        @Test
        fun `déplacement du curseur - composition validée, annulation oubliée`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))
            s.type("bonj ")

            s.editor.moveCursor(s.session, 2)
            s.editor.moveCursor(s.session, 8)
            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("bonjour")
            assertThat(s.editor.composeStart to s.editor.composeEnd).isEqualTo(0 to 7)
        }

        @Test
        fun `distribution bilingue pour l'adaptation`() {
            val s = setup()

            val d = s.session.distribution("bo")

            assertThat(d.isUnknownPrefix).isFalse()
            assertThat(d.probability('n')).isGreaterThan(0.9)
        }

        @Test
        fun `menu de l'espace - verrouillage depuis un état de langue`() {
            val s = setup()

            s.session.lockLanguage("en")

            assertThat(s.session.language!!.locked).isEqualTo("en")
            assertThat(s.session.state.languageVersion).isGreaterThan(0)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `émoji vide refusé`() {
            assertThatThrownBy { setup().session.onEmoji("") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("vide")
        }

        @Test
        fun `glisser vers le haut sur l'espace sans mot en cours - vaut un appui sur l'espace`() {
            val s = setup()

            s.type("bonjour ")
            s.session.onSpaceSwipeUp()
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo(s.run { val o = setup(); o.type("bonjour  "); o.editor.toString() })
        }

        @Test
        fun `lettre ou caractère vide refusés`() {
            val s = setup()

            assertThatThrownBy { s.session.onLetter("") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("vide")
            assertThatThrownBy { s.session.onCharacter("") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("vide")
        }

        @Test
        fun `case de barre inexistante ou barre vide - sans effet`() {
            val s = setup()

            s.session.onBarItem(0)
            s.session.onBarItem(-1)
            s.type("bonj")
            s.session.onBarItem(7)

            assertThat(s.editor.toString()).isEqualTo("Bonj")
        }

        @Test
        fun `annulation impossible si le texte a changé - effacement normal`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))
            s.type("bonj ")
            s.editor.text.append("x")
            s.editor.selStart++
            s.editor.selEnd++

            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("bonjour ")
        }

        @Test
        fun `champ qui ne fournit pas son texte - pas de suggestion, retour arrière par touche`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES))
            s.editor.providesText = false
            s.session.onStartInput(field(), -1, -1)

            s.type("bon ")
            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("bon ")
            assertThat(s.editor.calls.last()).isEqualTo("key:backspace")
            assertThat(s.session.state.shift).isEqualTo(ShiftState.OFF)
            assertThat(s.session.state.spaceLabel).isNull()
        }

        @Test
        fun `sans prédicteur - saisie sans suggestions ni langue`() {
            val s = setup(withPredictor = false)

            s.type("bonj ")
            s.session.lockLanguage("fr")

            assertThat(s.editor.toString()).isEqualTo("Bonj ")
            assertThat(s.session.state.languageLabel).isNull()
            assertThat(s.session.state.prefix).isNull()
            assertThat(s.session.distribution("bo")).isSameAs(Distribution.UNKNOWN)
        }

        @Test
        fun `la composition validée par l'application est oubliée`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))
            s.type("bon")
            s.editor.composeStart = -1
            s.editor.composeEnd = -1

            s.session.onSelectionUpdate(3, 3, -1, -1)
            s.type("j")

            assertThat(s.editor.toString()).isEqualTo("bonj")
            assertThat(s.editor.calls).contains("region:0:3")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `émoji dans un champ qui ne lit pas le texte - inséré tel quel`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD))

            s.session.onEmoji("😀")
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo("😀")
        }

        @Test
        fun `glisser vers le haut sur l'espace dans un champ sans suggestions - une espace`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_NO_SUGGESTIONS))

            s.type("bo")
            s.session.onSpaceSwipeUp()
            s.editor.deliver(s.session)

            assertThat(s.editor.toString()).isEqualTo("bo ")
        }

        @Test
        fun `mises à jour de sélection en retard - reconnues, rien n'est réinitialisé`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))
            s.session.onLetter("b")
            s.session.onLetter("o")
            s.session.onLetter("n")
            s.session.onLetter("j")
            s.session.onSpace()

            s.editor.deliver(s.session)
            s.backspace()

            assertThat(s.editor.toString()).isEqualTo("bonj")
        }

        @Test
        fun `sélection - remplacée par la frappe, effacée par retour arrière`() {
            val s = setup("bonjour le chat", field(C.TYPE_CLASS_TEXT))
            s.editor.selStart = 8
            s.editor.selEnd = 10
            s.session.onSelectionUpdate(8, 10, -1, -1)

            s.backspace()
            s.type("la")

            assertThat(s.editor.toString()).isEqualTo("bonjour la chat")
        }

        @Test
        fun `retour arrière dans la composition jusqu'au mot vide, puis sur un émoji`() {
            val s = setup("😀", field(C.TYPE_CLASS_TEXT))
            s.type("a")

            s.backspace()
            s.backspace()

            assertThat(s.editor.toString()).isEmpty()
            assertThat(s.editor.calls).contains("compose:", "delete:2")
        }

        @Test
        fun `élision finale - le mot suivant commence sans espace`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))

            s.type("l'")
            s.session.onSpace()

            assertThat(s.editor.toString()).isEqualTo("l' ")
        }

        @Test
        fun `double espace - point et majuscule, seulement en fin de phrase`() {
            val s = setup()

            s.type("bonjour  ")

            assertThat(s.editor.toString()).isEqualTo("Bonjour. ")
            assertThat(s.session.state.shift).isEqualTo(ShiftState.ONCE)
        }

        @Test
        fun `espace au milieu du texte - pas d'espace automatique, pas de barre de ponctuation`() {
            val s = setup("x", field(C.TYPE_CLASS_TEXT))
            s.editor.moveCursor(s.session, 0)

            s.type("bonjour ")

            assertThat(s.editor.toString()).isEqualTo("bonjour x")
            assertThat(s.session.state.bar).isEqualTo(Bar.EMPTY)
        }

        @Test
        fun `fin de saisie - la composition est validée`() {
            val s = setup(field = field(C.TYPE_CLASS_TEXT))
            s.type("bon")

            s.session.onFinishInput()
            s.session.onFinishInput()

            assertThat(s.editor.calls.count { it == "finish" }).isEqualTo(1)
        }

        @Test
        fun `une seule langue - pas d'indicateur`() {
            val editor = FakeEditor()
            val session = InputSession(editor)
            session.setPredictor(Predictor(mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(french, "fr"))), KeyboardLayouts.AZERTY), "en")
            session.onStartInput(field(), 0, 0)

            assertThat(session.state.languageLabel).isNull()
            assertThat(session.language!!.languages).containsExactly("fr")
        }

        @Test
        fun `nouveau prédicteur sur les mêmes langues - l'état de langue est gardé`() {
            val s = setup()
            s.session.lockLanguage("en")

            s.session.setPredictor(predictor())

            assertThat(s.session.language!!.locked).isEqualTo("en")
            s.session.setPredictor(null)
            assertThat(s.session.language).isNull()
        }

        @Test
        fun `écouteur prévenu à chaque état`() {
            val s = setup()
            val states = mutableListOf<KeyboardState>()
            s.session.onStateChanged = { states += it }

            s.type("b")

            assertThat(states).hasSize(1)
            assertThat(states.last()).isEqualTo(s.session.state)
        }
    }
}
