package nextglyph.ime.view

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.math.pow

class KeyStyleTest {

    /** Couleurs des ressources : `values` (clair) ou `values-night` (sombre), lues dans les sources du module. */
    private fun colors(folder: String): Map<String, Int> {
        val xml = File("src/main/res/$folder/colors.xml").readText()
        return Regex("""<color name="(\w+)">#([0-9A-Fa-f]{6})</color>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }
    }

    private fun channel(color: Int, shift: Int) = (color shr shift and 0xFF).toDouble()

    /** Mélange [foreground] d'opacité [alpha] sur [background], canal par canal (composition du Canvas). */
    private fun blend(foreground: Int, alpha: Float, background: Int): Int =
        listOf(16, 8, 0).fold(0) { acc, shift ->
            val mixed = alpha * channel(foreground, shift) + (1 - alpha) * channel(background, shift)
            acc shl 8 or Math.round(mixed).toInt()
        }

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

    /** Contraste du libellé d'une touche d'opacité [opacity] sur le fond de cette touche. */
    private fun labelContrast(folder: String, opacity: Float): Double {
        val c = colors(folder)
        val key = blend(c.getValue("key_background"), opacity, c.getValue("keyboard_background"))
        val label = blend(c.getValue("key_label"), KeyStyle.labelOpacity(opacity), key)
        return contrast(label, key)
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `le libellé suit l'opacité de la touche au-dessus du plancher`() {
            assertThat(KeyStyle.labelOpacity(0.8f)).isEqualTo(0.8f)
            assertThat(KeyStyle.labelOpacity(1f)).isEqualTo(1f)
        }

        @Test
        fun `une touche au repos respecte 4,5 pour 1 sur les deux thèmes`() {
            for (folder in listOf("values", "values-night")) assertThat(labelContrast(folder, 1f)).isGreaterThanOrEqualTo(4.5)
        }

        @ParameterizedTest(name = "fond {0}")
        @ValueSource(strings = ["key_pressed_background", "key_function_background", "key_background"])
        fun `touches enfoncées, de fonction, ⇧ active et suggestion surlignée respectent 4,5 pour 1`(background: String) {
            for (folder in listOf("values", "values-night")) {
                val c = colors(folder)
                assertThat(contrast(c.getValue("key_label"), c.getValue(background))).`as`("$folder, $background").isGreaterThanOrEqualTo(4.5)
            }
        }

        @Test
        fun `le texte tapé proposé dans la barre respecte 4,5 pour 1`() {
            for (folder in listOf("values", "values-night")) {
                val c = colors(folder)
                val text = blend(c.getValue("key_label"), KeyStyle.TYPED_ALPHA / 255f, c.getValue("keyboard_background"))
                assertThat(contrast(text, c.getValue("keyboard_background"))).`as`(folder).isGreaterThanOrEqualTo(4.5)
            }
        }

        @Test
        fun `l'indicateur de langue garde 3 pour 1 sur l'espace`() {
            for (folder in listOf("values", "values-night")) {
                val c = colors(folder)
                val text = blend(c.getValue("key_label"), KeyStyle.LANGUAGE_ALPHA / 255f, c.getValue("key_background"))
                assertThat(contrast(text, c.getValue("key_background"))).`as`(folder).isGreaterThanOrEqualTo(3.0)
            }
        }

        @ParameterizedTest(name = "sur {0}")
        @ValueSource(strings = ["key_background", "keyboard_background", "key_function_background", "key_pressed_background"])
        fun `le contour du focus TalkBack garde 3 pour 1`(background: String) {
            for (folder in listOf("values", "values-night")) {
                val c = colors(folder)
                assertThat(contrast(c.getValue("key_focus_outline"), c.getValue(background))).`as`("$folder, $background").isGreaterThanOrEqualTo(3.0)
            }
        }

        @ParameterizedTest(name = "opacité {0}")
        @ValueSource(floats = [0.30f, 0.44f, 0.6f, 0.8f])
        fun `une touche atténuée garde 3 pour 1 sur les deux thèmes (§13)`(opacity: Float) {
            for (folder in listOf("values", "values-night")) {
                assertThat(labelContrast(folder, opacity)).`as`("$folder à $opacity").isGreaterThanOrEqualTo(3.0)
            }
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `sans plancher, le libellé d'une touche très atténuée serait illisible`() {
            // Garde-fou du test lui-même : à l'opacité de la touche, le contraste clair tombe sous 3:1.
            val c = colors("values")
            val key = blend(c.getValue("key_background"), 0.3f, c.getValue("keyboard_background"))
            val label = blend(c.getValue("key_label"), 0.3f, key)

            assertThat(contrast(label, key)).isLessThan(3.0)
        }

        @Test
        fun `les ressources de couleur attendues existent`() {
            for (folder in listOf("values", "values-night")) {
                assertThat(colors(folder).keys).contains("keyboard_background", "key_background", "key_label")
            }
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `le plancher du libellé est de 0,55`() {
            assertThat(KeyStyle.MIN_LABEL_OPACITY).isEqualTo(0.55f)
        }

        @Test
        fun `à l'opacité minimale d'une lettre, le libellé est au plancher`() {
            assertThat(KeyStyle.labelOpacity(0.30f)).isEqualTo(KeyStyle.MIN_LABEL_OPACITY)
            assertThat(KeyStyle.labelOpacity(0f)).isEqualTo(KeyStyle.MIN_LABEL_OPACITY)
        }

        @Test
        fun `à l'opacité exactement égale au plancher`() {
            assertThat(KeyStyle.labelOpacity(KeyStyle.MIN_LABEL_OPACITY)).isEqualTo(0.55f)
        }
    }
}
