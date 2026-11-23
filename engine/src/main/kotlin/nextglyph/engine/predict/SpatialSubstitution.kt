package nextglyph.engine.predict

import nextglyph.engine.adapt.TapSpatialModel
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Poids de substitution d'une lettre tapée par une autre, d'après la position réelle de la frappe
 * (issue #53) : rapport de vraisemblances gaussiennes `L(p | voulue) / L(p | tapée)`, avec le même
 * modèle que `TapDecoder` (centre de repos décalé par le modèle appris, `σx = σ · largeur · échelle`,
 * `σy = σ · échelle`). Repère : unités de touche, `x` en largeurs de lettre, `y` en rangées.
 *
 * Poids = `clamp(spatialScale × rapport, otherSubstitution, 1)`. Construit une fois par prédicteur :
 * les centres au repos sont précalculés.
 */
class SpatialSubstitution(layout: KeyboardLayout, private val config: PredictionConfig) {

    private val present = BooleanArray(LETTERS)
    private val centerX = DoubleArray(LETTERS)
    private val centerY = DoubleArray(LETTERS)
    private val width = DoubleArray(LETTERS)
    private val floor = config.correction.otherSubstitution

    init {
        layout.rows.forEachIndexed { r, row ->
            var edge = row.leadingInset.toDouble()
            for (key in row.keys) {
                val c = key.label.singleOrNull()
                if (key.kind == KeyKind.LETTER && c != null && c in 'a'..'z' && !present[c - 'a']) {
                    val i = c - 'a'
                    present[i] = true
                    centerX[i] = edge + key.restWidth / 2.0
                    centerY[i] = r.toDouble()
                    width[i] = key.restWidth.toDouble()
                }
                edge += key.restWidth
            }
        }
    }

    /**
     * Poids de la substitution de la lettre tapée [tap] par [intended], ou `-1` si le calcul ne
     * s'applique pas (une des deux lettres hors de la disposition) : l'appelant garde alors le poids binaire.
     */
    fun weight(tap: TypedTap, intended: Char, spatial: TapSpatialModel): Double {
        if (tap.letter !in 'a'..'z' || intended !in 'a'..'z') return NOT_APPLICABLE
        val t = tap.letter - 'a'
        val c = intended - 'a'
        if (!present[t] || !present[c]) return NOT_APPLICABLE
        val px = centerX[t] + tap.dx * width[t]
        val py = centerY[t] + tap.dy
        val logRatio = logDensity(px, py, c, intended, spatial) - logDensity(px, py, t, tap.letter, spatial)
        val ratio = exp(min(logRatio, MAX_LOG_RATIO))
        return max(floor, min(1.0, config.spatialScale * ratio))
    }

    /** Logarithme de la gaussienne 2D normalisée (sans le facteur `1/2π`, commun aux deux touches). */
    private fun logDensity(px: Double, py: Double, index: Int, letter: Char, spatial: TapSpatialModel): Double {
        val sx = config.spatialSigma * width[index] * spatial.sigmaScaleX(letter)
        val sy = config.spatialSigma * spatial.sigmaScaleY(letter)
        val dx = (px - (centerX[index] + spatial.offsetX(letter) * width[index])) / sx
        val dy = (py - (centerY[index] + spatial.offsetY(letter))) / sy
        return -(dx * dx + dy * dy) / 2 - ln(sx * sy)
    }

    private companion object {
        const val LETTERS = 26
        const val NOT_APPLICABLE = -1.0

        /** Évite le dépassement de `exp` : au-delà, le poids est de toute façon plafonné à 1. */
        const val MAX_LOG_RATIO = 50.0
    }
}
