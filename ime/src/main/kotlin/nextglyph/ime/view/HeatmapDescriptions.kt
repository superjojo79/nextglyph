package nextglyph.ime.view

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.stats.KeyHeatmap
import nextglyph.ime.R

/**
 * Textes lus par TalkBack pour chaque touche de la carte de frappe (issue #46) : « e : 312 frappes, décalage
 * léger vers la gauche », « e : 4 % d'erreurs sur 250 frappes »…
 *
 * @param text lit une chaîne de ressource avec ses arguments (`Context.getString(id, *args)`).
 * @param plural lit un pluriel selon [quantity] (`Resources.getQuantityString`).
 * @param percent met en forme une part (0 à 1) en pourcentage, selon la langue.
 */
class HeatmapDescriptions(
    private val text: (id: Int, args: List<Any>) -> String,
    private val plural: (id: Int, quantity: Int, args: List<Any>) -> String,
    private val percent: (Double) -> String,
) {
    /** Nom lu de la touche : la lettre, ou le nom de la touche de fonction. */
    fun keyName(heat: KeyHeatmap.KeyHeat): String = heat.letter?.toString() ?: when (heat.kind) {
        KeyKind.SPACE -> text(R.string.a11y_space, emptyList())
        KeyKind.ENTER -> text(R.string.a11y_enter, emptyList())
        KeyKind.BACKSPACE -> text(R.string.a11y_backspace, emptyList())
        else -> text(R.string.a11y_symbols, emptyList())
    }

    /**
     * Description de la touche [heat] dans la vue [mode]. [offsetX], [offsetY] : décalage appris (fractions de
     * touche) et [learned] : nombre de frappes qui l'ont produit.
     */
    fun describe(heat: KeyHeatmap.KeyHeat, mode: HeatmapMode, offsetX: Float, offsetY: Float, learned: Int): String {
        val name = keyName(heat)
        return when (mode) {
            HeatmapMode.DENSITY -> plural(R.plurals.heatmap_a11y_density, heat.taps, listOf(name, heat.taps))
            HeatmapMode.OFFSETS -> text(R.string.heatmap_a11y_offset, listOf(name, offset(offsetX, offsetY, learned)))
            HeatmapMode.ERRORS -> {
                val rate = heat.errorRate
                when {
                    heat.kind == KeyKind.BACKSPACE -> text(R.string.heatmap_a11y_errors_none, listOf(name))
                    rate == null -> text(R.string.heatmap_a11y_errors_unknown, listOf(name))
                    else -> plural(R.plurals.heatmap_a11y_errors, heat.taps, listOf(name, percent(rate), heat.taps))
                }
            }
        }
    }

    /** Décalage en mots : « décalage léger vers la gauche », « aucun décalage appris » sans frappe apprise. */
    fun offset(offsetX: Float, offsetY: Float, learned: Int): String {
        if (learned <= 0) return text(R.string.heatmap_offset_unknown, emptyList())
        val summary = HeatmapStyle.summarize(offsetX, offsetY)
        if (summary.strength == Strength.NONE) return text(R.string.heatmap_offset_centered, emptyList())
        val strength = text(if (summary.strength == Strength.CLEAR) R.string.heatmap_strength_clear else R.string.heatmap_strength_slight, emptyList())
        val sides = listOfNotNull(summary.horizontal, summary.vertical).map { text(sideString(it), emptyList()) }
        val direction = if (sides.size == 2) text(R.string.heatmap_sides_pair, sides) else sides[0]
        return text(R.string.heatmap_offset_phrase, listOf(strength, direction))
    }

    private fun sideString(side: Side): Int = when (side) {
        Side.LEFT -> R.string.heatmap_side_left
        Side.RIGHT -> R.string.heatmap_side_right
        Side.TOP -> R.string.heatmap_side_top
        Side.BOTTOM -> R.string.heatmap_side_bottom
    }
}
