package nextglyph.engine.input

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.LayoutRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NavKeysTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `flèche - événement clavier sans modificateur`() {
            assertThat(NavSelection().press(NavKey.LEFT)).isEqualTo(NavCommand.Event(PcKeyEvent(AndroidKeyCodes.KEYCODE_DPAD_LEFT, 0)))
        }

        @Test
        fun `mode Sélection - les déplacements portent Maj, mot par mot avec Ctrl`() {
            val selection = NavSelection()

            assertThat(selection.press(NavKey.SELECT)).isNull()
            assertThat(selection.selecting).isTrue()
            assertThat(selection.press(NavKey.RIGHT)).isEqualTo(NavCommand.Event(PcKeyEvent(AndroidKeyCodes.KEYCODE_DPAD_RIGHT, NavSelection.META_SHIFT)))
            assertThat(selection.press(NavKey.WORD_LEFT))
                .isEqualTo(NavCommand.Event(PcKeyEvent(AndroidKeyCodes.KEYCODE_DPAD_LEFT, PcModifier.CTRL.meta or NavSelection.META_SHIFT)))
            assertThat(selection.selecting).isTrue()
        }

        @Test
        fun `copier - action d'édition, fin du mode Sélection`() {
            val selection = NavSelection()
            selection.press(NavKey.SELECT)

            assertThat(selection.press(NavKey.COPY)).isEqualTo(NavCommand.Context(ContextAction.COPY))
            assertThat(selection.selecting).isFalse()
        }

        @Test
        fun `disposition - 3 rangées de 10 u, rôle Navigation, toutes les touches présentes`() {
            val layout = NavLayout.LAYOUT

            assertThat(layout.role).isEqualTo(LayoutRole.NAV_KEYS)
            assertThat(layout.rows).hasSize(3)
            layout.rows.forEach { assertThat(it.restUnits).isCloseTo(KeyboardLayout.ROW_UNITS, Offset.offset(1e-3f)) }
            val labels = layout.rows.flatMap { it.keys }.filter { it.kind == KeyKind.NAV }.map { it.label }
            assertThat(labels).containsExactlyInAnyOrderElementsOf(NavKey.entries.map { it.label })
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `libellé inconnu - aucune touche`() {
            assertThat(NavKey.of("Échap")).isNull()
            assertThat(NavKey.of("")).isNull()
        }

        @Test
        fun `Suppr en mode Sélection - pas de Maj, fin du mode`() {
            val selection = NavSelection()
            selection.press(NavKey.SELECT)

            assertThat(selection.press(NavKey.DELETE)).isEqualTo(NavCommand.Event(PcKeyEvent(AndroidKeyCodes.KEYCODE_FORWARD_DEL, 0)))
            assertThat(selection.selecting).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `deux appuis sur Sélect - retour au mode normal, remise à zéro`() {
            val selection = NavSelection()
            selection.press(NavKey.SELECT)
            selection.press(NavKey.SELECT)
            assertThat(selection.selecting).isFalse()

            selection.press(NavKey.SELECT)
            selection.reset()
            assertThat(selection.selecting).isFalse()
            assertThat(selection.press(NavKey.HOME)).isEqualTo(NavCommand.Event(PcKeyEvent(AndroidKeyCodes.KEYCODE_MOVE_HOME, 0)))
        }

        @Test
        fun `touches - déplacement, libellés et actions`() {
            assertThat(NavKey.entries.filter { it.moves }).containsExactlyInAnyOrder(
                NavKey.UP, NavKey.DOWN, NavKey.LEFT, NavKey.RIGHT, NavKey.WORD_LEFT, NavKey.WORD_RIGHT, NavKey.HOME, NavKey.END,
            )
            assertThat(NavKey.of("Coller")).isEqualTo(NavKey.PASTE)
            assertThat(NavKey.SELECT.key(1.5f).kind).isEqualTo(KeyKind.NAV)
            assertThat(NavSelection().press(NavKey.SELECT_ALL)).isEqualTo(NavCommand.Context(ContextAction.SELECT_ALL))
            assertThat(NavSelection().press(NavKey.PASTE)).isEqualTo(NavCommand.Context(ContextAction.PASTE))
            assertThat(NavSelection().press(NavKey.CUT)).isEqualTo(NavCommand.Context(ContextAction.CUT))
            assertThat(ContextAction.entries).hasSize(4)
        }
    }
}
