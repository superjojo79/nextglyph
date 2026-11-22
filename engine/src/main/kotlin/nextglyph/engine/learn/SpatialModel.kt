package nextglyph.engine.learn

import nextglyph.engine.adapt.TapSpatialModel
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.predict.PredictionSettings
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Modèle spatial personnalisé (spec §5.3) : pour chacune des 26 lettres et des quatre touches de
 * fonction apprises ([LEARNED_KEYS] : espace, entrée, ⌫, `?123`), le décalage moyen et la dispersion des
 * frappes **validées** de l'utilisateur, estimés en ligne.
 *
 * **Frappes validées.** Une frappe est d'abord mise en attente ([stage]) ; elle n'entre dans
 * l'estimation que quand le mot est validé sans annulation ([confirm]). Une lettre effacée
 * ([discardLast]), un mot annulé ou un changement de champ ([discardStaged]) l'écartent : on n'apprend
 * pas de ce que l'utilisateur a lui-même corrigé. Une frappe sur une touche de fonction suit un circuit
 * séparé, à **une seule place** ([stageKey], [confirmKey], [discardKey]) : `SessionLearning` la valide ou
 * l'écarte d'après l'événement suivant. [confirm], [discardStaged] et [discardLast] ne touchent que les
 * lettres. Rien n'est mis en attente dans les profils où
 * [LearningPolicy.allowsTaps] est faux (incognito, mot de passe, code PIN, pavés numériques).
 *
 * **Estimation.** Chaque frappe est exprimée relativement à sa touche au repos : décalage du centre en
 * fraction de la largeur (horizontal) et de la hauteur de rangée (vertical), donc indépendant de
 * l'écran, de la rotation et du mode d'adaptation. Moyenne et variance à pondération exponentielle
 * (voir [SpatialConfig]). Une frappe à plus de [SpatialConfig.maxSampleOffset] du centre est ignorée.
 *
 * **Valeurs exposées** (lues par `TapDecoder` via [TapSpatialModel]) : estimation ramenée vers la
 * valeur par défaut (décalage 0, facteur `σ` 1) selon `n / (n + priorWeight)`, puis bornée par
 * [SpatialConfig.maxOffset] et `[minSigmaScale, maxSigmaScale]`. Pour une touche de fonction, le
 * décalage est relatif à son rectangle au repos (largeur de la touche, hauteur de rangée) ; il sert à
 * déplacer les frontières avec les touches voisines (`TapDecoder`). Un modèle vide expose exactement les
 * valeurs par défaut : le décodeur se comporte comme sans modèle.
 *
 * Non thread-safe : à utiliser depuis le fil de l'interface (comme le décodeur).
 */
class SpatialModel(val config: SpatialConfig = SpatialConfig()) : TapSpatialModel {

    private class Tap(val slot: Int, val dx: Double, val dy: Double)

    private val samples = IntArray(SLOTS)
    private val meanX = DoubleArray(SLOTS)
    private val meanY = DoubleArray(SLOTS)
    private val varX = DoubleArray(SLOTS)
    private val varY = DoubleArray(SLOTS)
    private val staged = ArrayDeque<Tap>()
    private var pending: Tap? = null

    /** Change à chaque modification de l'estimation (à enregistrer quand elle change). */
    var revision: Long = 0L
        private set

    /** Touche de fonction dont la frappe est en attente de validation, ou `null`. */
    val pendingKey: KeyKind? get() = pending?.let { LEARNED_KEYS[it.slot - LETTERS] }

    /** Vrai si aucune frappe n'a été apprise (lettres et touches de fonction). */
    val isEmpty: Boolean get() = samples.all { it == 0 }

    /** Nombre de frappes en attente de validation. */
    val stagedCount: Int get() = staged.size

    /** Nombre total de frappes prises en compte, lettres et touches de fonction (somme des 30 compteurs). */
    val totalSamples: Int get() = samples.sum()

    /** Nombre de frappes prises en compte pour [letter] (au plus [SpatialConfig.window]). */
    fun sampleCount(letter: Char): Int = indexOf(letter).let { if (it < 0) 0 else samples[it] }

    /** Nombre de frappes prises en compte pour la touche de fonction [kind] (0 si elle n'est pas apprise). */
    fun sampleCount(kind: KeyKind): Int = slotOf(kind).let { if (it < 0) 0 else samples[it] }

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

    /**
     * Met en attente la frappe sur la touche de fonction [kind] (parmi [LEARNED_KEYS]) décalée de ([dx], [dy])
     * par rapport au centre de sa touche au repos (fraction de sa largeur et de la hauteur de rangée). Elle
     * remplace une frappe de touche déjà en attente. Faux (rien n'est retenu) si l'apprentissage est interdit,
     * si [kind] n'est pas apprise, si une valeur n'est pas finie ou si la frappe tombe hors du rectangle.
     */
    fun stageKey(kind: KeyKind, dx: Double, dy: Double, settings: PredictionSettings): Boolean {
        if (!LearningPolicy.allowsTaps(settings)) return false
        val slot = slotOf(kind)
        if (slot < 0 || !dx.isFinite() || !dy.isFinite()) return false
        if (abs(dx) > config.maxSampleOffset || abs(dy) > config.maxSampleOffset) return false
        pending = Tap(slot, dx, dy)
        return true
    }

