package nextglyph.engine.layout

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class KeyboardGeometryTest {

    private val eps = Offset.offset(1e-3f)

    private fun azerty(width: Float = 1000f, rowHeight: Float = 100f, gap: Float = 10f) =
        KeyboardGeometry.compute(KeyboardLayouts.AZERTY, width, rowHeight, gap)

    private fun qwerty(width: Float = 1000f, rowHeight: Float = 100f, gap: Float = 10f) =
        KeyboardGeometry.compute(KeyboardLayouts.QWERTY, width, rowHeight, gap)

    @Nested
    inner class HappyPath {
        @Test
        fun `AZERTY rangée 1 une lettre fait un dixième de la largeur`() {
            val geometry = azerty()

            val first = geometry.rows[0][0]
            val last = geometry.rows[0][9]

            assertThat(first.key.label).isEqualTo("a")
            assertThat(first.left).isEqualTo(0f)
            assertThat(first.right).isCloseTo(100f, eps)
            assertThat(last.left).isCloseTo(900f, eps)
            assertThat(last.right).isEqualTo(1000f)
        }

        @Test
        fun `les rangées sont empilées avec l'espacement`() {
            val geometry = azerty()

            assertThat(geometry.rows.map { it[0].top }).containsExactly(0f, 110f, 220f)
            assertThat(geometry.rows.map { it[0].bottom }).containsExactly(100f, 210f, 320f)
            assertThat(geometry.height).isEqualTo(320f)
            assertThat(geometry.width).isEqualTo(1000f)
            assertThat(geometry.rowHeight).isEqualTo(100f)
            assertThat(geometry.rowGap).isEqualTo(10f)
            assertThat(geometry.layout).isSameAs(KeyboardLayouts.AZERTY)
        }

        @Test
        fun `QWERTY rangée 2 commence au retrait de 0,3 u`() {
            val geometry = qwerty()

            val row = geometry.rows[1]

            assertThat(row.first().left).isCloseTo(30f, eps)
            assertThat(row.first().width).isCloseTo(95f, eps)
            assertThat(row.last().key.kind).isEqualTo(KeyKind.ENTER)
            assertThat(row.last().width).isCloseTo(115f, eps)
            assertThat(row.last().right).isEqualTo(1000f)
        }

        @Test
        fun `les index et le centre sont renseignés`() {
            val rect = azerty().rows[1][3]

            assertThat(rect.rowIndex).isEqualTo(1)
            assertThat(rect.keyIndex).isEqualTo(3)
            assertThat(rect.key.label).isEqualTo("f")
            assertThat(rect.centerX).isCloseTo(350f, eps)
            assertThat(rect.centerY).isCloseTo(160f, eps)
            assertThat(rect.height).isEqualTo(100f)
            assertThat(rect.toString()).contains("row=1", "key=3", "f")
        }

        @Test
        fun `keyAt trouve la touche sous le doigt`() {
            val geometry = azerty()

            assertThat(geometry.keyAt(50f, 50f)!!.key.label).isEqualTo("a")
            assertThat(geometry.keyAt(150f, 160f)!!.key.label).isEqualTo("s")
            assertThat(geometry.keyAt(980f, 270f)!!.key.kind).isEqualTo(KeyKind.BACKSPACE)
        }

        @Test
        fun `un solveur peut fournir d'autres largeurs de même somme`() {
            val widths = KeyboardLayouts.AZERTY.restWidths().toMutableList()
            widths[0] = listOf(1.5f, 0.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)

            val geometry = KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 1000f, 100f, 0f, widths)

            assertThat(geometry.rows[0][0].right).isCloseTo(150f, eps)
            assertThat(geometry.rows[0][1].width).isCloseTo(50f, eps)
            assertThat(geometry.rows[0][2].left).isCloseTo(200f, eps)
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @ValueSource(floats = [0f, -1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette une largeur invalide`(width: Float) {
            assertThatThrownBy { KeyboardGeometry.compute(KeyboardLayouts.AZERTY, width, 100f) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("largeur")
        }

        @ParameterizedTest
        @ValueSource(floats = [0f, -1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette une hauteur de rangée invalide`(height: Float) {
            assertThatThrownBy { KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 100f, height) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("hauteur")
        }

        @ParameterizedTest
        @ValueSource(floats = [-1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette un espacement invalide`(gap: Float) {
            assertThatThrownBy { KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 100f, 100f, gap) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("espacement")
        }

        @Test
        fun `rejette un nombre de rangées de largeurs incorrect`() {
            assertThatThrownBy { KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 100f, 100f, 0f, listOf(listOf(10f))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("1 rangées")
        }

        @Test
        fun `rejette un nombre de largeurs incorrect dans une rangée`() {
            val widths = KeyboardLayouts.AZERTY.restWidths().toMutableList()
            widths[1] = listOf(10f)

            assertThatThrownBy { KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 100f, 100f, 0f, widths) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("rangée 1")
        }

        @ParameterizedTest
        @ValueSource(floats = [0f, -1f, Float.NaN])
        fun `rejette une largeur de touche non positive`(bad: Float) {
            val widths = KeyboardLayouts.AZERTY.restWidths().toMutableList()
            widths[0] = listOf(bad) + List(9) { 1f }

            assertThatThrownBy { KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 100f, 100f, 0f, widths) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("non positive")
        }

        @Test
        fun `rejette une rangée dont la somme dévie de 10 u`() {
            val widths = KeyboardLayouts.AZERTY.restWidths().toMutableList()
            widths[2] = widths[2].mapIndexed { i, w -> if (i == 0) w + 0.1f else w }

            assertThatThrownBy { KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 100f, 100f, 0f, widths) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("rangée 2")
        }

        @Test
        fun `keyAt renvoie null hors du clavier`() {
            val geometry = azerty()

            assertThat(geometry.keyAt(-0.1f, 50f)).isNull()
            assertThat(geometry.keyAt(1000.1f, 50f)).isNull()
            assertThat(geometry.keyAt(50f, -0.1f)).isNull()
            assertThat(geometry.keyAt(50f, 320.1f)).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `les touches d'une rangée se jointent et couvrent toute la largeur`(kind: LayoutKind) {
            val geometry = KeyboardGeometry.compute(KeyboardLayouts.of(kind), 1080f, 120f, 8f)

            for (row in geometry.rows) {
                val inset = geometry.layout.rows[row[0].rowIndex].leadingInset
                assertThat(row.first().left).isCloseTo(inset * 108f, eps)
                row.zipWithNext().forEach { (a, b) -> assertThat(a.right).isEqualTo(b.left) }
                assertThat(row.last().right).isEqualTo(1080f)
            }
        }

        @Test
        fun `la hauteur totale sans espacement est trois rangées`() {
            assertThat(azerty(gap = 0f).height).isEqualTo(300f)
        }

        @Test
        fun `keyAt accepte les quatre bords`() {
            val geometry = azerty()

            assertThat(geometry.keyAt(0f, 0f)!!.key.label).isEqualTo("a")
            assertThat(geometry.keyAt(1000f, 0f)!!.key.label).isEqualTo("p")
            assertThat(geometry.keyAt(0f, 320f)!!.key.kind).isEqualTo(KeyKind.SYMBOLS)
            assertThat(geometry.keyAt(1000f, 320f)!!.key.kind).isEqualTo(KeyKind.BACKSPACE)
        }

        @Test
        fun `l'espacement entre rangées est rattaché à la rangée du dessus, aucun trou`() {
            val geometry = azerty()

            assertThat(geometry.keyAt(50f, 99.9f)!!.rowIndex).isEqualTo(0)
            assertThat(geometry.keyAt(50f, 105f)!!.rowIndex).isEqualTo(0)
            assertThat(geometry.keyAt(50f, 110f)!!.rowIndex).isEqualTo(1)
            assertThat(geometry.keyAt(50f, 215f)!!.rowIndex).isEqualTo(1)
            assertThat(geometry.keyAt(50f, 220f)!!.rowIndex).isEqualTo(2)
        }

        @Test
        fun `le retrait de début de rangée QWERTY est rattaché à la première touche`() {
            val geometry = qwerty()

            assertThat(geometry.keyAt(10f, 160f)!!.key.label).isEqualTo("a")
        }

        @Test
        fun `la frontière entre deux touches appartient à la touche de droite`() {
            val geometry = azerty()

            assertThat(geometry.keyAt(99.9f, 50f)!!.key.label).isEqualTo("a")
            assertThat(geometry.keyAt(100.1f, 50f)!!.key.label).isEqualTo("z")
        }

        @Test
        fun `une largeur très petite reste cohérente`() {
            val geometry = KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 10f, 1f)

            assertThat(geometry.rows[0][0].right).isCloseTo(1f, eps)
            assertThat(geometry.height).isEqualTo(3f)
        }
    }
}
