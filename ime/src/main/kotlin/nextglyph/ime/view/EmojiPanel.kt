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
 *
 * Sert aussi au panneau du presse-papiers (issue #35) : catalogue vide, textes copiés à la place des
 * récents, grille de [columns] × [rows] plus larges, textes raccourcis ([fitText]).
 */
class EmojiPanel(
    private val catalog: EmojiCatalog,
    val columns: Int = COLUMNS,
    val rows: Int = ROWS,
    recentsLabel: String = RECENTS_LABEL,
    private val fitText: Boolean = false,
) {
    private val perPage = columns * rows


    /** Ce que produit un relâchement. */
    sealed interface Action {
        data object Letters : Action
        data object Backspace : Action
        data class Insert(val emoji: String) : Action
    }

    /** Récents, du plus récent au plus ancien (fournis par le service). */
    var recents: List<String> = emptyList()

    /** Texte affiché au centre de la grille quand la page est vide (presse-papiers sans texte). */
    var emptyText: String = ""

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
    val tabLabels: List<String> = listOf(LETTERS_LABEL, recentsLabel) + catalog.groups.map { it.emojis.first().text } + BACKSPACE_LABEL

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
    fun pageItems(): List<String> = EmojiPages.page(items(), page, perPage)

    fun pageCount(): Int = EmojiPages.count(items().size, perPage)

    fun tabWidth(): Float = width / tabLabels.size

    fun cellWidth(): Float = width / columns

    fun cellHeight(): Float = gridHeight / rows

    /** Rectangle de l'onglet [i]. */
    fun tabRect(i: Int, out: RectF) = out.set(i * tabWidth(), 0f, (i + 1) * tabWidth(), tabsHeight)

    /** Rectangle de la case [i] de la page. */
    fun cellRect(i: Int, out: RectF) {
        val left = (i % columns) * cellWidth()
        val top = tabsHeight + (i / columns) * cellHeight()
        out.set(left, top, left + cellWidth(), top + cellHeight())
    }

    /** Onglet sous `(x, y)`, ou −1. */
    fun tabAt(x: Float, y: Float): Int = if (y < 0f || y >= tabsHeight || width <= 0f) -1 else (x / tabWidth()).toInt().coerceIn(0, tabLabels.size - 1)

    /** Case de la page sous `(x, y)`, ou −1. */
    fun cellAt(x: Float, y: Float): Int {
        if (y < tabsHeight || y >= tabsHeight + gridHeight || width <= 0f) return -1
        val i = ((y - tabsHeight) / cellHeight()).toInt().coerceIn(0, rows - 1) * columns + (x / cellWidth()).toInt().coerceIn(0, columns - 1)
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
            val text = if (fitText) fit(items[i], cellWidth() * FIT_FRACTION) { labelPaint.measureText(it) } else items[i]
            val paint = if (fitText) labelPaint else emojiPaint
            canvas.drawText(text, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        if (items.isEmpty() && emptyText.isNotEmpty()) {
            canvas.drawText(emptyText, width / 2f, tabsHeight + gridHeight / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
        }
        val pages = pageCount()
        if (pages > 1) {
            val label = "${page + 1}/$pages"
            canvas.drawText(label, width - labelPaint.measureText(label), tabsHeight + gridHeight - labelPaint.descent(), labelPaint)
        }
    }

    companion object {
        /**
         * [text] sur une ligne (sauts de ligne et tabulations remplacés par des espaces), raccourci avec `…`
         * pour tenir dans [maxWidth] selon [measure] ; vide si même `…` ne tient pas.
         */
        fun fit(text: String, maxWidth: Float, measure: (String) -> Float): String {
            val line = text.trim().replace(Regex("\\s+"), " ")
            if (measure(line) <= maxWidth) return line
            var end = line.length
            while (end > 0) {
                // Jamais au milieu d'une paire de substituts (émoji).
                if (Character.isHighSurrogate(line[end - 1])) {
                    end--
                    continue
                }
                val candidate = line.substring(0, end).trimEnd() + ELLIPSIS
                if (measure(candidate) <= maxWidth) return candidate
                end--
            }
            return ""
        }

        private const val ELLIPSIS = "…"

        /** Part de la largeur d'une case occupée par le texte raccourci. */
        private const val FIT_FRACTION = 0.9f

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
