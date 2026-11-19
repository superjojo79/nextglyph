package nextglyph.engine.learn

import nextglyph.engine.adapt.TapSpatialModel
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.predict.PredictionSettings
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Modèle spatial personnalisé (spec §5.3) : pour chacune des 26 lettres, le décalage moyen et la
 * dispersion des frappes **validées** de l'utilisateur, estimés en ligne.
 *
 * **Frappes validées.** Une frappe est d'abord mise en attente ([stage]) ; elle n'entre dans
 * l'estimation que quand le mot est validé sans annulation ([confirm]). Une lettre effacée
 * ([discardLast]), un mot annulé ou un changement de champ ([discardStaged]) l'écartent : on n'apprend
 * pas de ce que l'utilisateur a lui-même corrigé. Rien n'est mis en attente dans les profils où
 * [LearningPolicy.allowsTaps] est faux (incognito, mot de passe, code PIN, pavés numériques).
 *
 * **Estimation.** Chaque frappe est exprimée relativement à sa touche au repos : décalage du centre en
 * fraction de la largeur (horizontal) et de la hauteur de rangée (vertical), donc indépendant de
 * l'écran, de la rotation et du mode d'adaptation. Moyenne et variance à pondération exponentielle
 * (voir [SpatialConfig]). Une frappe à plus de [SpatialConfig.maxSampleOffset] du centre est ignorée.
 *
 * **Valeurs exposées** (lues par `TapDecoder` via [TapSpatialModel]) : estimation ramenée vers la
 * valeur par défaut (décalage 0, facteur `σ` 1) selon `n / (n + priorWeight)`, puis bornée par
 * [SpatialConfig.maxOffset] et `[minSigmaScale, maxSigmaScale]`. Un modèle vide expose exactement les
 * valeurs par défaut : le décodeur se comporte comme sans modèle.
 *
 * Non thread-safe : à utiliser depuis le fil de l'interface (comme le décodeur).
 */
class SpatialModel(val config: SpatialConfig = SpatialConfig()) : TapSpatialModel {

    private class Tap(val letter: Int, val dx: Double, val dy: Double)

    private val samples = IntArray(LETTERS)
    private val meanX = DoubleArray(LETTERS)
    private val meanY = DoubleArray(LETTERS)
    private val varX = DoubleArray(LETTERS)
    private val varY = DoubleArray(LETTERS)
    private val staged = ArrayDeque<Tap>()

    /** Change à chaque modification de l'estimation (à enregistrer quand elle change). */
    var revision: Long = 0L
        private set

    /** Vrai si aucune frappe n'a été apprise. */
    val isEmpty: Boolean get() = samples.all { it == 0 }

    /** Nombre de frappes en attente de validation. */
    val stagedCount: Int get() = staged.size

    /** Nombre de frappes prises en compte pour [letter] (au plus [SpatialConfig.window]). */
    fun sampleCount(letter: Char): Int = indexOf(letter).let { if (it < 0) 0 else samples[it] }

    /**
     * Met en attente une frappe sur [letter] décalée de ([dx], [dy]) par rapport au centre de la touche
     * au repos, en fraction de sa largeur et de la hauteur de rangée. Faux (rien n'est retenu) si
     * l'apprentissage est interdit, si [letter] n'est pas une lettre `a`–`z`, si une valeur n'est pas
     * finie, ou si la frappe tombe hors du rectangle (voir [SpatialConfig.maxSampleOffset]).
     */
    fun stage(letter: Char, dx: Double, dy: Double, settings: PredictionSettings): Boolean {
        if (!LearningPolicy.allowsTaps(settings)) return false
        val index = indexOf(letter)
        if (index < 0 || !dx.isFinite() || !dy.isFinite()) return false
        if (abs(dx) > config.maxSampleOffset || abs(dy) > config.maxSampleOffset) return false
        staged.addLast(Tap(index, dx, dy))
        if (staged.size > config.maxStaged) staged.removeFirst()
        return true
    }

    /**
     * Met en attente la frappe au point ([x], [y]) (pixels) décodée comme la touche [rest] de la
     * géométrie au repos ; sans effet pour une touche qui n'est pas une lettre.
     */
    fun stage(x: Float, y: Float, rest: KeyRect, settings: PredictionSettings): Boolean {
        if (rest.key.kind != KeyKind.LETTER) return false
        return stage(rest.key.label[0], (x - rest.centerX).toDouble() / rest.width, (y - rest.centerY).toDouble() / rest.height, settings)
    }

