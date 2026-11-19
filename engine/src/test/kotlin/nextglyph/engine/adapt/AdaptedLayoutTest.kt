package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardLayouts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AdaptedLayoutTest {

    private val azerty = KeyboardLayouts.AZERTY

    private fun adapted(): AdaptedLayout {
        val weights = KeyWeights(azerty)
        weights.setLetterWeight('e', 1f)
        weights.setLetterWeight('a', 0f)
        val out = AdaptedLayout(azerty)
        LayoutSolver(azerty, AdaptationConfig(mode = AdaptationMode.ELASTIC)).solve(weights, out)
        return out
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `une nouvelle disposition résolue est au repos`() {
            val layout = AdaptedLayout(azerty)

            assertThat(layout.isRest).isTrue()
            assertThat(layout.widthsByRow()).isEqualTo(azerty.restWidths())
            assertThat(layout.opacity(2, 9)).isEqualTo(1f)
            assertThat(layout.labelScale(2, 9)).isEqualTo(1f)
            assertThat(layout.width(2, 7)).isEqualTo(1.9f)
        }

        @Test
        fun `copie, égalité de contenu et remise au repos`() {
            val source = adapted()
            val copy = AdaptedLayout(azerty)

            copy.copyFrom(source)

            assertThat(copy.contentEquals(source)).isTrue()
            assertThat(copy.isRest).isFalse()
            copy.setRest()
            assertThat(copy.isRest).isTrue()
            assertThat(copy.contentEquals(source)).isFalse()
        }

        @Test
        fun `la représentation textuelle sépare les rangées`() {
            val text = AdaptedLayout(azerty).toString()

            assertThat(text).startsWith("AdaptedLayout(a:1.0/1.0/1.0, z:").contains(" | q:", "_:1.9")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette la copie d'une autre disposition`() {
            assertThatThrownBy { AdaptedLayout(azerty).copyFrom(AdaptedLayout(KeyboardLayouts.QWERTY)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `deux dispositions différentes ne sont jamais égales`() {
            assertThat(AdaptedLayout(azerty).contentEquals(AdaptedLayout(KeyboardLayouts.QWERTY))).isFalse()
            val twin = nextglyph.engine.layout.KeyboardLayout(azerty.kind, azerty.rows)
            assertThat(AdaptedLayout(azerty).contentEquals(AdaptedLayout(twin))).isFalse()
        }

        @Test
        fun `rejette une touche inexistante`() {
            assertThatThrownBy { AdaptedLayout(azerty).width(0, 10) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
            assertThatThrownBy { AdaptedLayout(azerty).opacity(3, 0) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
            assertThatThrownBy { AdaptedLayout(azerty).labelScale(-1, 0) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `isRest détecte un seul écart d'opacité, de libellé ou de largeur`() {
            val reference = adapted()
            for (field in 0..2) {
                val layout = AdaptedLayout(azerty)
                when (field) {
                    0 -> layout.opacities[29] = 0.5f
                    1 -> layout.labelScales[29] = 0.5f
                    else -> layout.widths[29] = 0.9f
                }

                assertThat(layout.isRest).isFalse()
                assertThat(layout.contentEquals(AdaptedLayout(azerty))).isFalse()
                assertThat(layout.contentEquals(reference)).isFalse()
            }
        }

        @Test
        fun `QWERTY, le retrait n'est pas une touche`() {
            val widths = AdaptedLayout(KeyboardLayouts.QWERTY).widthsByRow()

            assertThat(widths.map { it.size }).containsExactly(10, 10, 10)
            assertThat(widths[1].last()).isEqualTo(1.15f)
        }
    }
}
