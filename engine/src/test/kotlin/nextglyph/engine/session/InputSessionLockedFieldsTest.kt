package nextglyph.engine.session

import nextglyph.engine.edit.ShiftState
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.DecimalSeparatorPolicy
import nextglyph.engine.profile.FieldKey
import nextglyph.engine.profile.InputProfileResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Champs sans lecture du texte : mot de passe (§7), pavés (§8), disposition classique (§4). */
class InputSessionLockedFieldsTest {

    private val password = C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD
    private val decimal = C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL
    private val fieldKey = FieldKey("app", 7)

    private fun predictor() = Predictor(
        linkedMapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("bonjour" to 100L, "bon" to 50L), "fr"))),
        KeyboardLayouts.AZERTY,
    )

    private fun field(inputType: Int, enter: EnterBehavior = EnterBehavior.EnterKey) =
        SessionField(InputProfileResolver.resolve(inputType, 0, null), enter)

    private fun session(target: SessionTarget, inputType: Int = password, enter: EnterBehavior = EnterBehavior.EnterKey): InputSession {
        val session = InputSession(target)
        session.setPredictor(predictor())
        session.onStartInput(field(inputType, enter), 0, 0)
        // Le prédicteur a été installé avant le champ, dans le champ de texte par défaut.
        when (target) {
            is FakeEditor -> target.reads = 0
            is FilteringEditor -> target.inner.reads = 0
        }
        return session
    }

    /** Éditeur qui filtre les caractères refusés, comme un `DigitsKeyListener`. */
    private class FilteringEditor(private val accepted: String, val inner: FakeEditor = FakeEditor()) : SessionTarget by inner {
        override fun commitText(text: String) {
            inner.commitText(text.filter { it in accepted })
        }
    }

    private fun InputSession.typeLetters(text: String) {
        for (c in text) onLetter(c.lowercase(), upper = c.isUpperCase())
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `mot de passe - aucune lecture du champ, chaque touche insérée telle quelle`() {
            val editor = FakeEditor()
            val s = session(editor, enter = EnterBehavior.EditorAction(C.IME_ACTION_DONE))

            s.typeLetters("bonj")
            s.onKey(Key.character('1'))
            s.onSpace()
            s.onBackspace()
            s.onCharacter("!")
            s.onEnter()

            assertThat(editor.reads).isZero()
            assertThat(editor.calls).containsExactly(
                "commit:b", "commit:o", "commit:n", "commit:j", "commit:1", "commit: ", "key:backspace", "commit:!", "action:6",
            )
        }

        @Test
        fun `mot de passe - rien de ce qui est tapé n'est conservé par la session`() {
            val s = session(FakeEditor())

            s.typeLetters("secret")

            assertThat(s.prediction).isNull()
            assertThat(s.state.bar).isEqualTo(Bar.EMPTY)
            assertThat(s.state.spaceLabel).isNull()
            assertThat(s.state.prefix).isNull()
            assertThat(s.state.toString()).doesNotContain("secret")
        }

        @Test
        fun `⇧ - un appui donne une seule majuscule`() {
            val editor = FakeEditor()
            val s = session(editor)

            s.onShiftKey(1_000)
            assertThat(s.state.shift).isEqualTo(ShiftState.ONCE)
            s.typeLetters("ab")

            assertThat(editor.toString()).isEqualTo("Ab")
            assertThat(s.state.shift).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `⇧ - un double appui verrouille, un troisième déverrouille`() {
            val editor = FakeEditor()
            val s = session(editor)

            s.onShiftKey(1_000)
            s.onShiftKey(1_200)
            s.typeLetters("abc")
            val locked = s.state.shift
            s.onShiftKey(5_000)
            s.typeLetters("d")

            assertThat(locked).isEqualTo(ShiftState.LOCKED)
            assertThat(editor.toString()).isEqualTo("ABCd")
            assertThat(s.state.shift).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `pavé décimal - le séparateur préféré est inséré quand le champ l'accepte`() {
            val editor = FilteringEditor("0123456789,.")
            val s = session(editor, decimal)
            s.onKey(Key.character('3'))

            val inserted = s.onDecimalSeparator(DecimalSeparatorPolicy(), fieldKey)

            assertThat(inserted).isEqualTo(',')
            assertThat(editor.inner.toString()).isEqualTo("3,")
        }

        @Test
        fun `pavé décimal - repli sur le point quand le champ refuse la virgule, puis mémorisé`() {
            val editor = FilteringEditor("0123456789.")
            val policy = DecimalSeparatorPolicy()
            val s = session(editor, decimal)
            s.onKey(Key.character('3'))

            val inserted = s.onDecimalSeparator(policy, fieldKey)

            assertThat(inserted).isEqualTo('.')
            assertThat(editor.inner.toString()).isEqualTo("3.")
            assertThat(policy.separatorFor(fieldKey)).isEqualTo('.')
        }

        @Test
        fun `après une touche de pavé, la mise à jour de sélection est acceptée sans effet`() {
            val editor = FakeEditor()
            val s = session(editor, C.TYPE_CLASS_PHONE)
            s.onKey(Key.character('0'))
            s.onBackspace()

            editor.deliver(s)
            s.onSelectionUpdate(0, 0, -1, -1)

            assertThat(editor.reads).isZero()
            assertThat(s.state.bar).isEqualTo(Bar.EMPTY)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un délai de double appui négatif est refusé`() {
            val s = session(FakeEditor())

            assertThatThrownBy { s.onShiftKey(0, -1) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `pavé décimal - champ qui refuse les deux séparateurs, rien n'est inséré`() {
            val editor = FilteringEditor("0123456789")
            val s = session(editor, decimal)

            val inserted = s.onDecimalSeparator(DecimalSeparatorPolicy(), fieldKey)

            assertThat(inserted).isNull()
            assertThat(editor.inner.toString()).isEmpty()
        }

        @Test
        fun `⌫ dans un mot de passe vide - touche envoyée sans lecture`() {
            val editor = FakeEditor()
            val s = session(editor)

            s.onBackspace()
            s.onBackspace()

            assertThat(editor.reads).isZero()
            assertThat(editor.calls).containsExactly("key:backspace", "key:backspace")
        }

        @Test
        fun `⇧ - un second appui trop tardif annule la majuscule au lieu de verrouiller`() {
            val s = session(FakeEditor())

            s.onShiftKey(1_000)
            s.onShiftKey(2_000)

            assertThat(s.state.shift).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `⇧ - une horloge qui recule n'est pas un double appui`() {
            val s = session(FakeEditor())

            s.onShiftKey(1_000)
            s.onShiftKey(900)

            assertThat(s.state.shift).isEqualTo(ShiftState.OFF)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `⇧ - double appui exactement au délai, puis un milliseconde de trop`() {
            val exact = session(FakeEditor())
            exact.onShiftKey(1_000)
            exact.onShiftKey(1_000 + InputSession.DOUBLE_TAP_MS)

            val late = session(FakeEditor())
            late.onShiftKey(1_000)
            late.onShiftKey(1_001 + InputSession.DOUBLE_TAP_MS)

            assertThat(exact.state.shift).isEqualTo(ShiftState.LOCKED)
            assertThat(late.state.shift).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `⇧ - double appui au même instant, délai nul`() {
            val s = session(FakeEditor())

            s.onShiftKey(1_000, 0)
            s.onShiftKey(1_000, 0)

            assertThat(s.state.shift).isEqualTo(ShiftState.LOCKED)
        }

        @Test
        fun `⇧ - un appui après une lettre repart d'une majuscule simple`() {
            val editor = FakeEditor()
            val s = session(editor)

            s.onShiftKey(1_000)
            s.typeLetters("a")
            s.onShiftKey(1_100)

            assertThat(s.state.shift).isEqualTo(ShiftState.ONCE)
        }

        @Test
        fun `⇧ - une majuscule posée autrement n'est pas le premier appui d'un double appui`() {
            val s = session(FakeEditor())
            s.onShiftKey(1_000)
            s.onShiftKey(1_050)
            s.onShiftKey(1_100)

            s.toggleShift()
            s.onShiftKey(1_150)

            assertThat(s.state.shift).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `⇧ - majuscule manuelle sans appui préalable, un appui l'annule`() {
            val s = session(FakeEditor())

            s.toggleShift()
            s.onShiftKey(0)

            assertThat(s.state.shift).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `⌫ et séparateur décimal notifient le nouvel état`() {
            val s = session(FilteringEditor("0123456789."), decimal)
            var notified = 0
            s.onStateChanged = { notified++ }

            s.onBackspace()
            s.onDecimalSeparator(DecimalSeparatorPolicy(), fieldKey)

            assertThat(notified).isEqualTo(2)
        }

        @Test
        fun `un nouveau champ oublie la majuscule et le premier appui`() {
            val s = session(FakeEditor())
            s.onShiftKey(1_000)

            s.onStartInput(field(password), 0, 0)
            s.onShiftKey(1_100)

            assertThat(s.state.shift).isEqualTo(ShiftState.ONCE)
        }

        @Test
        fun `les touches de couche, de majuscule, de séparateur et vides n'écrivent rien`() {
            val editor = FakeEditor()
            val s = session(editor)

            for (kind in listOf(KeyKind.SYMBOLS, KeyKind.LETTERS, KeyKind.SHIFT, KeyKind.DECIMAL, KeyKind.EMPTY)) s.onKey(Key(kind, "", 1f))

            assertThat(editor.calls).isEmpty()
            assertThat(editor.reads).isZero()
        }

        @Test
        fun `disposition classique d'un texte - aucune lecture non plus`() {
            val editor = FakeEditor("Déjà ")
            val s = InputSession(editor)
            s.setPredictor(predictor())
            val classic = InputProfileResolver.classic(InputProfileResolver.resolve(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES, 0, null))
            s.onStartInput(SessionField(classic), 5, 5)
            editor.reads = 0

            s.typeLetters("bon")
            s.onSpace()
            s.onBackspace()

            assertThat(editor.reads).isZero()
            assertThat(editor.calls).containsExactly("commit:b", "commit:o", "commit:n", "commit: ", "key:backspace")
        }
    }
}
