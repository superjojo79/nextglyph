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

    /** Opacité (sur 255) du texte tapé proposé dans la barre (§5.4) : contraste ≥ 4,5:1 vérifié par `KeyStyleTest`. */
    const val TYPED_ALPHA: Int = 170

    /** Opacité (sur 255) de l'indicateur de langue sur l'espace (§10.6) : contraste ≥ 3:1 vérifié par `KeyStyleTest`. */
    const val LANGUAGE_ALPHA: Int = 150

    /** Opacité du libellé d'une touche d'opacité [keyOpacity] : la touche, mais pas sous [MIN_LABEL_OPACITY]. */
    fun labelOpacity(keyOpacity: Float): Float = max(keyOpacity, MIN_LABEL_OPACITY)
}
