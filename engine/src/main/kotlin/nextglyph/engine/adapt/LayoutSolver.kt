package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Solveur de disposition (spec §2, §5.2) : transforme des [KeyWeights] en [AdaptedLayout]
 * (largeur, opacité, échelle du libellé de chaque touche) selon le mode et les paramètres de
 * [config].
 *
 * **Garanties** (testées par propriétés) :
 * - topologie immuable : mêmes touches, mêmes rangées, même ordre ; toutes les largeurs > 0 ;
 * - largeur de chaque rangée conservée (retrait compris : [KeyboardLayout.ROW_UNITS]) ;
 * - le centre de chaque touche reste à au plus `plafond × largeur au repos` de son centre de repos,
 *   où le plafond est [AdaptationConfig.displacement] du mode (marge numérique de 10⁻⁵ u incluse,
 *   si bien que la garantie tient aussi pour les centres recalculés à partir des largeurs `Float`) ;
 * - largeur minimale : `minWidth × repos` pour une lettre, le repos pour l'espace ; les autres
 *   touches de fonction gardent leur largeur de repos ; hors mode Élastique, une lettre ne descend
 *   pas non plus sous [AdaptationConfig.minKeyWidthDp] (jamais au-dessus de son repos), dès que la
 *   largeur du clavier est connue ([keyboardWidthDp]) ;
 * - état neutre, mode Désactivé ou intensité nulle : exactement la disposition au repos ;
 * - déterminisme : mêmes entrées, même sortie, sans état caché.
 *
 * **Algorithme des largeurs**, rangée par rangée :
 * 1. largeurs cibles par les formules de [AdaptationConfig], lettres normalisées pour que la rangée
 *    garde sa largeur, puis mélange avec le repos selon l'intensité ;
 * 2. contraintes : chaque bord intérieur est une variable ; chaque touche borne sa largeur
 *    (minimum, maximum) et la somme de ses deux bords (plafond de déplacement de son centre).
 *    Une passe de droite à gauche calcule, pour chaque bord, l'intervalle des positions
 *    qui laissent une solution aux touches suivantes (la disposition au repos est toujours
 *    admissible, donc ces intervalles ne sont jamais vides) ;
 * 3. une passe de gauche à droite place chaque bord au plus près de sa position cible, dans
 *    l'intervalle admissible compte tenu du bord précédent.
 *
 * Coût : O(nombre de touches), sans allocation. Non thread-safe (tampons de travail).
 */
