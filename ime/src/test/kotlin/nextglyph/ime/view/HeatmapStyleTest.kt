package nextglyph.ime.view

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.math.pow

/** Calculs purs de la carte de frappe (issue #46) et lisibilité de ses couleurs sur les deux thèmes. */
class HeatmapStyleTest {

    private val themes = listOf("values", "values-night")

    private fun colors(folder: String): Map<String, Int> {
        val xml = File("src/main/res/$folder/colors.xml").readText()
        return Regex("""<color name="(\w+)">#([0-9A-Fa-f]{6})</color>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }
    }

    private fun channel(color: Int, shift: Int) = (color shr shift and 0xFF).toDouble()

    private fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = channel(color, shift) / 255
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `l'opacité d'une case croît avec ses frappes jusqu'au maximum`() {
            assertThat(HeatmapStyle.cellAlpha(10, 10)).isEqualTo(HeatmapStyle.MAX_ALPHA)
            assertThat(HeatmapStyle.cellAlpha(5, 10)).isEqualTo(110)
            assertThat(HeatmapStyle.cellAlpha(1, 10)).isEqualTo(46)
        }

        @Test
        fun `le rouge croît avec le taux d'erreur jusqu'à 25 pour cent`() {
            assertThat(HeatmapStyle.errorLevel(0.125)!!).isCloseTo(0.5f, Offset.offset(1e-6f))
            assertThat(HeatmapStyle.errorLevel(0.25)!!).isEqualTo(1f)
            assertThat(HeatmapStyle.errorLevel(0.0)!!).isEqualTo(0f)
        }

        @Test
        fun `le mélange de deux couleurs se fait canal par canal`() {
            assertThat(HeatmapStyle.blend(0x000000, 0xFFFFFF, 0.5f)).isEqualTo(0x808080)
            assertThat(HeatmapStyle.blend(0xFF0000, 0x0000FF, 0f)).isEqualTo(0xFF0000)
            assertThat(HeatmapStyle.blend(0xFF0000, 0x0000FF, 1f)).isEqualTo(0x0000FF)
            assertThat(HeatmapStyle.blend(0x102030, 0x304050, 0.5f)).isEqualTo(0x203040)
        }

        @Test
        fun `la flèche est agrandie trois fois et garde sa direction`() {
            val (dx, dy) = HeatmapStyle.arrow(0.1f, -0.05f, 40f, 50f, 4f)

            assertThat(dx).isCloseTo(12f, Offset.offset(1e-4f))
            assertThat(dy).isCloseTo(-7.5f, Offset.offset(1e-4f))
        }

        @Test
        fun `une flèche trop longue est ramenée dans la touche sans changer de direction`() {
            val (dx, dy) = HeatmapStyle.arrow(0.5f, 0.25f, 40f, 50f, 4f)

            assertThat(dx).isCloseTo(16f, Offset.offset(1e-4f))
            assertThat(dy).isCloseTo(10f, Offset.offset(1e-4f))
        }

        @Test
        fun `un décalage se décrit par sa force et ses côtés`() {
            assertThat(HeatmapStyle.summarize(-0.05f, 0f)).isEqualTo(OffsetSummary(Strength.SLIGHT, Side.LEFT, null))
            assertThat(HeatmapStyle.summarize(0.15f, 0f)).isEqualTo(OffsetSummary(Strength.CLEAR, Side.RIGHT, null))
            assertThat(HeatmapStyle.summarize(0f, -0.05f)).isEqualTo(OffsetSummary(Strength.SLIGHT, null, Side.TOP))
            assertThat(HeatmapStyle.summarize(0.05f, 0.2f)).isEqualTo(OffsetSummary(Strength.CLEAR, Side.RIGHT, Side.BOTTOM))
        }

        @Test
        fun `les vues se mémorisent sous leur nom en minuscules`() {
            for (mode in HeatmapMode.entries) assertThat(HeatmapMode.fromStored(mode.stored)).isEqualTo(mode)
            assertThat(HeatmapMode.OFFSETS.stored).isEqualTo("offsets")
        }

        @Test
        fun `le libellé reste lisible sur la case la plus dense, sur les deux thèmes`() {
            for (folder in themes) {
                val c = colors(folder)
                val dense = HeatmapStyle.blend(c.getValue("key_background"), c.getValue("heat_density"), HeatmapStyle.MAX_ALPHA / 255f)

                assertThat(contrast(c.getValue("key_label"), dense)).describedAs(folder).isGreaterThanOrEqualTo(4.5)
            }
        }

        @Test
        fun `le libellé reste lisible sur une touche rouge, de 0 à 100 pour cent, sur les deux thèmes`() {
            for (folder in themes) {
                val c = colors(folder)
                for (level in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    val key = HeatmapStyle.blend(c.getValue("key_background"), c.getValue("heat_error"), level)

                    assertThat(contrast(c.getValue("key_label"), key)).describedAs("$folder $level").isGreaterThanOrEqualTo(4.5)
                }
            }
        }

        @Test
        fun `la flèche et le contour se distinguent du fond, sur les deux thèmes`() {
            for (folder in themes) {
                val c = colors(folder)

                assertThat(contrast(c.getValue("heat_arrow"), c.getValue("key_background"))).describedAs(folder).isGreaterThanOrEqualTo(3.0)
                assertThat(contrast(c.getValue("heat_outline"), c.getValue("keyboard_background"))).describedAs(folder).isGreaterThanOrEqualTo(3.0)
            }
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une case vide ou une échelle vide n'a aucune opacité`() {
            assertThat(HeatmapStyle.cellAlpha(0, 10)).isZero()
            assertThat(HeatmapStyle.cellAlpha(-3, 10)).isZero()
            assertThat(HeatmapStyle.cellAlpha(5, 0)).isZero()
            assertThat(HeatmapStyle.cellAlpha(5, -1)).isZero()
        }

        @Test
        fun `un taux inconnu n'a pas de niveau de rouge, un taux négatif en a zéro`() {
            assertThat(HeatmapStyle.errorLevel(null)).isNull()
            assertThat(HeatmapStyle.errorLevel(-0.5)!!).isEqualTo(0f)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "inconnue", "DENSITY", "Offsets"])
        fun `une vue absente ou inconnue donne la densité`(stored: String) {
            assertThat(HeatmapMode.fromStored(stored)).isEqualTo(HeatmapMode.DENSITY)
            assertThat(HeatmapMode.fromStored(null)).isEqualTo(HeatmapMode.DENSITY)
        }

        @Test
        fun `un décalage nul n'a ni force ni côté`() {
            assertThat(HeatmapStyle.summarize(0f, 0f)).isEqualTo(OffsetSummary(Strength.NONE, null, null))
        }

        @Test
        fun `un mélange hors de 0 à 1 est borné`() {
            assertThat(HeatmapStyle.blend(0x000000, 0xFFFFFF, -1f)).isEqualTo(0x000000)
            assertThat(HeatmapStyle.blend(0x000000, 0xFFFFFF, 2f)).isEqualTo(0xFFFFFF)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une seule frappe reste visible, au-delà du maximum l'opacité plafonne`() {
            assertThat(HeatmapStyle.cellAlpha(1, 100_000)).isEqualTo(HeatmapStyle.MIN_ALPHA)
            assertThat(HeatmapStyle.cellAlpha(20, 10)).isEqualTo(HeatmapStyle.MAX_ALPHA)
            assertThat(HeatmapStyle.cellAlpha(1, 1)).isEqualTo(HeatmapStyle.MAX_ALPHA)
        }

        @Test
        fun `le rouge plafonne à 25 pour cent et au-delà`() {
            assertThat(HeatmapStyle.errorLevel(0.2499)!!).isLessThan(1f)
            assertThat(HeatmapStyle.errorLevel(0.2501)!!).isEqualTo(1f)
            assertThat(HeatmapStyle.errorLevel(1.0)!!).isEqualTo(1f)
        }

        @Test
        fun `les seuils des décalages sont à 0,03 et 0,10 de touche`() {
            assertThat(HeatmapStyle.summarize(0.029f, 0f).strength).isEqualTo(Strength.NONE)
            assertThat(HeatmapStyle.summarize(0.03f, 0f).strength).isEqualTo(Strength.SLIGHT)
            assertThat(HeatmapStyle.summarize(0.099f, 0f).strength).isEqualTo(Strength.SLIGHT)
            assertThat(HeatmapStyle.summarize(0.1f, 0f).strength).isEqualTo(Strength.CLEAR)
            assertThat(HeatmapStyle.summarize(-0.1f, 0f)).isEqualTo(OffsetSummary(Strength.CLEAR, Side.LEFT, null))
            assertThat(HeatmapStyle.summarize(0f, 0.2f)).isEqualTo(OffsetSummary(Strength.CLEAR, null, Side.BOTTOM))
        }

        @Test
        fun `un axe sous le seuil n'est pas décrit, l'autre l'est`() {
            assertThat(HeatmapStyle.summarize(0.02f, 0.06f)).isEqualTo(OffsetSummary(Strength.SLIGHT, null, Side.BOTTOM))
        }

        @Test
        fun `une flèche nulle est invisible, une flèche à la longueur minimale est visible`() {
            assertThat(HeatmapStyle.isVisibleArrow(0f, 0f, 3f)).isFalse()
            assertThat(HeatmapStyle.isVisibleArrow(2.9f, 0f, 3f)).isFalse()
            assertThat(HeatmapStyle.isVisibleArrow(0f, -3f, 3f)).isTrue()
            assertThat(HeatmapStyle.isVisibleArrow(3f, 4f, 5f)).isTrue()
        }

        @Test
        fun `une flèche juste à la limite de la touche n'est pas raccourcie`() {
            val (dx, dy) = HeatmapStyle.arrow(16f / 3f / 40f, 0f, 40f, 50f, 4f)

            assertThat(dx).isCloseTo(16f, Offset.offset(1e-3f))
            assertThat(dy).isZero()
        }
    }
}
