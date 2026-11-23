package nextglyph.engine.adapt

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayout
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Comment un tap a été décodé. */
enum class TapDecision {
    /** Tap sur une touche de fonction (espace, ⌫, ↵, `?123`) : elle est produite telle quelle. */
    FUNCTION_KEY,

    /** Tap dans le noyau d'une lettre affichée : cette lettre, quel que soit le modèle de langue. */
    CORE,

    /** Lettre de meilleur score `P(point | c) · P(c | p)^λ`. */
    SCORE,
}

/**
 * Résultat d'un décodage (tampon réutilisable, rempli par [TapDecoder.decode]).
 *
 * Les scores ne sont définis que pour les lettres (0 pour les touches de fonction, et pour toutes
 * les touches après un tap sur une touche de fonction) ; ils ne sont pas normalisés.
 */
class TapDecoding(val layout: KeyboardLayout) {

    internal val keys = LayoutKeys(layout)
    internal val scores = DoubleArray(keys.size)

    /** Touche décodée, ou `null` si le tap est hors du clavier. */
    var key: Key? = null
        private set

    /** Rangée de la touche décodée (−1 si aucune). */
    var rowIndex: Int = -1
        private set

    /** Index de la touche décodée dans sa rangée (−1 si aucune). */
    var keyIndex: Int = -1
        private set

    /** Manière dont la touche a été choisie (sans signification si [key] est `null`). */
    var decision: TapDecision = TapDecision.FUNCTION_KEY
        private set

    /** Score de la touche [key] de la rangée [row]. */
    fun score(row: Int, key: Int): Double = scores[keys.index(row, key)]

    internal fun clear() {
        scores.fill(0.0)
        key = null
        rowIndex = -1
        keyIndex = -1
        decision = TapDecision.FUNCTION_KEY
    }

    internal fun set(rect: KeyRect, decision: TapDecision) {
        key = rect.key
        rowIndex = rect.rowIndex
        keyIndex = rect.keyIndex
        this.decision = decision
    }
}

/**
 * Décodeur de tap (spec §5.3) : associe un point touché à la lettre la plus probable plutôt qu'à
 * la touche sous le doigt.
 *
 * ```
 * score(c)      = P(point | c) · max(r(c), minPrior)^λ
 * P(point | c)  = α · N(point ; centre affiché, σ) + (1 − α) · N(point ; centre de repos, σ)
 * ```
 *
 * `N` est une gaussienne 2D normalisée, d'écarts types `σ · largeur au repos` et
 * `σ · hauteur de rangée`. `r(c)` est proportionnel à `P(c | p)`, ce qui ne change pas l'ordre des
 * scores ; une touche au repos (ou des poids absents) a un a priori neutre.
 *
 * **Règles de frontière** (voir `docs/DECISIONS.md`) :
 * - la *nature* de la touche est celle de la touche affichée sous le doigt (après déplacement appris des
 *   frontières des touches de fonction, voir plus bas) : un tap sur une touche
 *   de fonction la produit toujours, et un tap sur une lettre produit toujours une lettre, même
 *   tout près de l'espace ou de ⌫ ; le décodage probabiliste ne départage que des lettres ;
 * - **noyau** : un tap dans la fraction centrale [TapDecoderConfig.coreFraction] (en largeur et en
 *   hauteur) d'une lettre affichée produit cette lettre, même atténuée : c'est la garantie « un tap
 *   franchement au centre d'une touche atténuée produit cette lettre ».
 *
 * **Zones de frappe des touches de fonction** ([TapSpatialModel.keyOffsetX], [TapSpatialModel.keyOffsetY]) :
 * la frontière entre deux touches dont l'une au moins n'est pas une lettre (espace/lettre, `?123`/lettre,
 * ⌫/lettre, ↵/lettre, et les frontières verticales de ces touches avec les lettres de la rangée voisine)
 * est déplacée de la moyenne des décalages appris des deux touches (en pixels), bornée par
 * [TapDecoderConfig.maxBoundaryShift]. L'utilisateur qui vise l'espace un peu trop à gauche agrandit
 * ainsi l'espace de ce côté. Les frontières lettre/lettre ne bougent pas (le score s'en charge). C'est
 * déterministe, borné et indépendant du modèle de langue. Si une frontière horizontale et une frontière
 * verticale revendiquent toutes deux le point, la frontière horizontale l'emporte. Avec un modèle vide
 * ou [TapDecoderConfig.maxBoundaryShift] à 0, la touche retenue est celle sous le doigt.
 *
 * **Modèle spatial personnalisé** ([spatial], §5.3) : pour chaque lettre, les deux centres sont décalés
 * du décalage moyen appris et `σ` est multiplié par le facteur appris. Avec [TapSpatialModel.NONE]
 * (ou un modèle vide : décalages nuls, facteurs 1), le résultat est identique bit à bit.
 * Le noyau et la nature de la touche ne dépendent pas du modèle : seuls les scores changent.
 *
 * Sans allocation ; non thread-safe (aucun état, mais [TapDecoding] est un tampon).
 */