class LayoutSolver(
    val layout: KeyboardLayout,
    val config: AdaptationConfig = AdaptationConfig(),
) {

    private val keys = LayoutKeys(layout)
    private val target = DoubleArray(keys.size)
    private val edgeLow = DoubleArray(keys.size + 1)
    private val edgeHigh = DoubleArray(keys.size + 1)

    /** Plancher des lettres, en unités (0 : aucun). */
    private var floorUnits = 0.0

    /**
     * Largeur du clavier en dp, pour appliquer [AdaptationConfig.minKeyWidthDp] (1 u = largeur / 10).
     * 0 (défaut) : largeur inconnue, aucun plancher en dp.
     */
    var keyboardWidthDp: Float = 0f
        set(value) {
            require(value >= 0f && value < Float.POSITIVE_INFINITY) { "La largeur doit être positive ou nulle : $value." }
            field = value
            floorUnits = if (value > 0f) config.minKeyWidthDp / (value / KeyboardLayout.ROW_UNITS).toDouble() else 0.0
        }

    /**
     * Résout la disposition pour [weights] dans le mode de [config] et l'écrit dans [out].
     *
     * Des poids neutres, une intensité nulle ou le mode Désactivé donnent exactement la disposition
     * au repos : touches au repos ou intensité nulle ramènent chaque cible au repos, et le mode
     * Désactivé n'a ni effet d'apparence ni plafond de largeur.
     *
     * @throws IllegalArgumentException si [weights] ou [out] portent sur une autre disposition.
     */
    fun solve(weights: KeyWeights, out: AdaptedLayout) {
        require(weights.layout === layout) { "Les poids doivent porter sur la disposition du solveur." }
        require(out.layout === layout) { "La sortie doit porter sur la disposition du solveur." }
        val k = weights.intensity
        appearance(weights, config.profile(), k, out)
        val cap = config.displacement()
        for (r in layout.rows.indices) {
            if (cap == 0f) restRow(r, out) else solveRow(r, cap.toDouble(), weights, k.toDouble(), out)
        }
    }

    private fun appearance(weights: KeyWeights, profile: ModeProfile, k: Float, out: AdaptedLayout) {
        for (i in 0 until keys.size) {
            if (weights.atRest[i]) {
                out.opacities[i] = 1f
                out.labelScales[i] = 1f
                continue
            }
            val r = weights.relative[i]
            val s = sqrt(r)
            val opacity = if (r == 0f) config.minOpacity else config.opacityBase + config.opacitySlope * s
            val label = config.minLabelScale + (1f - config.minLabelScale) * s
            out.opacities[i] = 1f - profile.opacityStrength * k * (1f - opacity)
            out.labelScales[i] = 1f - profile.labelStrength * k * (1f - label)
        }
    }

    private fun restRow(r: Int, out: AdaptedLayout) {
        for (i in keys.rowStart[r] until keys.rowStart[r + 1]) out.widths[i] = keys.keys[i].restWidth
    }

    private fun solveRow(r: Int, cap: Double, weights: KeyWeights, k: Double, out: AdaptedLayout) {
        val start = keys.rowStart[r]
        val end = keys.rowStart[r + 1]
        val inset = layout.rows[r].leadingInset.toDouble()
        targets(start, end, weights)

        // Passe de droite à gauche : intervalle admissible [edgeLow[j], edgeHigh[j]] du bord gauche
        // de la j-ième touche de la rangée (j = n : bord droit de la rangée, fixe).
        val n = end - start
        var rowEnd = inset
        for (i in start until end) rowEnd += keys.restWidth[i]
        edgeLow[n] = rowEnd
        edgeHigh[n] = rowEnd
        for (j in n - 1 downTo 0) {
            val i = start + j
            val d = max(0.0, cap * keys.restWidth[i] - GUARD)
            val sumLow = 2 * (keys.restCenter[i] - d)
            val sumHigh = 2 * (keys.restCenter[i] + d)
            val m = minWidth(i)
            val a = edgeLow[j + 1]
            val b = edgeHigh[j + 1]
            var low = sumLow - b
            if (isFixed(i)) low = max(low, max(a - m, (sumLow - m) / 2))
            edgeLow[j] = low
            edgeHigh[j] = min(sumHigh - a, min(b - m, (sumHigh - m) / 2))
        }

        // Passe de gauche à droite : chaque bord au plus près de sa cible.
        var edge = inset
        var targetEdge = inset
        for (j in 0 until n) {
            val i = start + j
            val d = max(0.0, cap * keys.restWidth[i] - GUARD)
            val m = minWidth(i)
            targetEdge += rest(i) + k * (target[i] - rest(i))
            val low = max(edgeLow[j + 1], max(edge + m, 2 * (keys.restCenter[i] - d) - edge))
            val high = min(edgeHigh[j + 1], 2 * (keys.restCenter[i] + d) - edge)
            // Touche de largeur fixe : son bord droit suit le gauche. Sinon, au plus près de la cible ;
            // si un arrondi vidait l'intervalle (low > high), la borne basse l'emporte.
            val next = if (isFixed(i)) edge + m else max(low, min(high, targetEdge))
            out.widths[i] = (next - edge).toFloat()
            edge = next
        }
    }

    /** Largeurs cibles non mélangées avec le repos, lettres normalisées sur la rangée. */
    private fun targets(start: Int, end: Int, weights: KeyWeights) {
        var fixed = 0.0
        var adaptive = 0.0
        var available = 0.0
        for (i in start until end) {
            val restWidth = rest(i)
            available += restWidth
            // Seules les lettres peuvent ne pas être au repos (cf. KeyWeights).
            val t = when {
                !weights.atRest[i] -> {
                    val w = weights.relative[i].toDouble()
                    restWidth * if (w == 0.0) config.minWidth.toDouble() else config.widthBase + config.gain * sqrt(w)
                }
                keys.keys[i].kind == KeyKind.SPACE -> restWidth + config.spaceGain * weights.endProbability.toDouble()
                else -> restWidth
            }
            target[i] = t
            if (weights.atRest[i]) fixed += t else adaptive += t
        }
        // Sans lettre adaptée dans la rangée, rien ne compense l'espace : le solveur la ramène
        // dans ses contraintes (les autres touches au repos, donc l'espace aussi).
        val budget = max(0.0, available - fixed)
        for (i in start until end) {
            if (!weights.atRest[i]) target[i] *= budget / adaptive
        }
    }

    private fun rest(i: Int): Double = keys.restWidth[i]

    private fun isFixed(i: Int): Boolean = keys.keys[i].kind != KeyKind.LETTER && keys.keys[i].kind != KeyKind.SPACE

    private fun minWidth(i: Int): Double = when (keys.keys[i].kind) {
        KeyKind.LETTER -> max(config.minWidth * rest(i), if (config.mode == AdaptationMode.ELASTIC) 0.0 else min(rest(i), floorUnits))
        else -> rest(i)
    }

    private companion object {
        /** Marge numérique sur le plafond de déplacement, en unités (arrondis `Float` des largeurs). */
        const val GUARD = 1e-5
    }
}
