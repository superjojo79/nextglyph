package nextglyph.ime.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import nextglyph.engine.emoji.EmojiCatalog
import nextglyph.engine.emoji.EmojiPages
import kotlin.math.abs

/**
 * Panneau des émojis (spec §5.11, issue #26), dessiné par [KeyboardView] à la place de la barre et des
 * touches, à hauteur égale :
 *
 * - en haut, à la hauteur de la barre, des onglets : `ABC` (retour aux lettres), récents, un onglet par
 *   groupe Unicode (son premier émoji), `⌫` ;
 * - dessous, une grille de [COLUMNS] × [ROWS] émojis ; glisser horizontalement change de page.
 *
 * Les positions sont en pixels de la vue. Sans état Android : la vue fournit la taille et les pinceaux.
 */
class EmojiPanel(private val catalog: EmojiCatalog) {

    /** Ce que produit un relâchement. */
    sealed interface Action {
        data object Letters : Action
        data object Backspace : Action
        data class Insert(val emoji: String) : Action
    }

    /** Récents, du plus récent au plus ancien (fournis par le service). */
    var recents: List<String> = emptyList()

    /** Onglet affiché : 0 = récents, 1.. = groupes. */
    var tab: Int = 1
        private set

    /** Page affichée dans l'onglet. */
    var page: Int = 0
        private set

    private var width = 0f
    private var tabsHeight = 0f
    private var gridHeight = 0f
    private var downX = 0f
    private var downY = 0f

    /** Libellés des onglets, `ABC` et `⌫` compris. */
    val tabLabels: List<String> = listOf(LETTERS_LABEL, RECENTS_LABEL) + catalog.groups.map { it.emojis.first().text } + BACKSPACE_LABEL

    /** Ouverture : récents s'il y en a, sinon le premier groupe. */
    fun open() {
        tab = if (recents.isNotEmpty() || catalog.groups.isEmpty()) 0 else 1
        page = 0
    }

    fun layout(width: Float, tabsHeight: Float, gridHeight: Float) {
        this.width = width
        this.tabsHeight = tabsHeight
        this.gridHeight = gridHeight
    }

    /** Émojis de l'onglet courant. */
    fun items(): List<String> = if (tab == 0) recents else catalog.groups.getOrNull(tab - 1)?.emojis?.map { it.text }.orEmpty()

    /** Émojis de la page affichée. */
    fun pageItems(): List<String> = EmojiPages.page(items(), page, PER_PAGE)

    fun pageCount(): Int = EmojiPages.count(items().size, PER_PAGE)

    fun tabWidth(): Float = width / tabLabels.size

    fun cellWidth(): Float = width / COLUMNS

    fun cellHeight(): Float = gridHeight / ROWS

    /** Rectangle de l'onglet [i]. */
    fun tabRect(i: Int, out: RectF) = out.set(i * tabWidth(), 0f, (i + 1) * tabWidth(), tabsHeight)

    /** Rectangle de la case [i] de la page. */
    fun cellRect(i: Int, out: RectF) {
        val left = (i % COLUMNS) * cellWidth()
        val top = tabsHeight + (i / COLUMNS) * cellHeight()
        out.set(left, top, left + cellWidth(), top + cellHeight())
    }

    /** Onglet sous `(x, y)`, ou −1. */
    fun tabAt(x: Float, y: Float): Int = if (y < 0f || y >= tabsHeight || width <= 0f) -1 else (x / tabWidth()).toInt().coerceIn(0, tabLabels.size - 1)

    /** Case de la page sous `(x, y)`, ou −1. */
    fun cellAt(x: Float, y: Float): Int {
        if (y < tabsHeight || y >= tabsHeight + gridHeight || width <= 0f) return -1
        val i = ((y - tabsHeight) / cellHeight()).toInt().coerceIn(0, ROWS - 1) * COLUMNS + (x / cellWidth()).toInt().coerceIn(0, COLUMNS - 1)
        return if (i < pageItems().size) i else -1
    }

    fun down(x: Float, y: Float) {
        downX = x
        downY = y
    }

    /** Relâchement en `(x, y)` : change d'onglet ou de page, ou renvoie l'action à exécuter. */
    fun up(x: Float, y: Float): Action? {
        val dx = x - downX
        if (downY >= tabsHeight && abs(dx) > width * SWIPE_FRACTION && abs(dx) > abs(y - downY)) {
            page = (page + if (dx < 0) 1 else -1).coerceIn(0, pageCount() - 1)
            return null
        }
        return tap(downX, downY)
    }

    /** Tap (ou activation d'accessibilité) en `(x, y)`. */
    fun tap(x: Float, y: Float): Action? {
        val t = tabAt(x, y)
        if (t >= 0) return selectTab(t)
        val c = cellAt(x, y)
        return if (c >= 0) Action.Insert(pageItems()[c]) else null
    }

    /** Choix de l'onglet [t] : `ABC` et `⌫` renvoient leur action, les autres changent d'onglet. */
    fun selectTab(t: Int): Action? = when (t) {
        0 -> Action.Letters
        tabLabels.size - 1 -> Action.Backspace
        else -> {
            tab = t - 1
            page = 0
            null
        }
    }

    /** Dessin : onglets (l'onglet courant surligné), page, numéro de page. */
    fun draw(canvas: Canvas, rect: RectF, emojiPaint: Paint, labelPaint: Paint, highlight: Paint, corner: Float) {
        for (i in tabLabels.indices) {
            tabRect(i, rect)
            if (i == tab + 1) canvas.drawRoundRect(rect, corner, corner, highlight)
            val paint = if (i == 0 || i == tabLabels.size - 1) labelPaint else emojiPaint
            canvas.drawText(tabLabels[i], rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        val items = pageItems()
        for (i in items.indices) {
            cellRect(i, rect)
            canvas.drawText(items[i], rect.centerX(), rect.centerY() - (emojiPaint.ascent() + emojiPaint.descent()) / 2f, emojiPaint)
        }
        val pages = pageCount()
        if (pages > 1) {
            val label = "${page + 1}/$pages"
            canvas.drawText(label, width - labelPaint.measureText(label), tabsHeight + gridHeight - labelPaint.descent(), labelPaint)
        }
    }

    companion object {
        const val COLUMNS: Int = 8
        const val ROWS: Int = 3
        const val PER_PAGE: Int = COLUMNS * ROWS
        const val LETTERS_LABEL: String = "ABC"
        const val RECENTS_LABEL: String = "🕘"
        const val BACKSPACE_LABEL: String = "⌫"

        /** Glissement horizontal minimal pour changer de page, en fraction de la largeur. */
        const val SWIPE_FRACTION: Float = 0.2f
    }
}
