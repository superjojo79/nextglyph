package nextglyph.engine.adapt

import kotlin.math.pow

/**
 * Animation entre deux dispositions résolues (spec §5.2 : 100 ms, `ease-out`).
 *
 * L'interpolation est linéaire sur les largeurs, opacités et libellés, après la courbe
 * [easeOut]. Comme les centres des touches dépendent linéairement des largeurs, toute image
 * intermédiaire entre deux dispositions valides est valide : rangées de largeur conservée,
 * plafond de déplacement et largeurs minimales respectés.
 */
object LayoutAnimation {

    /**
     * Courbe `ease-out` `1 − (1 − t)^power` ([AdaptationConfig.easeOutPower]), pour `t` borné à [0, 1].
     * @throws IllegalArgumentException si [power] est inférieur à 1 ou si [t] n'est pas un nombre.
     */
    fun easeOut(t: Float, power: Float): Float {
        require(power >= 1f && power < Float.POSITIVE_INFINITY) { "L'exposant doit valoir au moins 1 : $power." }
        require(!t.isNaN()) { "t n'est pas un nombre." }
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x).pow(power)
    }

    /**
     * Écrit dans [out] l'image de l'animation de [from] vers [to] à l'instant [t] ∈ [0, 1]
     * (borné), courbe [easeOut] comprise : `t = 0` donne exactement [from], `t = 1` exactement [to].
     * [out] peut être [from] ou [to]. Sans allocation.
     *
     * @throws IllegalArgumentException si les trois dispositions ne portent pas sur la même
     *   disposition, ou si [t] n'est pas un nombre.
     */
    fun interpolate(from: AdaptedLayout, to: AdaptedLayout, t: Float, out: AdaptedLayout, power: Float) {
        from.requireSameLayout(to)
        from.requireSameLayout(out)
        val e = easeOut(t, power)
        for (i in 0 until out.keys.size) {
            out.widths[i] = lerp(from.widths[i], to.widths[i], e)
            out.opacities[i] = lerp(from.opacities[i], to.opacities[i], e)
            out.labelScales[i] = lerp(from.labelScales[i], to.labelScales[i], e)
        }
    }

    /** Exact aux extrémités : `t = 0` donne [a], `t = 1` donne [b]. */
    private fun lerp(a: Float, b: Float, t: Float): Float = a * (1f - t) + b * t
}
