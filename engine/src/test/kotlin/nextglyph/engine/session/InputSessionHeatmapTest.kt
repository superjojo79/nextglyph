package nextglyph.engine.session

import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.stats.KeyHeatmap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Carte de frappe (issue #46) branchée dans la session : compteurs par touche, jamais de texte. */
class InputSessionHeatmapTest {

    private val textType = C.TYPE_CLASS_TEXT
    private val password = C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD
    private val decimal = C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL
    private val centered = TapOffset(0.0, 0.0)
    private val backspaceKey = Key(KeyKind.BACKSPACE, "⌫", 1f)
    private val spaceKey = Key(KeyKind.SPACE, "", 1.6f)
    private val enterKey = Key(KeyKind.ENTER, "↵", 1f)
    private val symbolsKey = Key(KeyKind.SYMBOLS, "?123", 1f)

    private class Setup(val editor: FakeEditor, val session: InputSession, val map: KeyHeatmap)

    private fun setup(inputType: Int = textType, incognito: Boolean = false, enter: EnterBehavior = EnterBehavior.NewLine): Setup {
        val editor = FakeEditor()
        val session = InputSession(editor)
        val predictor = Predictor(
            linkedMapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("bonjour" to 100L, "bon" to 50L), "fr"))),
            KeyboardLayouts.AZERTY,
        )
        session.setPredictor(predictor)
        val map = KeyHeatmap()
        session.heatmap = map
        val profile = InputProfileResolver.resolve(inputType, if (incognito) C.IME_FLAG_NO_PERSONALIZED_LEARNING else 0, null)
        session.onStartInput(SessionField(profile, enter), 0, 0)
        return Setup(editor, session, map)
    }

    private fun Setup.letter(c: Char, offset: TapOffset? = centered) {
        session.onKey(Key.letter(c), offset = offset)
        editor.deliver(session)
    }

    private fun Setup.tap(key: Key, offset: TapOffset? = centered) {
        session.onKey(key, offset = offset)
        editor.deliver(session)
    }

    private fun KeyHeatmap.taps(c: Char) = keys().first { it.letter == c }.taps
    private fun KeyHeatmap.errors(c: Char) = keys().first { it.letter == c }.errors
    private fun KeyHeatmap.errors(kind: KeyKind) = keys().first { it.kind == kind }.errors
    private fun KeyHeatmap.taps(kind: KeyKind) = keys().first { it.kind == kind }.taps

    @Nested
    inner class HappyPath {
        @Test
        fun `une frappe de lettre est comptée à son décalage`() {
            val s = setup()

            s.letter('e', TapOffset(-0.45, 0.45))

            assertThat(s.map.taps('e')).isEqualTo(1)
            assertThat(s.map.keys().first { it.letter == 'e' }.cells[KeyHeatmap.COLS * (KeyHeatmap.ROWS - 1)]).isEqualTo(1)
        }

        @Test
        fun `espace, entrée, retour arrière et touche symboles sont comptés`() {
            val s = setup()
            s.letter('a')

            s.tap(spaceKey)
            s.tap(enterKey)
            s.tap(backspaceKey)
            s.session.onLayerKey(symbolsKey, centered)

            assertThat(s.map.taps(KeyKind.SPACE)).isEqualTo(1)
            assertThat(s.map.taps(KeyKind.ENTER)).isEqualTo(1)
            assertThat(s.map.taps(KeyKind.BACKSPACE)).isEqualTo(1)
            assertThat(s.map.taps(KeyKind.SYMBOLS)).isEqualTo(1)
        }

        @Test
        fun `un retour arrière sur un caractère compte l'erreur pour la bonne touche`() {
            val s = setup()
            s.letter('a')
            s.letter('z')

            s.tap(backspaceKey)

            assertThat(s.map.errors('z')).isEqualTo(1)
            assertThat(s.map.errors('a')).isZero()
        }

        @Test
        fun `un caractère de signe garde la pile alignée sans erreur`() {
            val s = setup()
            s.letter('a')
            s.session.onCharacter("1")

            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
            s.tap(backspaceKey)
            assertThat(s.map.errors('a')).isEqualTo(1)
        }

        @Test
        fun `une lettre sans position ou accentuée garde la pile alignée sans erreur`() {
            val s = setup()
            s.letter('a')
            s.letter('b', null)
            s.session.onLetter("é", offset = centered)

            s.tap(backspaceKey)
            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
            assertThat(s.map.taps('b')).isZero()
        }

        @Test
        fun `l'annulation d'une validation automatique n'est pas une erreur`() {
            val s = setup()
            for (c in "bonj") s.letter(c)
            s.tap(spaceKey)
            assertThat(s.editor.text.toString()).isEqualTo("bonjour ")

            s.tap(backspaceKey)
            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
        }

        @Test
        fun `l'espace effacée compte une erreur pour l'espace`() {
            val s = setup()
            s.letter('z')
            s.tap(spaceKey)

            s.tap(backspaceKey)

            assertThat(s.map.errors(KeyKind.SPACE)).isEqualTo(1)
        }

        @Test
        fun `un espace par glissé garde la pile alignée`() {
            val s = setup()
            s.letter('a')
            s.session.onSpaceSwipeUp()
            s.editor.deliver(s.session)

            s.tap(backspaceKey)

            assertThat(s.map.errors('a')).isZero()
        }

        @Test
        fun `un espace sans position garde la pile alignée`() {
            val s = setup()
            s.letter('a')
            s.session.onSpace()
            s.editor.deliver(s.session)

            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
            s.tap(backspaceKey)
            assertThat(s.map.errors('a')).isEqualTo(1)
        }

        @Test
        fun `une entrée sans position garde la pile alignée`() {
            val s = setup()
            s.letter('a')
            s.session.onEnter()
            s.editor.deliver(s.session)

            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
            s.tap(backspaceKey)
            assertThat(s.map.errors('a')).isEqualTo(1)
        }

        @Test
        fun `entrée avec saut de ligne est empilée, entrée d'action ne l'est pas`() {
            val newline = setup()
            newline.letter('a')
            newline.tap(enterKey)
            newline.tap(backspaceKey)
            val action = setup(enter = EnterBehavior.EditorAction(C.IME_ACTION_DONE))
            action.letter('a')
            action.tap(enterKey)
            action.tap(backspaceKey)

            assertThat(newline.map.errors(KeyKind.ENTER)).isEqualTo(1)
            assertThat(action.map.keys().sumOf { it.errors }).isZero()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `incognito - rien n'est compté`() {
            val s = setup(incognito = true)

            s.letter('a')
            s.tap(spaceKey)
            s.tap(backspaceKey)

            assertThat(s.map.isEmpty).isTrue()
            assertThat(s.map.changed).isFalse()
        }

        @Test
        fun `mot de passe - rien n'est compté`() {
            val s = setup(inputType = password)

            s.letter('a')
            s.tap(backspaceKey)
            s.tap(spaceKey)

            assertThat(s.map.isEmpty).isTrue()
            assertThat(s.map.changed).isFalse()
        }

        @Test
        fun `pavé décimal - rien n'est compté`() {
            val s = setup(inputType = decimal)

            s.letter('a')

            assertThat(s.map.isEmpty).isTrue()
        }

        @Test
        fun `sans carte, la session fonctionne comme avant`() {
            val s = setup()
            s.session.heatmap = null

            s.letter('a')
            s.tap(backspaceKey)
            s.tap(enterKey)

            assertThat(s.map.isEmpty).isTrue()
        }

        @Test
        fun `un retour arrière dans un champ sans lecture du texte n'attribue aucune erreur`() {
            val s = setup(inputType = decimal)

            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
        }

        @Test
        fun `un décalage hors de la touche n'est pas compté et garde la pile alignée`() {
            val s = setup()
            s.letter('a')
            s.letter('b', TapOffset(0.9, 0.0))

            s.tap(backspaceKey)

            assertThat(s.map.taps('b')).isZero()
            assertThat(s.map.errors('a')).isZero()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un changement de champ oublie les frappes récentes`() {
            val s = setup()
            s.letter('a')

            s.session.onStartInput(SessionField(InputProfileResolver.resolve(textType, 0, null), EnterBehavior.NewLine), 0, 0)
            s.tap(backspaceKey)

            assertThat(s.map.errors('a')).isZero()
        }

        @Test
        fun `la fin de saisie oublie les frappes récentes`() {
            val s = setup()
            s.letter('a')

            s.session.onFinishInput()
            s.tap(backspaceKey)

            assertThat(s.map.errors('a')).isZero()
        }

        @Test
        fun `un curseur déplacé oublie les frappes récentes`() {
            val s = setup()
            s.letter('a')
            s.letter('b')

            s.session.onSelectionUpdate(0, 0, -1, -1)
            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
        }

        @Test
        fun `l'effacement d'un mot oublie les frappes récentes`() {
            val s = setup()
            s.letter('a')

            s.session.onDeleteWord()
            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
        }

        @Test
        fun `l'effacement d'un mot oublie les frappes même avec du texte restant`() {
            val s = setup()
            s.letter('a')
            s.letter('b')
            s.tap(spaceKey)
            s.letter('c')

            s.session.onDeleteWord()
            s.editor.deliver(s.session)
            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
        }

        @Test
        fun `un émoji, un texte collé ou un raccourci oublient les frappes récentes`() {
            for (insert in listOf<(InputSession) -> Unit>({ it.onEmoji("😀") }, { it.onClipText("xyz") }, { it.onShortcut("@") })) {
                val s = setup()
                s.letter('a')

                insert(s.session)
                s.tap(backspaceKey)

                assertThat(s.map.keys().sumOf { it.errors }).isZero()
            }
        }

        @Test
        fun `le choix d'une suggestion oublie les frappes récentes`() {
            val s = setup()
            for (c in "bonj") s.letter(c)

            s.session.onBarItem(1)
            s.editor.deliver(s.session)
            s.tap(backspaceKey)

            assertThat(s.map.keys().sumOf { it.errors }).isZero()
        }

        @Test
        fun `la forme enregistrée ne contient aucun texte`() {
            val s = setup()
            for (c in "bonjour") s.letter(c)

            assertThat(s.map.serialize()).matches("[h0-9,;]+")
        }
    }
}
