package nextglyph.engine.adapt

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.lexicon.LexiconAlphabet

/**
 * Index à plat des touches d'une disposition (rangée par rangée), précalculé une fois pour
 * éviter toute allocation dans les chemins appelés à chaque frappe.
 */
internal class LayoutKeys(val layout: KeyboardLayout) {

    /** Les touches, rangée par rangée. */
    val keys: Array<Key> = layout.rows.flatMap { it.keys }.toTypedArray()

    /** Nombre total de touches. */
    val size: Int = keys.size

    /** Index à plat de la première touche de chaque rangée, plus [size] en dernière position. */
    val rowStart: IntArray = IntArray(layout.rows.size + 1).also { starts ->
        for (r in layout.rows.indices) starts[r + 1] = starts[r] + layout.rows[r].keys.size
    }

    /** Index de la lettre dans [LexiconAlphabet] (−1 pour une touche de fonction ou hors alphabet). */
    val alphabetIndex: IntArray = IntArray(size) { i ->
        if (keys[i].kind == KeyKind.LETTER) LexiconAlphabet.indexOf(keys[i].label[0]) else -1
    }

    /** Largeurs au repos, en unités. */
    val restWidth: DoubleArray = DoubleArray(size) { keys[it].restWidth.toDouble() }

    /** Centre au repos de chaque touche, en unités depuis le bord gauche. */
    val restCenter: DoubleArray = DoubleArray(size)

    /** Rangée de chaque touche. */
    val rowOf: IntArray = IntArray(size)

    init {
        for (r in layout.rows.indices) {
            var edge = layout.rows[r].leadingInset.toDouble()
            for (i in rowStart[r] until rowStart[r + 1]) {
                rowOf[i] = r
                restCenter[i] = edge + restWidth[i] / 2
                edge += restWidth[i]
            }
        }
    }

    /**
     * Index à plat de la touche [key] de la rangée [row].
     * @throws IndexOutOfBoundsException si la touche n'existe pas.
     */
    fun index(row: Int, key: Int): Int {
        if (row !in layout.rows.indices) throw IndexOutOfBoundsException("Rangée inexistante : $row.")
        if (key !in 0 until rowStart[row + 1] - rowStart[row]) {
            throw IndexOutOfBoundsException("Touche inexistante : $key dans la rangée $row.")
        }
        return rowStart[row] + key
    }
}
