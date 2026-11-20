package nextglyph.engine.input

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.LayoutRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PcKeysTest {

    private val ctrl = AndroidKeyCodes.META_CTRL_ON or AndroidKeyCodes.META_CTRL_LEFT_ON
    private val alt = AndroidKeyCodes.META_ALT_ON or AndroidKeyCodes.META_ALT_LEFT_ON

    @Nested
    inner class HappyPath {

        @Test
        fun `touche simple - son code, sans modificateur`() {
            val m = PcModifiers()

            assertThat(m.press(PcKey.ESCAPE, 0, 300)).isEqualTo(PcKeyEvent(111, 0))
            assertThat(m.press(PcKey.F12, 0, 300)).isEqualTo(PcKeyEvent(142, 0))
            assertThat(m.press(PcKey.TAB, 0, 300)).isEqualTo(PcKeyEvent(61, 0))
        }

        @Test
        fun `Ctrl puis c - Ctrl+C, puis le modificateur est relâché`() {
            val m = PcModifiers()

            assertThat(m.press(PcKey.CTRL, 0, 300)).isNull()
            assertThat(m.state(PcModifier.CTRL)).isEqualTo(PcModifiers.State.ONCE)
            assertThat(m.character('c')).isEqualTo(PcKeyEvent(AndroidKeyCodes.KEYCODE_A + 2, ctrl))
            assertThat(m.active).isFalse()
            assertThat(m.character('c')).isNull()
        }

        @Test
        fun `Ctrl+Alt+Suppr en une touche`() {
            assertThat(PcModifiers().press(PcKey.CTRL_ALT_DELETE, 0, 300)).isEqualTo(PcKeyEvent(112, ctrl or alt))
        }

        @Test
        fun `Alt puis Tab - Alt+Tab`() {
            val m = PcModifiers()

            m.press(PcKey.ALT, 0, 300)

            assertThat(m.press(PcKey.TAB, 1000, 300)).isEqualTo(PcKeyEvent(61, alt))
        }

        @Test
        fun `panneau - 3 rangées de 10 u, retour aux lettres et effacement`() {
            val layout = PcLayout.LAYOUT

            assertThat(layout.role).isEqualTo(LayoutRole.PC_KEYS)
            assertThat(layout.rows).hasSize(3)
            assertThat(layout.rows.map { it.restUnits }).allMatch { KeyboardLayout.isRowSum(it) }
            val kinds = layout.rows.flatMap { row -> row.keys.map { it.kind } }
            assertThat(kinds).contains(KeyKind.LETTERS, KeyKind.BACKSPACE)
            val pc = layout.rows.flatMap { it.keys }.filter { it.kind == KeyKind.PC }
            assertThat(pc.map { PcKey.of(it.label) }).doesNotContainNull().hasSize(PcKey.entries.size)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `caractère sans modificateur actif - pas d'événement`() {
            assertThat(PcModifiers().character('c')).isNull()
        }

        @Test
        fun `caractère sans touche - pas d'événement, le modificateur reste actif`() {
            val m = PcModifiers()
            m.press(PcKey.CTRL, 0, 300)

            assertThat(m.character('é')).isNull()
            assertThat(m.character('-')).isNull()
            assertThat(m.active).isTrue()
        }

        @Test
        fun `libellé inconnu - pas de touche PC`() {
            assertThat(PcKey.of("Fn")).isNull()
            assertThat(PcModifiers.keyCodeOf('@')).isNull()
        }

        @Test
        fun `deux taps lents - le modificateur est relâché, pas verrouillé`() {
            val m = PcModifiers()

            m.press(PcKey.CTRL, 0, 300)
            m.press(PcKey.CTRL, 301, 300)

            assertThat(m.state(PcModifier.CTRL)).isEqualTo(PcModifiers.State.OFF)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `double tap à la limite - verrouillé, reste actif après une touche, relâché par un tap`() {
            val m = PcModifiers()

            m.press(PcKey.CTRL, 0, 300)
            m.press(PcKey.CTRL, 300, 300)
            assertThat(m.state(PcModifier.CTRL)).isEqualTo(PcModifiers.State.LOCKED)
            assertThat(m.character('z')).isEqualTo(PcKeyEvent(AndroidKeyCodes.KEYCODE_A + 25, ctrl))
            assertThat(m.state(PcModifier.CTRL)).isEqualTo(PcModifiers.State.LOCKED)

            m.press(PcKey.CTRL, 5000, 300)
            assertThat(m.state(PcModifier.CTRL)).isEqualTo(PcModifiers.State.OFF)
        }

        @Test
        fun `codes extrêmes - a, z, 0, 9 et majuscules`() {
            assertThat(PcModifiers.keyCodeOf('a')).isEqualTo(29)
            assertThat(PcModifiers.keyCodeOf('Z')).isEqualTo(54)
            assertThat(PcModifiers.keyCodeOf('0')).isEqualTo(7)
            assertThat(PcModifiers.keyCodeOf('9')).isEqualTo(16)
            assertThat(PcModifiers.keyCodeOf('`')).isNull()
            assertThat(PcModifiers.keyCodeOf('{')).isNull()
            assertThat(PcModifiers.keyCodeOf('/')).isNull()
            assertThat(PcModifiers.keyCodeOf(':')).isNull()
        }

        @Test
        fun `reset relâche tout, y compris un verrouillage`() {
            val m = PcModifiers()
            m.press(PcKey.ALT, 0, 300)
            m.press(PcKey.ALT, 10, 300)

            m.reset()

            assertThat(m.active).isFalse()
            m.press(PcKey.ALT, 20, 300)
            assertThat(m.state(PcModifier.ALT)).isEqualTo(PcModifiers.State.ONCE)
        }

        @Test
        fun `touche PC - pas de texte littéral`() {
            assertThat(PcKey.F1.key().text).isNull()
            assertThat(PcKey.CTRL_ALT_DELETE.key(1.5f).restWidth).isEqualTo(1.5f)
        }
    }
}
