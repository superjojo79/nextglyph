package nextglyph.ime.view

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Vue affichée par l'écran « Carte de frappe » (issue #46). */
enum class HeatmapMode {
    /** Densité des frappes sur chaque touche. */
    DENSITY,

    /** Décalage appris de chaque touche (flèches agrandies). */
    OFFSETS,

    /** Part des frappes suivies d'un ⌫. */
    ERRORS,
    ;

    /** Valeur enregistrée dans les préférences. */
    val stored: String get() = name.lowercase()

    companion object {
        /** Vue enregistrée ; [DENSITY] si la valeur est absente ou inconnue. */
        fun fromStored(value: String?): HeatmapMode = entries.firstOrNull { it.stored == value } ?: DENSITY
    }
}

/** Côté d'un décalage appris. */
enum class Side { LEFT, RIGHT, TOP, BOTTOM }

/** Force d'un décalage appris. */
enum class Strength { NONE, SLIGHT, CLEAR }

/** Décalage appris, décrit en mots : [strength] et les côtés ([horizontal], [vertical]) vers lesquels il pointe. */
data class OffsetSummary(val strength: Strength, val horizontal: Side?, val vertical: Side?)

/**
 * Calculs purs du dessin de la carte de frappe : opacités, couleurs, flèches et description des décalages.
 * Aucune dépendance Android, pour être testés sur la JVM.
 */
object HeatmapStyle {

    /** Facteur d'agrandissement des flèches de décalage (dit dans la légende). */
    const val ARROW_SCALE: Float = 3f

    /** Opacité maximale (sur 255) d'une case de densité : le libellé reste lisible dessus (contraste vérifié par test). */
    const val MAX_ALPHA: Int = 190

    /** Opacité minimale (sur 255) d'une case non vide : une frappe isolée reste visible. */
    const val MIN_ALPHA: Int = 30

    /** Taux d'erreur auquel la touche est entièrement rouge. */
    const val ERROR_FULL_RATE: Double = 0.25

    /** Décalage (fraction de touche) en dessous duquel un axe est considéré comme centré. */
    const val OFFSET_SLIGHT: Float = 0.03f

    /** Décalage (fraction de touche) à partir duquel un décalage est net. */
    const val OFFSET_CLEAR: Float = 0.10f

    /** Opacité (0 à 255) d'une case de [count] frappes quand la plus chargée en a [max] ; 0 pour une case vide. */
    fun cellAlpha(count: Int, max: Int): Int {
        if (count <= 0 || max <= 0) return 0
        val share = min(1.0, count.toDouble() / max)
        return (MIN_ALPHA + share * (MAX_ALPHA - MIN_ALPHA)).roundToInt()
    }

    /** Part de rouge (0 à 1) d'une touche au taux d'erreur [rate] ; `null` si le taux est inconnu. */
    fun errorLevel(rate: Double?): Float? = rate?.let { (it / ERROR_FULL_RATE).coerceIn(0.0, 1.0).toFloat() }

    /** Mélange de deux couleurs opaques `0xRRGGBB` : [from] à 0, [to] à 1, canal par canal. */
    fun blend(from: Int, to: Int, level: Float): Int {
        val t = level.coerceIn(0f, 1f)
        var result = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val a = from shr shift and 0xFF
            val b = to shr shift and 0xFF
            result = result shl 8 or (a + (b - a) * t).roundToInt()
        }
        return result
    }

    /**
     * Extrémité de la flèche d'un décalage ([offsetX], [offsetY]) en fractions de la touche, agrandi de [ARROW_SCALE],
     * relative au centre d'une touche de [width] × [height] ; ramenée, direction conservée, à l'intérieur de la
     * touche à [margin] près de ses bords.
     */
    fun arrow(offsetX: Float, offsetY: Float, width: Float, height: Float, margin: Float): Pair<Float, Float> {
        val vx = offsetX * width * ARROW_SCALE
        val vy = offsetY * height * ARROW_SCALE
        val limitX = width / 2f - margin
        val limitY = height / 2f - margin
        var factor = 1f
        if (abs(vx) > limitX) factor = min(factor, limitX / abs(vx))
        if (abs(vy) > limitY) factor = min(factor, limitY / abs(vy))
        return vx * factor to vy * factor
    }

    /** Longueur d'une flèche de [dx], [dy] pixels ; une flèche plus courte que [min] se réduit au point central. */
    fun isVisibleArrow(dx: Float, dy: Float, min: Float): Boolean = hypot(dx, dy) >= min

    /** Décrit un décalage ([offsetX], [offsetY]) en fractions de touche (x vers la droite, y vers le bas). */
    fun summarize(offsetX: Float, offsetY: Float): OffsetSummary {
        val horizontal = if (abs(offsetX) < OFFSET_SLIGHT) null else if (offsetX < 0) Side.LEFT else Side.RIGHT
        val vertical = if (abs(offsetY) < OFFSET_SLIGHT) null else if (offsetY < 0) Side.TOP else Side.BOTTOM
        val strength = when {
            horizontal == null && vertical == null -> Strength.NONE
            abs(offsetX) >= OFFSET_CLEAR || abs(offsetY) >= OFFSET_CLEAR -> Strength.CLEAR
            else -> Strength.SLIGHT
        }
        return OffsetSummary(strength, horizontal, vertical)
    }
}