    /** Écarte la dernière frappe en attente (lettre effacée). Faux s'il n'y en a pas. */
    fun discardLast(): Boolean = staged.removeLastOrNull() != null

    /** Écarte toutes les frappes en attente (mot annulé, curseur déplacé, champ changé). */
    fun discardStaged() = staged.clear()

    /** Intègre les frappes en attente (mot validé sans annulation) ; renvoie leur nombre. */
    fun confirm(): Int {
        val count = staged.size
        for (tap in staged) update(tap)
        staged.clear()
        if (count > 0) revision++
        return count
    }

    /** Oublie tout ce qui est appris et les frappes en attente (§12). */
    fun reset() {
        samples.fill(0)
        meanX.fill(0.0)
        meanY.fill(0.0)
        varX.fill(0.0)
        varY.fill(0.0)
        staged.clear()
        revision++
    }

    override fun offsetX(letter: Char): Float = offset(letter, meanX)

    override fun offsetY(letter: Char): Float = offset(letter, meanY)

    override fun sigmaScaleX(letter: Char): Float = scale(letter, varX)

    override fun sigmaScaleY(letter: Char): Float = scale(letter, varY)

    private fun offset(letter: Char, means: DoubleArray): Float {
        val index = indexOf(letter)
        if (index < 0) return 0f
        return (means[index].coerceIn(-config.maxOffset, config.maxOffset) * confidence(index)).toFloat()
    }

    private fun scale(letter: Char, variances: DoubleArray): Float {
        val index = indexOf(letter)
        if (index < 0) return 1f
        val measured = (sqrt(variances[index]) / config.expectedSpread).coerceIn(config.minSigmaScale, config.maxSigmaScale)
        return (1.0 + (measured - 1.0) * confidence(index)).toFloat()
    }

    /** Part de l'estimation dans la valeur exposée : `n / (n + priorWeight)`, 0 sans frappe. */
    private fun confidence(index: Int): Double {
        val n = samples[index]
        return if (n == 0) 0.0 else n / (n + config.priorWeight)
    }

    private fun update(tap: Tap) {
        val i = tap.letter
        samples[i] = minOf(samples[i] + 1, config.window)
        val w = 1.0 / samples[i]
        val ex = tap.dx - meanX[i]
        meanX[i] += w * ex
        varX[i] = (1 - w) * (varX[i] + w * ex * ex)
        val ey = tap.dy - meanY[i]
        meanY[i] += w * ey
        varY[i] = (1 - w) * (varY[i] + w * ey * ey)
    }

    /** Contenu pour l'enregistrement : `a`–`z`, dans l'ordre. */
    internal fun snapshot(): List<LetterStats> =
        List(LETTERS) { LetterStats(samples[it], meanX[it], meanY[it], varX[it], varY[it]) }

    /**
     * Remplace l'estimation par celle d'un enregistrement.
     *
     * @throws IllegalArgumentException si les valeurs sont invalides ; rien n'est alors modifié.
     */
    internal fun restore(stats: List<LetterStats>) {
        require(stats.size == LETTERS) { "Il faut $LETTERS lettres (${stats.size})." }
        for (s in stats) {
            require(s.samples in 0..config.window) { "Nombre de frappes invalide (${s.samples})." }
            require(abs(s.meanX) <= config.maxSampleOffset) { "Décalage horizontal invalide (${s.meanX})." }
            require(abs(s.meanY) <= config.maxSampleOffset) { "Décalage vertical invalide (${s.meanY})." }
            require(s.varX in 0.0..MAX_VARIANCE) { "Variance horizontale invalide (${s.varX})." }
            require(s.varY in 0.0..MAX_VARIANCE) { "Variance verticale invalide (${s.varY})." }
        }
        stats.forEachIndexed { i, s ->
            samples[i] = s.samples
            meanX[i] = s.meanX
            meanY[i] = s.meanY
            varX[i] = s.varX
            varY[i] = s.varY
        }
        staged.clear()
        revision++
    }

    private fun indexOf(letter: Char): Int = if (letter in 'a'..'z') letter - 'a' else -1

    private companion object {
        const val LETTERS = 26

        /** Variance maximale d'une frappe retenue : l'écart type ne dépasse pas la demi-touche (0,25 = 0,5²). */
        const val MAX_VARIANCE = 0.25
    }
}

/** Statistiques d'une lettre, telles qu'enregistrées. */
internal class LetterStats(val samples: Int, val meanX: Double, val meanY: Double, val varX: Double, val varY: Double)
