package nextglyph.engine.layout

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class KeyboardLayoutsTest {

    private fun KeyRow.labels() = keys.joinToString("") { if (it.kind == KeyKind.LETTER) it.label else "|" }

    @Nested
    inner class HappyPath {
        @Test
        fun `AZERTY a les lettres du §5-1`() {
            val rows = KeyboardLayouts.AZERTY.rows

            assertThat(rows.map { it.keys.filter { k -> k.kind == KeyKind.LETTER }.joinToString("") { k -> k.label } })
                .containsExactly("azertyuiop", "qsdfghjklm", "wxcvbn")
        }

        @Test
        fun `QWERTY a les lettres du §5-1`() {
            val rows = KeyboardLayouts.QWERTY.rows

            assertThat(rows.map { it.keys.filter { k -> k.kind == KeyKind.LETTER }.joinToString("") { k -> k.label } })
                .containsExactly("qwertyuiop", "asdfghjkl", "zxcvbnm")
        }

        @Test
        fun `rangée 3 AZERTY au repos`() {
            val row = KeyboardLayouts.AZERTY.rows[2]

            assertThat(row.keys.map { it.kind }).containsExactly(
                KeyKind.SYMBOLS, KeyKind.LETTER, KeyKind.LETTER, KeyKind.LETTER, KeyKind.LETTER, KeyKind.LETTER, KeyKind.LETTER,
                KeyKind.SPACE, KeyKind.ENTER, KeyKind.BACKSPACE,
            )
            assertThat(row.restWidths).containsExactly(1f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 1.6f, 1f, 1f)
            assertThat(row.leadingInset).isZero()
        }

        @Test
        fun `rangée 2 QWERTY retrait et Entrée`() {
            val row = KeyboardLayouts.QWERTY.rows[1]

            assertThat(row.leadingInset).isEqualTo(0.3f)
            assertThat(row.restWidths).containsExactly(0.95f, 0.95f, 0.95f, 0.95f, 0.95f, 0.95f, 0.95f, 0.95f, 0.95f, 1.15f)
            assertThat(row.keys.last().kind).isEqualTo(KeyKind.ENTER)
        }

        @Test
        fun `rangée 3 QWERTY sans Entrée`() {
            val row = KeyboardLayouts.QWERTY.rows[2]

            assertThat(row.keys.map { it.kind }.distinct()).containsExactly(KeyKind.SYMBOLS, KeyKind.LETTER, KeyKind.SPACE, KeyKind.BACKSPACE)
            assertThat(row.restWidths.last()).isEqualTo(1f)
            assertThat(row.restWidths[row.restWidths.size - 2]).isEqualTo(1.7f)
            assertThat(row.labels()).isEqualTo("|zxcvbnm||")
        }

        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `of renvoie la disposition du même genre`(kind: LayoutKind) {
            assertThat(KeyboardLayouts.of(kind).kind).isEqualTo(kind)
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `chaque touche fonctionnelle est présente exactement une fois`(kind: LayoutKind) {
            val keys = KeyboardLayouts.of(kind).rows.flatMap { it.keys }

            for (functional in listOf(KeyKind.SYMBOLS, KeyKind.SPACE, KeyKind.ENTER, KeyKind.BACKSPACE)) {
                assertThat(keys.count { it.kind == functional }).describedAs(functional.name).isEqualTo(1)
            }
        }

        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `chaque lettre de a à z apparaît exactement une fois`(kind: LayoutKind) {
            val letters = KeyboardLayouts.of(kind).rows.flatMap { it.keys }.filter { it.kind == KeyKind.LETTER }.map { it.label }

            assertThat(letters).hasSize(26).doesNotHaveDuplicates()
            assertThat(letters.sorted().joinToString("")).isEqualTo("abcdefghijklmnopqrstuvwxyz")
        }
    }

    @Nested
    inner class BoundaryValues {
        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `chaque rangée fait 10 u retrait compris`(kind: LayoutKind) {
            for (row in KeyboardLayouts.of(kind).rows) {
                assertThat(row.restUnits).isCloseTo(10f, Offset.offset(1e-4f))
            }
        }

        @Test
        fun `les rangées 1 et 2 AZERTY ont des lettres de 1 u`() {
            val widths = KeyboardLayouts.AZERTY.rows.take(2).flatMap { it.restWidths }

            assertThat(widths).hasSize(20).containsOnly(1f)
        }

        @Test
        fun `trois rangées par disposition`() {
            assertThat(KeyboardLayouts.AZERTY.rows).hasSize(3)
            assertThat(KeyboardLayouts.QWERTY.rows).hasSize(3)
        }
    }
}
