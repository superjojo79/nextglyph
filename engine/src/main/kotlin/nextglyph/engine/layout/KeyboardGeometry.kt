package nextglyph.engine.layout

/**
 * Rectangle d'une touche, en pixels, dans le repère de la vue (origine en haut à gauche).
 * [left] inclus, [right] exclu (sauf pour la dernière touche d'une rangée, qui touche le bord).
 */
class KeyRect(
    val rowIndex: Int,
    val keyIndex: Int,
    val key: Key,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    override fun toString(): String = "KeyRect(row=$rowIndex, key=$keyIndex \"${key.label}\", $left,$top,$right,$bottom)"
}

/**
 * Géométrie d'une disposition pour une largeur et une hauteur de rangée données.
 *
 * Les largeurs viennent par défaut du repos ; un solveur (spec §5.2) peut en fournir d'autres
 * pourvu que chaque rangée garde ses [KeyboardLayout.ROW_UNITS] unités, retrait compris.
 * Les rectangles se jointent horizontalement (l'espacement visuel est un détail de dessin) ;
 * les rangées sont séparées par [rowGap].
 */
class KeyboardGeometry private constructor(
    val layout: KeyboardLayout,
    val width: Float,
    val rowHeight: Float,
    val rowGap: Float,
    /** Rectangles par rangée puis par touche. */
    val rows: List<List<KeyRect>>,
) {
    /** Hauteur totale : toutes les rangées et les espacements entre elles. */
    val height: Float = rowHeight * rows.size + rowGap * (rows.size - 1)

    /**
     * Touche située sous le point `(x, y)`, ou `null` hors du clavier.
     * L'espacement entre rangées et le retrait de début de rangée sont rattachés à la touche voisine,
     * si bien qu'aucun tap à l'intérieur du clavier n'est perdu.
     */
    fun keyAt(x: Float, y: Float): KeyRect? {
        if (x < 0f || x > width || y < 0f || y > height) return null
        val rowIndex = minOf((y / (rowHeight + rowGap)).toInt(), rows.size - 1)
        val row = rows[rowIndex]
        return row.firstOrNull { x < it.right } ?: row.last()
    }

    companion object {
        /**
         * Calcule la géométrie.
         *
         * @param widths largeurs en unités, par rangée puis par touche ; par défaut, celles du repos.
         * @throws IllegalArgumentException si une dimension n'est pas strictement positive,
         *   si [rowGap] est négatif, ou si [widths] ne correspond pas à la disposition
         *   (nombre de rangées ou de touches, largeur non positive, somme de rangée différente de 10 u).
         */
        fun compute(
            layout: KeyboardLayout,
            width: Float,
            rowHeight: Float,
            rowGap: Float = 0f,
            widths: List<List<Float>> = layout.restWidths(),
        ): KeyboardGeometry {
            require(width.isFinite() && width > 0f) { "La largeur doit être strictement positive : $width." }
            require(rowHeight.isFinite() && rowHeight > 0f) { "La hauteur de rangée doit être strictement positive : $rowHeight." }
            require(rowGap.isFinite() && rowGap >= 0f) { "L'espacement doit être positif ou nul : $rowGap." }
            require(widths.size == layout.rows.size) { "${widths.size} rangées de largeurs pour ${layout.rows.size} rangées." }

            val unit = width / KeyboardLayout.ROW_UNITS
            val rects = layout.rows.mapIndexed { rowIndex, row ->
                val rowWidths = widths[rowIndex]
                require(rowWidths.size == row.keys.size) {
                    "La rangée $rowIndex a ${row.keys.size} touches mais ${rowWidths.size} largeurs."
                }
                require(rowWidths.all { it.isFinite() && it > 0f }) { "Largeur non positive dans la rangée $rowIndex." }
                val total = row.leadingInset + rowWidths.sum()
                require(KeyboardLayout.isRowSum(total)) { "La rangée $rowIndex fait $total u au lieu de ${KeyboardLayout.ROW_UNITS} u." }

                val top = rowIndex * (rowHeight + rowGap)
                var edge = row.leadingInset
                row.keys.mapIndexed { keyIndex, key ->
                    val left = edge * unit
                    edge += rowWidths[keyIndex]
                    // La dernière touche rejoint exactement le bord : pas de trou lié à la tolérance.
                    val right = if (keyIndex == row.keys.lastIndex) width else edge * unit
                    KeyRect(rowIndex, keyIndex, key, left, top, right, top + rowHeight)
                }
            }
            return KeyboardGeometry(layout, width, rowHeight, rowGap, rects)
        }
    }
}
