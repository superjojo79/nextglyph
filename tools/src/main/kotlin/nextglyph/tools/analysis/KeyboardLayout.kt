package nextglyph.tools.analysis

import kotlin.math.hypot

private const val MAX_NEIGHBOR_DISTANCE = 1.5

/** Une rangée de lettres : abscisse du bord gauche de la première lettre et largeur d'une lettre (u). */
internal class LetterRow(val letters: String, val start: Double, val keyWidth: Double)

/**
 * Géométrie des 26 lettres de la disposition compacte au repos (spec §5.1), en unités de touche
 * (1 u = largeur d'une lettre des rangées 1 et 2 ; une rangée = 1 u de haut, approximation d'une
 * touche de 38 dp sur un écran de 360 dp).
 *
 * - AZERTY : rangées 1 et 2 alignées (10 u), rangée 3 = `?123` (1 u) puis 6 lettres de 0,9 u.
 * - QWERTY : rangée 2 décalée de 0,3 u avec des lettres de 0,95 u, rangée 3 = `?123` puis 7 lettres de 0,9 u.
 *
 * Deux lettres sont **voisines** si la distance entre leurs centres est au plus
 * [NEIGHBOR_DISTANCE] (1,5 u) : les deux voisines de rangée et, sur les rangées adjacentes, les
 * touches dont le centre est décalé d'au plus 1,12 u. Ce modèle d'adjacence sert à la fois au
 * modèle de fautes (substitution par une voisine) et à la pondération des corrections (§10.4).
 */
enum class KeyboardLayout(rows: List<LetterRow>) {
    AZERTY(
        listOf(
            LetterRow("azertyuiop", 0.0, 1.0),
            LetterRow("qsdfghjklm", 0.0, 1.0),
            LetterRow("wxcvbn", 1.0, 0.9),
        ),
    ),
    QWERTY(
        listOf(
            LetterRow("qwertyuiop", 0.0, 1.0),
            LetterRow("asdfghjkl", 0.3, 0.95),
            LetterRow("zxcvbnm", 1.0, 0.9),
        ),
    ),
    ;

    private val centers: Map<Char, Pair<Double, Double>> = buildMap {
        rows.forEachIndexed { y, row ->
            row.letters.forEachIndexed { i, c -> put(c, row.start + row.keyWidth * (i + 0.5) to y.toDouble()) }
        }
    }

    private val neighborMap: Map<Char, List<Char>> = centers.keys.associateWith { c ->
        centers.keys.filter { it != c && distance(c, it) <= MAX_NEIGHBOR_DISTANCE }.sorted()
    }

    /** Les lettres de la disposition. */
    val letters: Set<Char> get() = centers.keys

    /** Distance entre les centres de deux lettres, en u. */
    fun distance(a: Char, b: Char): Double {
        val (ax, ay) = requireNotNull(centers[a]) { "Lettre absente de la disposition : « $a »." }
        val (bx, by) = requireNotNull(centers[b]) { "Lettre absente de la disposition : « $b »." }
        return hypot(ax - bx, ay - by)
    }

    /** Les voisines de [c], par ordre alphabétique ; vide si [c] n'est pas une lettre de la disposition. */
    fun neighbors(c: Char): List<Char> = neighborMap[c] ?: emptyList()

    /** Vrai si [a] et [b] sont deux lettres voisines distinctes. */
    fun areNeighbors(a: Char, b: Char): Boolean = b in neighbors(a)

    companion object {
        /** Distance maximale entre les centres de deux touches voisines (u). */
        const val NEIGHBOR_DISTANCE: Double = MAX_NEIGHBOR_DISTANCE
    }
}