class TapDecoder(
    val layout: KeyboardLayout,
    val config: TapDecoderConfig = TapDecoderConfig(),
    val spatial: TapSpatialModel = TapSpatialModel.NONE,
) {

    /**
     * Décode le tap au point `(x, y)` (pixels, repère des géométries) et écrit le résultat dans [out].
     *
     * @param displayed géométrie affichée (largeurs de l'image courante).
     * @param rest géométrie au repos, de mêmes dimensions.
     * @param weights poids de la frappe courante, ou `null` pour un décodage purement spatial.
     * @return faux si le point est hors du clavier (alors `out.key == null`).
     * @throws IllegalArgumentException si une géométrie, [weights] ou [out] porte sur une autre
     *   disposition, ou si les deux géométries n'ont pas les mêmes dimensions.
     */
    fun decode(x: Float, y: Float, displayed: KeyboardGeometry, rest: KeyboardGeometry, weights: KeyWeights?, out: TapDecoding): Boolean {
        require(displayed.layout === layout && rest.layout === layout) { "Les géométries doivent porter sur la disposition du décodeur." }
        require(displayed.width == rest.width && displayed.rowHeight == rest.rowHeight && displayed.rowGap == rest.rowGap) {
            "Les géométries affichée et au repos doivent avoir les mêmes dimensions."
        }
        require(weights == null || weights.layout === layout) { "Les poids doivent porter sur la disposition du décodeur." }
        require(out.layout === layout) { "Le résultat doit porter sur la disposition du décodeur." }
        out.clear()

        val base = displayed.keyAt(x, y) ?: return false
        val touched = personalized(base, x, y, displayed, rest)
        if (touched.key.kind != KeyKind.LETTER) {
            out.set(touched, TapDecision.FUNCTION_KEY)
            return true
        }

        val alpha = config.alpha.toDouble()
        val sigma = config.sigma.toDouble()
        val lambda = config.lambda.toDouble()
        val sy = sigma * rest.rowHeight
        var best: KeyRect = touched
        var bestScore = -1.0
        var flat = 0
        for (r in 0 until layout.rows.size) {
            val shownRow = displayed.rows[r]
            val restRow = rest.rows[r]
            for (k in 0 until shownRow.size) {
                val shown = shownRow[k]
                if (shown.key.kind == KeyKind.LETTER) {
                    val restRect = restRow[k]
                    val letter = shown.key.label[0]
                    val shiftX = spatial.offsetX(letter) * restRect.width
                    val shiftY = spatial.offsetY(letter) * rest.rowHeight
                    val sx = sigma * restRect.width * spatial.sigmaScaleX(letter)
                    val sigmaY = sy * spatial.sigmaScaleY(letter)
                    val likelihood = alpha * gaussian(x, y, shown.centerX + shiftX, shown.centerY + shiftY, sx, sigmaY) +
                        (1 - alpha) * gaussian(x, y, restRect.centerX + shiftX, restRect.centerY + shiftY, sx, sigmaY)
                    // Une touche au repos a un poids relatif de 1 : a priori neutre.
                    val prior = if (weights == null) 1.0 else max(weights.relative[flat].toDouble(), config.minPrior.toDouble())
                    val score = likelihood * prior.pow(lambda)
                    out.scores[flat] = score
                    if (score > bestScore) {
                        bestScore = score
                        best = shown
                    }
                }
                flat++
            }
        }
        // La touche sous le doigt l'emporte à score égal.
        if (out.scores[out.keys.rowStart[touched.rowIndex] + touched.keyIndex] >= bestScore) best = touched

        val core = config.coreFraction
        val inCore = core > 0f && abs(x - touched.centerX) <= core * touched.width / 2 && abs(y - touched.centerY) <= core * touched.height / 2
        out.set(if (inCore) touched else best, if (inCore) TapDecision.CORE else TapDecision.SCORE)
        return true
    }

    /**
     * Touche retenue pour le point `(x, y)` dont la touche affichée est [base] : [base], ou sa voisine si une
     * frontière avec une touche de fonction, déplacée par les décalages appris, passe de l'autre côté du point.
     */
    private fun personalized(base: KeyRect, x: Float, y: Float, displayed: KeyboardGeometry, rest: KeyboardGeometry): KeyRect {
        if (config.maxBoundaryShift <= 0f) return base
        return horizontalNeighbor(base, x, displayed, rest) ?: verticalNeighbor(base, x, y, displayed, rest) ?: base
    }

    private fun horizontalNeighbor(base: KeyRect, x: Float, displayed: KeyboardGeometry, rest: KeyboardGeometry): KeyRect? {
        val row = displayed.rows[base.rowIndex]
        val toLeft = x < base.centerX
        val neighbor = row.getOrNull(if (toLeft) base.keyIndex - 1 else base.keyIndex + 1) ?: return null
        if (!differentNature(base, neighbor)) return null
        val left = if (toLeft) neighbor else base
        val right = if (toLeft) base else neighbor
        val restRow = rest.rows[base.rowIndex]
        val leftRest = restRow[left.keyIndex]
        val rightRest = restRow[right.keyIndex]
        val shift = (offsetX(left.key) * leftRest.width + offsetX(right.key) * rightRest.width) / 2
        val limit = config.maxBoundaryShift * min(leftRest.width, rightRest.width)
        val boundary = left.right + shift.coerceIn(-limit, limit)
        // Sans décalage, `boundary == left.right` : `keyAt` a déjà attribué le point (gauche si x < right).
        val crossed = if (toLeft) x < boundary else x >= boundary
        return if (crossed) neighbor else null
    }

    private fun verticalNeighbor(base: KeyRect, x: Float, y: Float, displayed: KeyboardGeometry, rest: KeyboardGeometry): KeyRect? {
        val above = y < base.centerY
        val row = base.rowIndex + if (above) -1 else 1
        if (row < 0 || row >= displayed.rows.size) return null
        val neighbor = displayed.rows[row].let { keys -> keys.firstOrNull { x < it.right } ?: keys.last() }
        if (!differentNature(base, neighbor)) return null
        val upper = if (above) neighbor else base
        val lower = if (above) base else neighbor
        val shift = (offsetY(upper.key) + offsetY(lower.key)) * rest.rowHeight / 2
        // Sans décalage appris, `keyAt` décide seul (évite tout écart d'arrondi sur la frontière).
        if (shift == 0f) return null
        val limit = config.maxBoundaryShift * rest.rowHeight
        // Même frontière que `keyAt` : le début de la rangée du bas, écart compris dans la rangée du haut.
        val boundary = (upper.rowIndex + 1) * (displayed.rowHeight + displayed.rowGap) + shift.coerceIn(-limit, limit)
        val crossed = if (above) y < boundary else y >= boundary
        return if (crossed) neighbor else null
    }

    /** Vrai si au moins une des deux touches n'est pas une lettre, et aucune n'est un emplacement vide. */
    private fun differentNature(a: KeyRect, b: KeyRect): Boolean {
        val ka = a.key.kind
        val kb = b.key.kind
        if (ka == KeyKind.EMPTY || kb == KeyKind.EMPTY) return false
        return ka != KeyKind.LETTER || kb != KeyKind.LETTER
    }

    private fun offsetX(key: Key): Float =
        if (key.kind == KeyKind.LETTER) spatial.offsetX(key.label[0]) else spatial.keyOffsetX(key.kind)

    private fun offsetY(key: Key): Float =
        if (key.kind == KeyKind.LETTER) spatial.offsetY(key.label[0]) else spatial.keyOffsetY(key.kind)

    private fun gaussian(x: Float, y: Float, centerX: Float, centerY: Float, sx: Double, sy: Double): Double {
        val dx = (x - centerX) / sx
        val dy = (y - centerY) / sy
        return exp(-(dx * dx + dy * dy) / 2) / (sx * sy)
    }
}
