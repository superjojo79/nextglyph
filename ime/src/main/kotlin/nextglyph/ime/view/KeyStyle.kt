package nextglyph.ime.view

import kotlin.math.max

/** Apparence d'une touche adaptée (spec §5.2, §13). */
object KeyStyle {

    /**
     * Opacité minimale du libellé d'une touche atténuée : sous celle de la touche, pour garder un
     * contraste d'au moins 3:1 (§13) même à l'opacité minimale des lettres du mode Élastique (0,30) ;
     * vérifié sur les deux thèmes par `KeyStyleTest`.
     */
    const val MIN_LABEL_OPACITY: Float = 0.55f

    /** Opacité du libellé d'une touche d'opacité [keyOpacity] : la touche, mais pas sous [MIN_LABEL_OPACITY]. */
    fun labelOpacity(keyOpacity: Float): Float = max(keyOpacity, MIN_LABEL_OPACITY)
}
