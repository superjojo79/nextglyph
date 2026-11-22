package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyKind

/**
 * Modèle spatial personnalisé lu par [TapDecoder] (spec §5.3) : pour chaque lettre, le décalage
 * moyen des frappes de l'utilisateur par rapport au centre de la touche, et la dispersion de ses
 * frappes relativement à la dispersion par défaut.
 *
 * Le modèle expose aussi un décalage pour les touches de fonction apprises (espace, entrée, ⌫, `?123`),
 * qui déplace les frontières entre ces touches et leurs voisines ([TapDecoder]).
 *
 * Les valeurs sont relatives, donc indépendantes de la taille de l'écran : les décalages sont des
 * fractions de la largeur au repos de la touche (horizontal) et de la hauteur de rangée (vertical).
 * Les lettres inconnues du modèle (hors `a`–`z`) ont les valeurs neutres. Implémentation fournie par
 * `nextglyph.engine.learn.SpatialModel`.
 */
interface TapSpatialModel {

    /** Décalage horizontal moyen de [letter], en fraction de la largeur au repos de la touche (0 : centré). */
    fun offsetX(letter: Char): Float

    /** Décalage vertical moyen de [letter], en fraction de la hauteur de rangée (0 : centré). */
    fun offsetY(letter: Char): Float

    /**
     * Décalage horizontal moyen de la touche de fonction [kind], en fraction de sa largeur au repos
     * (0 : centré, et pour toute touche non apprise).
     */
    fun keyOffsetX(kind: KeyKind): Float = 0f

    /** Décalage vertical moyen de la touche de fonction [kind], en fraction de la hauteur de rangée (0 : centré). */
    fun keyOffsetY(kind: KeyKind): Float = 0f

    /** Facteur appliqué à `σ` horizontalement pour [letter] (1 : valeur par défaut). */
    fun sigmaScaleX(letter: Char): Float

    /** Facteur appliqué à `σ` verticalement pour [letter] (1 : valeur par défaut). */
    fun sigmaScaleY(letter: Char): Float

    companion object {
        /** Modèle vide : aucun décalage, `σ` par défaut. C'est le comportement du décodeur sans personnalisation. */
        val NONE: TapSpatialModel = object : TapSpatialModel {
            override fun offsetX(letter: Char): Float = 0f
            override fun offsetY(letter: Char): Float = 0f
            override fun sigmaScaleX(letter: Char): Float = 1f
            override fun sigmaScaleY(letter: Char): Float = 1f
        }
    }
}
