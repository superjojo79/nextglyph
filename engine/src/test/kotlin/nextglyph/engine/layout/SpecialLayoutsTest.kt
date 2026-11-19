package nextglyph.engine.layout

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class SpecialLayoutsTest {

    private fun KeyRow.labels(): List<String> = keys.map { if (it.kind == KeyKind.EMPTY) "_" else it.label.ifEmpty { "␣" } }

    private fun KeyboardLayout.grid(): List<List<String>> = rows.map { it.labels() }

    private fun everyLayout(): List<KeyboardLayout> = buildList {
        for (kind in LayoutKind.entries) {
            add(SpecialLayouts.password(kind))
            add(SpecialLayouts.passwordSymbols(kind))
            add(SpecialLayouts.textSymbols(kind))
        }
        for (signed in listOf(false, true)) for (decimal in listOf(false, true)) add(SpecialLayouts.number(signed, decimal))
        add(SpecialLayouts.pin())
        add(SpecialLayouts.phone())
        for (variant in DateTimeVariant.entries) add(SpecialLayouts.dateTime(variant))
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `mot de passe AZERTY a les 6 rangées du §7-1`() {
            val layout = SpecialLayouts.password(LayoutKind.AZERTY)

            assertThat(layout.role).isEqualTo(LayoutRole.PASSWORD)
            assertThat(layout.grid()).containsExactly(
                listOf("@", "#", "$", "%", "&", "*", "-", "_", "!", "?"),
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m"),
                listOf("⇧", "w", "x", "c", "v", "b", "n", "⌫"),
                listOf("?123", ",", "␣", ".", "↵"),
            )
        }

        @Test
        fun `mot de passe QWERTY a les mêmes rangées de symboles et de chiffres`() {
            val layout = SpecialLayouts.password(LayoutKind.QWERTY)

            assertThat(layout.grid()).containsExactly(
                listOf("@", "#", "$", "%", "&", "*", "-", "_", "!", "?"),
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"),
                listOf("?123", ",", "␣", ".", "↵"),
            )
            assertThat(layout.rows[3].leadingInset).isEqualTo(0.5f)
        }

        @Test
        fun `les touches du mot de passe ont les bonnes natures`() {
            val rows = SpecialLayouts.password(LayoutKind.AZERTY).rows

            assertThat(rows[0].keys.map { it.kind }.distinct()).containsExactly(KeyKind.CHARACTER)
            assertThat(rows[1].keys.map { it.kind }.distinct()).containsExactly(KeyKind.CHARACTER)
            assertThat(rows[2].keys.map { it.kind }.distinct()).containsExactly(KeyKind.LETTER)
            assertThat(rows[4].keys.first().kind).isEqualTo(KeyKind.SHIFT)
            assertThat(rows[4].keys.last().kind).isEqualTo(KeyKind.BACKSPACE)
            assertThat(rows[5].keys.map { it.kind }).containsExactly(
                KeyKind.SYMBOLS, KeyKind.CHARACTER, KeyKind.SPACE, KeyKind.CHARACTER, KeyKind.ENTER,
            )
        }

        @Test
        fun `couche de symboles du texte - 3 rangées, rangée 3 comme les lettres AZERTY`() {
            val layout = SpecialLayouts.textSymbols()

            assertThat(layout.role).isEqualTo(LayoutRole.TEXT_SYMBOLS)
            assertThat(layout.rows).hasSize(3)
            assertThat(layout.grid()).containsExactly(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "€", "%", "&", "-", "+", "(", ")", "/"),
                listOf("ABC", ",", ".", "!", "?", "'", ":", "␣", "↵", "⌫"),
            )
            assertThat(layout.rows[2].restWidths).isEqualTo(KeyboardLayouts.AZERTY.rows[2].restWidths)
            assertThat(layout.rows[2].keys.first().kind).isEqualTo(KeyKind.LETTERS)
        }

        @Test
        fun `couche de symboles du mot de passe - les symboles restants du §7-1`() {
            val layout = SpecialLayouts.passwordSymbols()
            val symbols = layout.rows.flatMap { it.keys }.filter { it.kind == KeyKind.CHARACTER }.map { it.label }

            assertThat(layout.role).isEqualTo(LayoutRole.PASSWORD_SYMBOLS)
            assertThat(symbols).contains("[", "]", "{", "}", "<", ">", "^", "~", "|", "\\", "/", "+", "=", "'", "\"", ";", ":", "€", "£")
            assertThat(layout.rows.last().keys.first().kind).isEqualTo(KeyKind.LETTERS)
            assertThat(layout.rows.last().keys.last().kind).isEqualTo(KeyKind.BACKSPACE)
        }

        @Test
        fun `pavé numérique complet`() {
            val layout = SpecialLayouts.number(signed = true, decimal = true)

            assertThat(layout.role).isEqualTo(LayoutRole.NUMBER)
            assertThat(layout.grid()).containsExactly(
                listOf("1", "2", "3", "−"),
                listOf("4", "5", "6", ","),
                listOf("7", "8", "9", "⌫"),
                listOf("_", "0", "_", "↵"),
            )
            assertThat(layout.rows[1].keys.last().kind).isEqualTo(KeyKind.DECIMAL)
        }

        @Test
        fun `le séparateur affiché est celui de la locale`() {
            val layout = SpecialLayouts.number(signed = false, decimal = true, decimalLabel = '.')

            assertThat(layout.rows[1].keys.last().label).isEqualTo(".")
        }

        @Test
        fun `pavé téléphone`() {
            val layout = SpecialLayouts.phone()

            assertThat(layout.role).isEqualTo(LayoutRole.PHONE)
            assertThat(layout.grid()).containsExactly(
                listOf("1", "2", "3", "+", "⌫"),
                listOf("4", "5", "6", ",", "_"),
                listOf("7", "8", "9", ";", "_"),
                listOf("*", "0", "#", "_", "↵"),
            )
        }

        @Test
        fun `pavé date`() {
            val layout = SpecialLayouts.dateTime(DateTimeVariant.DATE)

            assertThat(layout.role).isEqualTo(LayoutRole.DATE_TIME)
            assertThat(layout.grid()).containsExactly(
                listOf("1", "2", "3", "/"),
                listOf("4", "5", "6", "-"),
                listOf("7", "8", "9", "⌫"),
                listOf("_", "0", "_", "↵"),
            )
        }

        @Test
        fun `pavé heure`() {
            assertThat(SpecialLayouts.dateTime(DateTimeVariant.TIME).grid()).containsExactly(
                listOf("1", "2", "3", "_"),
                listOf("4", "5", "6", "_"),
                listOf("7", "8", "9", "⌫"),
                listOf("_", "0", ":", "↵"),
            )
        }

        @Test
        fun `pavé date et heure a les trois séparateurs`() {
            assertThat(SpecialLayouts.dateTime(DateTimeVariant.DATE_TIME).grid()).containsExactly(
                listOf("1", "2", "3", "/"),
                listOf("4", "5", "6", "-"),
                listOf("7", "8", "9", "⌫"),
                listOf("_", "0", ":", "↵"),
            )
        }

        @Test
        fun `pavé PIN - pavé numérique sans signe ni séparateur`() {
            val layout = SpecialLayouts.pin()

            assertThat(layout.role).isEqualTo(LayoutRole.PIN)
            assertThat(layout.grid()).containsExactly(
                listOf("1", "2", "3", "_"),
                listOf("4", "5", "6", "_"),
                listOf("7", "8", "9", "⌫"),
                listOf("_", "0", "_", "↵"),
            )
            assertThat(layout.rows.flatMap { it.keys }.none { it.kind == KeyKind.DECIMAL }).isTrue()
        }

        @Test
        fun `chaque rangée de chaque disposition fait 10 u`() {
            for (layout in everyLayout()) for (row in layout.rows) {
                assertThat(row.restUnits).`as`("$layout").isCloseTo(10f, Offset.offset(1e-3f))
            }
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `les emplacements vides ne produisent aucun texte`() {
            val empties = everyLayout().flatMap { it.rows.flatMap(KeyRow::keys) }.filter { it.kind == KeyKind.EMPTY }

            assertThat(empties).isNotEmpty()
            assertThat(empties.map { it.text }.distinct()).containsExactly(null)
            assertThat(empties.map { it.label }.distinct()).containsExactly("")
        }

        @Test
        fun `un pavé numérique sans signe ni séparateur ne propose ni l'un ni l'autre`() {
            val keys = SpecialLayouts.number(signed = false, decimal = false).rows.flatMap { it.keys }

            assertThat(keys.none { it.label == "−" || it.kind == KeyKind.DECIMAL }).isTrue()
        }

        @Test
        fun `un pavé n'a aucune touche lettre ni bascule vers les lettres`() {
            val pads = listOf(SpecialLayouts.number(true, true), SpecialLayouts.pin(), SpecialLayouts.phone()) +
                DateTimeVariant.entries.map { SpecialLayouts.dateTime(it) }

            for (pad in pads) {
                val kinds = pad.rows.flatMap { it.keys }.map { it.kind }.toSet()
                assertThat(kinds).doesNotContain(KeyKind.LETTER, KeyKind.SYMBOLS, KeyKind.LETTERS, KeyKind.SHIFT, KeyKind.SPACE)
            }
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `les positions fixes - les chiffres ne bougent pas quelle que soit la configuration`() {
            val reference = SpecialLayouts.number(signed = true, decimal = true)
            val variants = listOf(
                SpecialLayouts.number(false, false), SpecialLayouts.number(true, false),
                SpecialLayouts.number(false, true), SpecialLayouts.pin(),
            )

            for (variant in variants) {
                assertThat(variant.rows.map { it.keys.size }).isEqualTo(reference.rows.map { it.keys.size })
                assertThat(variant.restWidths()).isEqualTo(reference.restWidths())
                for ((r, row) in variant.rows.withIndex()) for ((k, key) in row.keys.withIndex()) {
                    if (key.kind == KeyKind.CHARACTER && key.label[0].isDigit()) {
                        assertThat(reference.rows[r].keys[k]).isEqualTo(key)
                    }
                }
            }
        }

        @Test
        fun `les emplacements vides prennent la place des touches absentes`() {
            val full = SpecialLayouts.number(true, true).rows
            val bare = SpecialLayouts.number(false, false).rows

            assertThat(full[0].keys[3].kind).isEqualTo(KeyKind.CHARACTER)
            assertThat(bare[0].keys[3].kind).isEqualTo(KeyKind.EMPTY)
            assertThat(full[1].keys[3].kind).isEqualTo(KeyKind.DECIMAL)
            assertThat(bare[1].keys[3].kind).isEqualTo(KeyKind.EMPTY)
            assertThat(bare[0].keys[3].restWidth).isEqualTo(full[0].keys[3].restWidth)
        }

        @Test
        fun `les pavés date et heure ont les mêmes positions pour les chiffres, Retour et Entrée que le pavé numérique`() {
            val number = SpecialLayouts.number(true, true)

            for (variant in DateTimeVariant.entries) {
                val pad = SpecialLayouts.dateTime(variant)
                assertThat(pad.rows[2].keys.last()).isEqualTo(number.rows[2].keys.last())
                assertThat(pad.rows[3].keys.last()).isEqualTo(number.rows[3].keys.last())
                assertThat(pad.rows[3].keys[1]).isEqualTo(number.rows[3].keys[1])
            }
        }

        @Test
        fun `la variante date et heure expose les propriétés hasDate et hasTime`() {
            assertThat(DateTimeVariant.DATE.hasDate).isTrue()
            assertThat(DateTimeVariant.DATE.hasTime).isFalse()
            assertThat(DateTimeVariant.TIME.hasDate).isFalse()
            assertThat(DateTimeVariant.TIME.hasTime).isTrue()
            assertThat(DateTimeVariant.DATE_TIME.hasDate).isTrue()
            assertThat(DateTimeVariant.DATE_TIME.hasTime).isTrue()
        }

        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `à 360 dp, aucune touche réelle ne descend sous 32 dp (§13)`(kind: LayoutKind) {
            val layouts = listOf(
                SpecialLayouts.password(kind), SpecialLayouts.passwordSymbols(kind), SpecialLayouts.textSymbols(kind),
                SpecialLayouts.number(true, true), SpecialLayouts.pin(), SpecialLayouts.phone(),
            ) + DateTimeVariant.entries.map { SpecialLayouts.dateTime(it) }

            for (layout in layouts) {
                val geometry = KeyboardGeometry.compute(layout, 360f, 38f)
                for (rect in geometry.rows.flatten()) {
                    if (rect.key.kind != KeyKind.EMPTY) {
                        assertThat(rect.width).`as`("${layout.role} ${rect.key.label}").isGreaterThanOrEqualTo(32f - 0.01f)
                    }
                }
            }
        }

        @Test
        fun `la hauteur des pavés vise 44 dp par touche`() {
            assertThat(KeyboardMetrics.PAD_ROW_HEIGHT_DP).isEqualTo(44f)
        }

        @Test
        fun `géométrie du pavé numérique à 360 dp - quatre colonnes de 90 dp, keyAt retrouve chaque touche`() {
            val layout = SpecialLayouts.number(signed = true, decimal = true)
            val geometry = KeyboardGeometry.compute(layout, 360f, 44f, 4f)

            for ((r, row) in geometry.rows.withIndex()) {
                assertThat(row.map { it.width }).allSatisfy { assertThat(it).isCloseTo(90f, Offset.offset(0.01f)) }
                for (rect in row) {
                    assertThat(geometry.keyAt(rect.centerX, rect.centerY)).isSameAs(rect)
                }
                assertThat(row.first().rowIndex).isEqualTo(r)
            }
            assertThat(geometry.keyAt(135f, 10f)!!.key.label).isEqualTo("2")
            assertThat(geometry.keyAt(45f, 3 * 48f + 10f)!!.key.kind).isEqualTo(KeyKind.EMPTY)
            assertThat(geometry.keyAt(135f, 3 * 48f + 10f)!!.key.label).isEqualTo("0")
            assertThat(geometry.keyAt(359f, 3 * 48f + 10f)!!.key.kind).isEqualTo(KeyKind.ENTER)
            assertThat(geometry.keyAt(360.5f, 10f)).isNull()
        }

        @Test
        fun `géométrie du mot de passe - la rangée de lettres QWERTY décalée touche le bord droit`() {
            val geometry = KeyboardGeometry.compute(SpecialLayouts.password(LayoutKind.QWERTY), 360f, 38f, 4f)
            val row = geometry.rows[3]

            assertThat(row.first().left).isCloseTo(18f, Offset.offset(0.01f))
            assertThat(row.last().right).isEqualTo(360f)
            assertThat(geometry.rows).hasSize(6)
        }

        @Test
        fun `chaque touche du clavier est atteignable par keyAt à son centre`() {
            for (layout in everyLayout()) {
                val geometry = KeyboardGeometry.compute(layout, 360f, 38f, 4f)
                for (rect in geometry.rows.flatten()) {
                    assertThat(geometry.keyAt(rect.centerX, rect.centerY)).`as`("${layout.role} ${rect.key.label}").isSameAs(rect)
                }
            }
        }
    }
}
