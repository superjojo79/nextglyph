package nextglyph.tools.analysis

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.predict.TypedTap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Mot tapé : texte résultant et frappes ([taps] : une entrée par caractère, `null` hors `a`–`z`). */
class SimulatedWord(val typed: String, val taps: List<TypedTap?>)

/**
 * Modèle de frappe gaussien pour simuler la position des doigts (issue #53). Chaque lettre voulue est
 * tapée en un point tiré selon `N(centre de repos, σ · largeur, σ · 1 rangée)` dans le repère en unités
 * de touche de [layout] (rangées : `leadingInset` + `restWidth`, comme `SpatialSubstitution`). La lettre
 * tapée est celle dont la touche au repos contient le point ; `dx`/`dy` sont relatifs à cette touche.
 *
 * @property sigma écart-type, en fraction de la largeur de la touche (`x`) et de la hauteur de rangée (`y`).
 */
class GaussianTapModel(layout: KeyboardLayout, val sigma: Double) {

    private class LetterKey(val letter: Char, val start: Double, val width: Double, val row: Int) {
        val centerX: Double get() = start + width / 2.0
    }

    private val rows: List<List<LetterKey>>
    private val rowIndexes: List<Int>
    private val byLetter = HashMap<Char, LetterKey>()

    init {
        require(sigma.isFinite() && sigma >= 0.0) { "L'écart-type doit être positif ou nul : $sigma." }
        val built = ArrayList<List<LetterKey>>()
        val indexes = ArrayList<Int>()
        layout.rows.forEachIndexed { r, row ->
            var edge = row.leadingInset.toDouble()
            val keys = ArrayList<LetterKey>()
            for (key in row.keys) {
                val c = key.label.singleOrNull()
                if (key.kind == KeyKind.LETTER && c != null && c in 'a'..'z') {
                    val k = LetterKey(c, edge, key.restWidth.toDouble(), r)
                    keys += k
                    byLetter.putIfAbsent(c, k)
                }
                edge += key.restWidth
            }
            if (keys.isNotEmpty()) {
                built += keys
                indexes += r
            }
        }
        require(built.isNotEmpty()) { "La disposition ne contient aucune lettre a–z." }
        rows = built
        rowIndexes = indexes
    }

    /** Lettre dont la touche au repos contient le point ([x], [y]) (voir la classe). */
    fun letterAt(x: Double, y: Double): Char = keyAt(x, y).letter

    private fun keyAt(x: Double, y: Double): LetterKey {
        val rowPos = floor(y + 0.5).toInt().coerceIn(rowIndexes.first(), rowIndexes.last())
        val rowKeys = rows.getOrNull(rowIndexes.indexOf(rowPos))
            ?: return nearest(x, y)
        val clamped = x.coerceIn(rowKeys.first().start, rowKeys.last().start + rowKeys.last().width - EPSILON)
        return rowKeys.firstOrNull { clamped >= it.start && clamped < it.start + it.width } ?: nearest(x, y)
    }

    /** Lettre la plus proche (distance euclidienne aux centres de repos), si le point tombe hors d'une lettre. */
    private fun nearest(x: Double, y: Double): LetterKey =
        byLetter.values.minBy { (it.centerX - x) * (it.centerX - x) + (it.row - y) * (it.row - y) }

    /** Frappe de la lettre voulue [intended] ; `null` si elle n'est pas une lettre `a`–`z` de la disposition. */
    fun tap(intended: Char, random: Random): TypedTap? {
        val key = byLetter[intended] ?: return null
        val x = key.centerX + gaussian(random) * sigma * key.width
        val y = key.row + gaussian(random) * sigma
        val hit = keyAt(x, y)
        return TypedTap(hit.letter, (x - hit.centerX) / hit.width, y - hit.row)
    }

    /** Tape [word] : les caractères hors `a`–`z` (`'`, `-`) sont tapés tels quels, sans position. */
    fun type(word: String, random: Random): SimulatedWord {
        val taps = word.map { tap(it, random) }
        val typed = word.indices.joinToString("") { (taps[it]?.letter ?: word[it]).toString() }
        return SimulatedWord(typed, taps)
    }

    private fun gaussian(random: Random): Double {
        val u = 1.0 - random.nextDouble() // dans ]0 ; 1]
        return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * random.nextDouble())
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
