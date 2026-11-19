package nextglyph.engine.predict

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import kotlin.math.hypot

/**
 * Voisinage des lettres d'une disposition (spec §10.4) : deux lettres sont voisines si la distance
 * entre leurs centres au repos est au plus [maxDistance] unités (une rangée = 1 u de haut). Même
 * modèle que l'analyse M0, calculé ici sur la disposition réelle du moteur.
 */
class KeyAdjacency(layout: KeyboardLayout, val maxDistance: Double = DEFAULT_DISTANCE) {

    private val neighbors = BooleanArray(LETTERS * LETTERS)

    init {
        require(maxDistance > 0.0) { "La distance de voisinage doit être strictement positive ($maxDistance)." }
        val letters = ArrayList<Triple<Char, Double, Double>>()
        layout.rows.forEachIndexed { y, row ->
            var edge = row.leadingInset.toDouble()
            for (key in row.keys) {
                val c = key.label.singleOrNull()
                if (key.kind == KeyKind.LETTER && c != null && c in 'a'..'z') {
                    letters += Triple(c, edge + key.restWidth / 2.0, y.toDouble())
                }
                edge += key.restWidth
            }
        }
        for ((a, ax, ay) in letters) {
            for ((b, bx, by) in letters) {
                if (a != b && hypot(ax - bx, ay - by) <= maxDistance) neighbors[(a - 'a') * LETTERS + (b - 'a')] = true
            }
        }
    }

    /** Vrai si [a] et [b] sont deux lettres distinctes et voisines (faux hors de `a`–`z`). */
    fun areNeighbors(a: Char, b: Char): Boolean =
        a in 'a'..'z' && b in 'a'..'z' && neighbors[(a - 'a') * LETTERS + (b - 'a')]

    /** Les voisines de [c], par ordre alphabétique. */
    fun neighbors(c: Char): List<Char> = ('a'..'z').filter { areNeighbors(c, it) }

    companion object {
        /** Distance de voisinage de l'analyse M0 (u). */
        const val DEFAULT_DISTANCE: Double = 1.5
        private const val LETTERS = 26
    }
}