    /** Intègre la frappe de touche en attente ; faux s'il n'y en a pas. */
    fun confirmKey(): Boolean {
        val tap = pending ?: return false
        pending = null
        update(tap)
        revision++
        return true
    }

    /** Écarte la frappe de touche en attente ; faux s'il n'y en a pas. */
    fun discardKey(): Boolean {
        if (pending == null) return false
        pending = null
        return true
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
        pending = null
        revision++
    }

    override fun offsetX(letter: Char): Float = offset(letter, meanX)

    override fun offsetY(letter: Char): Float = offset(letter, meanY)

    override fun keyOffsetX(kind: KeyKind): Float = slotOf(kind).let { if (it < 0) 0f else offsetAt(it, meanX) }

    override fun keyOffsetY(kind: KeyKind): Float = slotOf(kind).let { if (it < 0) 0f else offsetAt(it, meanY) }

    override fun sigmaScaleX(letter: Char): Float = scale(letter, varX)

    override fun sigmaScaleY(letter: Char): Float = scale(letter, varY)

    private fun offset(letter: Char, means: DoubleArray): Float {
        val index = indexOf(letter)
        if (index < 0) return 0f
        return offsetAt(index, means)
    }

    private fun offsetAt(index: Int, means: DoubleArray): Float =
        (means[index].coerceIn(-config.maxOffset, config.maxOffset) * confidence(index)).toFloat()

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
        val i = tap.slot
        samples[i] = minOf(samples[i] + 1, config.window)
        val w = 1.0 / samples[i]
        val ex = tap.dx - meanX[i]
        meanX[i] += w * ex
        varX[i] = (1 - w) * (varX[i] + w * ex * ex)
        val ey = tap.dy - meanY[i]
        meanY[i] += w * ey
        varY[i] = (1 - w) * (varY[i] + w * ey * ey)
    }

    /** Contenu pour l'enregistrement : `a`–`z`, puis les touches de [LEARNED_KEYS], dans l'ordre (30 entrées). */
    internal fun snapshot(): List<LetterStats> =
        List(SLOTS) { LetterStats(samples[it], meanX[it], meanY[it], varX[it], varY[it]) }

    /**
     * Remplace l'estimation par celle d'un enregistrement : 26 entrées (lettres seules, enregistrement
     * d'avant les touches de fonction, qui restent vides) ou 30 (lettres puis [LEARNED_KEYS]).
     *
     * @throws IllegalArgumentException si les valeurs sont invalides ; rien n'est alors modifié.
     */
    internal fun restore(stats: List<LetterStats>) {
        require(stats.size == LETTERS || stats.size == SLOTS) { "Il faut $LETTERS lettres, ou $SLOTS avec les touches (${stats.size})." }
        for (s in stats) {
            require(s.samples in 0..config.window) { "Nombre de frappes invalide (${s.samples})." }
            require(abs(s.meanX) <= config.maxSampleOffset) { "Décalage horizontal invalide (${s.meanX})." }
            require(abs(s.meanY) <= config.maxSampleOffset) { "Décalage vertical invalide (${s.meanY})." }
            require(s.varX in 0.0..MAX_VARIANCE) { "Variance horizontale invalide (${s.varX})." }
            require(s.varY in 0.0..MAX_VARIANCE) { "Variance verticale invalide (${s.varY})." }
        }
        for (i in 0 until SLOTS) {
            val s = stats.getOrNull(i) ?: LetterStats(0, 0.0, 0.0, 0.0, 0.0)
            samples[i] = s.samples
            meanX[i] = s.meanX
            meanY[i] = s.meanY
            varX[i] = s.varX
            varY[i] = s.varY
        }
        staged.clear()
        pending = null
        revision++
    }

    private fun indexOf(letter: Char): Int = if (letter in 'a'..'z') letter - 'a' else -1

    private fun slotOf(kind: KeyKind): Int = LEARNED_KEYS.indexOf(kind).let { if (it < 0) -1 else LETTERS + it }

    companion object {
        /** Touches de fonction apprises, dans l'ordre de leurs places 26 à 29 de l'enregistrement. */
        val LEARNED_KEYS: List<KeyKind> = listOf(KeyKind.SPACE, KeyKind.ENTER, KeyKind.BACKSPACE, KeyKind.SYMBOLS)

        private const val LETTERS = 26
        private val SLOTS = LETTERS + LEARNED_KEYS.size

        /** Variance maximale d'une frappe retenue : l'écart type ne dépasse pas la demi-touche (0,25 = 0,5²). */
        private const val MAX_VARIANCE = 0.25
    }
}

/** Statistiques d'une lettre, telles qu'enregistrées. */
internal class LetterStats(val samples: Int, val meanX: Double, val meanY: Double, val varX: Double, val varY: Double)
