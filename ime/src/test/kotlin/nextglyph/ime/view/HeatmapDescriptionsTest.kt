package nextglyph.ime.view

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.stats.KeyHeatmap
import nextglyph.ime.R
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Textes de TalkBack de la carte de frappe (issue #46), avec des chaînes simulées « nom(arguments) ». */
class HeatmapDescriptionsTest {

    private val names = mapOf(
        R.string.a11y_space to "espace", R.string.a11y_enter to "entrée", R.string.a11y_backspace to "effacer",
        R.string.a11y_symbols to "symboles",
        R.string.heatmap_a11y_offset to "décalage", R.string.heatmap_a11y_errors_none to "sans-taux",
        R.string.heatmap_a11y_errors_unknown to "taux-inconnu", R.string.heatmap_offset_unknown to "inconnu",
        R.string.heatmap_offset_centered to "centré", R.string.heatmap_strength_slight to "léger",
        R.string.heatmap_strength_clear to "net", R.string.heatmap_side_left to "gauche", R.string.heatmap_side_right to "droite",
        R.string.heatmap_side_top to "haut", R.string.heatmap_side_bottom to "bas", R.string.heatmap_sides_pair to "et",
        R.string.heatmap_offset_phrase to "phrase",
    )
    private val plurals = mapOf(R.plurals.heatmap_a11y_density to "densité", R.plurals.heatmap_a11y_errors to "erreurs")

    private fun render(name: String, args: List<Any>) = name + if (args.isEmpty()) "" else args.joinToString(",", "(", ")")

    private val descriptions = HeatmapDescriptions(
        text = { id, args -> render(names.getValue(id), args) },
        plural = { id, quantity, args -> render(plurals.getValue(id) + "#" + quantity, args) },
        percent = { "${(it * 100).toInt()}%" },
    )

    private val settings = PredictionSettings()

    private fun heatmap(letter: Char = 'e', taps: Int = 25, errors: Int = 5): KeyHeatmap {
        val map = KeyHeatmap()
        repeat(taps) { map.onTap(letter, 0.0, 0.0, settings) }
        repeat(errors) {
            map.onTap(letter, 0.0, 0.0, settings)
            map.onErase()
        }
        return map
    }

    private fun KeyHeatmap.heat(letter: Char) = keys().first { it.letter == letter }
    private fun KeyHeatmap.heat(kind: KeyKind) = keys().first { it.kind == kind }

    @Nested
    inner class HappyPath {
        @Test
        fun `le nom d'une lettre est la lettre, celui d'une touche de fonction son libellé`() {
            val map = KeyHeatmap()

            assertThat(descriptions.keyName(map.heat('e'))).isEqualTo("e")
            assertThat(descriptions.keyName(map.heat(KeyKind.SPACE))).isEqualTo("espace")
            assertThat(descriptions.keyName(map.heat(KeyKind.ENTER))).isEqualTo("entrée")
            assertThat(descriptions.keyName(map.heat(KeyKind.BACKSPACE))).isEqualTo("effacer")
            assertThat(descriptions.keyName(map.heat(KeyKind.SYMBOLS))).isEqualTo("symboles")
        }

        @Test
        fun `densité - nom et nombre de frappes`() {
            val e = heatmap().heat('e')

            assertThat(descriptions.describe(e, HeatmapMode.DENSITY, 0f, 0f, 0)).isEqualTo("densité#30(e,30)")
        }

        @Test
        fun `erreurs - pourcentage et nombre de frappes`() {
            val e = heatmap().heat('e')

            assertThat(descriptions.describe(e, HeatmapMode.ERRORS, 0f, 0f, 0)).isEqualTo("erreurs#30(e,16%,30)")
        }

        @Test
        fun `décalages - force et direction`() {
            val e = heatmap().heat('e')

            assertThat(descriptions.describe(e, HeatmapMode.OFFSETS, -0.05f, 0f, 40)).isEqualTo("décalage(e,phrase(léger,gauche))")
            assertThat(descriptions.offset(0.2f, 0f, 3)).isEqualTo("phrase(net,droite)")
        }

        @Test
        fun `un décalage sur deux axes nomme les deux côtés`() {
            assertThat(descriptions.offset(0.05f, -0.05f, 10)).isEqualTo("phrase(léger,et(droite,haut))")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `erreurs - pas assez de frappes pour estimer`() {
            val e = heatmap(taps = 3, errors = 0).heat('e')

            assertThat(descriptions.describe(e, HeatmapMode.ERRORS, 0f, 0f, 0)).isEqualTo("taux-inconnu(e)")
        }

        @Test
        fun `erreurs - la touche d'effacement n'a pas de taux`() {
            val map = KeyHeatmap()
            repeat(40) { map.onTap(KeyKind.BACKSPACE, 0.0, 0.0, settings) }

            assertThat(descriptions.describe(map.heat(KeyKind.BACKSPACE), HeatmapMode.ERRORS, 0f, 0f, 0)).isEqualTo("sans-taux(effacer)")
        }

        @Test
        fun `décalages - rien d'appris`() {
            assertThat(descriptions.offset(0.2f, 0.2f, 0)).isEqualTo("inconnu")
            assertThat(descriptions.offset(0.2f, 0.2f, -1)).isEqualTo("inconnu")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un décalage sous le seuil est centré, à une frappe apprise`() {
            assertThat(descriptions.offset(0.029f, -0.029f, 1)).isEqualTo("centré")
        }

        @Test
        fun `densité - aucune frappe`() {
            val e = KeyHeatmap().heat('e')

            assertThat(descriptions.describe(e, HeatmapMode.DENSITY, 0f, 0f, 0)).isEqualTo("densité#0(e,0)")
        }

        @Test
        fun `erreurs - à 20 frappes le taux existe`() {
            val e = heatmap(taps = 20, errors = 0).heat('e')

            assertThat(descriptions.describe(e, HeatmapMode.ERRORS, 0f, 0f, 0)).isEqualTo("erreurs#20(e,0%,20)")
        }
    }
}
