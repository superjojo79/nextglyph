package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardLayout

/**
 * Disposition résolue : pour chaque touche, sa largeur (en unités), son opacité et l'échelle de
 * son libellé (spec §5.2). La topologie (ordre des touches, rangées) est celle de [layout] ;
 * chaque rangée garde sa largeur totale, si bien que [widthsByRow] se passe tel quel à
 * `KeyboardGeometry.compute(…, widths)`.
 *
 * Tampon **mutable et réutilisable**, rempli en place par [LayoutSolver.solve],
 * [LayoutAnimation.interpolate] et [AdaptiveLayoutState] ; une nouvelle instance est au repos.
 */
class AdaptedLayout(val layout: KeyboardLayout) {

    internal val keys = LayoutKeys(layout)
    internal val widths = FloatArray(keys.size)
    internal val opacities = FloatArray(keys.size)
    internal val labelScales = FloatArray(keys.size)

    init {
        setRest()
    }

    /** Largeur en unités de la touche [key] de la rangée [row]. */
    fun width(row: Int, key: Int): Float = widths[keys.index(row, key)]

    /** Opacité de la touche, dans ]0, 1] (1 = repos). */
    fun opacity(row: Int, key: Int): Float = opacities[keys.index(row, key)]

    /** Échelle du libellé de la touche, dans ]0, 1] (1 = repos). */
    fun labelScale(row: Int, key: Int): Float = labelScales[keys.index(row, key)]

    /**
     * Largeurs par rangée puis par touche, pour `KeyboardGeometry.compute`. Alloue des listes :
     * à appeler quand la disposition change, pas à chaque dessin.
     */
    fun widthsByRow(): List<List<Float>> = layout.rows.indices.map { r ->
        (keys.rowStart[r] until keys.rowStart[r + 1]).map { widths[it] }
    }

    /** Remet toutes les touches au repos. */
    fun setRest() {
        for (i in 0 until keys.size) widths[i] = keys.keys[i].restWidth
        opacities.fill(1f)
        labelScales.fill(1f)
    }

    /** Vrai si toutes les touches sont exactement au repos. */
    val isRest: Boolean
        get() {
            for (i in 0 until keys.size) {
                if (widths[i] != keys.keys[i].restWidth || opacities[i] != 1f || labelScales[i] != 1f) return false
            }
            return true
        }

    /** Copie [other], de la même disposition. */
    fun copyFrom(other: AdaptedLayout) {
        requireSameLayout(other)
        other.widths.copyInto(widths)
        other.opacities.copyInto(opacities)
        other.labelScales.copyInto(labelScales)
    }

    /** Vrai si [other] a exactement les mêmes largeurs, opacités et libellés. */
    fun contentEquals(other: AdaptedLayout): Boolean =
        other.layout === layout &&
            other.widths.contentEquals(widths) &&
            other.opacities.contentEquals(opacities) &&
            other.labelScales.contentEquals(labelScales)

    internal fun requireSameLayout(other: AdaptedLayout) {
        require(other.layout === layout) { "Les deux dispositions résolues doivent porter sur la même disposition." }
    }

    override fun toString(): String = buildString {
        append("AdaptedLayout(")
        for (i in 0 until keys.size) {
            if (i > 0) append(if (keys.rowOf[i] != keys.rowOf[i - 1]) " | " else ", ")
            append(keys.keys[i].label.ifEmpty { "_" }).append(':').append(widths[i])
            append('/').append(opacities[i]).append('/').append(labelScales[i])
        }
        append(')')
    }
}
