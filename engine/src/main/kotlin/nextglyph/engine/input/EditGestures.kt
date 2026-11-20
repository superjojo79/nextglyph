package nextglyph.engine.input

import kotlin.math.abs

/**
 * Glissement horizontal découpé en pas (gestes d'édition du §5.9, issue #33) : déplacer le curseur sur
 * l'espace, effacer mot par mot depuis ⌫.
 *
 * Le geste s'active quand le doigt s'est éloigné de [activationPx] horizontalement, plus qu'il n'a bougé
 * verticalement (un glissement vers le haut reste un glissement vers le haut). L'activation compte un premier
 * pas dans son sens ; les suivants sont comptés depuis le point d'activation, tous les [stepPx] ; revenir en
 * arrière compte des pas de signe opposé.
 */
class DragSteps(private val startX: Float, private val startY: Float, private val activationPx: Float, private val stepPx: Float) {

    init {
        require(activationPx > 0f && stepPx > 0f) { "Seuils invalides : $activationPx, $stepPx." }
    }

    /** Le geste est actif : le relâchement ne doit pas taper la touche. */
    var active: Boolean = false
        private set

    private var anchorX = 0f
    private var direction = 0
    private var emitted = 0

    /** Doigt en `(x, y)` : nombre de pas nouveaux depuis l'appel précédent (négatif vers la gauche). */
    fun move(x: Float, y: Float): Int {
        val dx = x - startX
        if (!active) {
            if (abs(dx) <= activationPx || abs(dx) <= abs(y - startY)) return 0
            active = true
            direction = if (dx > 0) 1 else -1
            anchorX = startX + direction * activationPx
        }
        val steps = direction + ((x - anchorX) / stepPx).toInt()
        val delta = steps - emitted
        emitted = steps
        return delta
    }
}

/** Effacement du mot précédent (glisser vers la gauche depuis ⌫, §5.9). */
object WordDeletion {

    /**
     * Nombre de caractères à effacer avant le curseur pour [before] : les espaces de fin, puis le mot qui les
     * précède (lettres, chiffres, apostrophes et traits d'union) ; à défaut de mot, un seul signe. 0 si vide.
     */
    fun length(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 0
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i == 0) return before.length
        if (!isWordChar(before[i - 1])) return before.length - i + TextUnits.lastCodePointLength(before.subSequence(0, i))
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.length - i
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c in JOINERS

    private const val JOINERS = "'’-‑_"
}
