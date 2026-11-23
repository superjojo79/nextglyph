package nextglyph.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeProvider
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.learn.SpatialModel
import nextglyph.engine.stats.KeyHeatmap
import nextglyph.ime.R
import java.text.NumberFormat
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Carte de frappe (issue #46) : dessine la disposition au repos (lettres et rangée du bas) et, sur chaque touche,
 * la vue [HeatmapMode] choisie. Lecture seule : aucune frappe n'y est interprétée, et aucune donnée saisie n'y
 * transite, seulement des compteurs par touche.
 *
 * - **Densité** : les cases de la grille de la touche, d'opacité proportionnelle à leur nombre de frappes.
 * - **Décalages** : un point au centre et une flèche vers le décalage appris, agrandi ([HeatmapStyle.ARROW_SCALE]).
 * - **Erreurs** : touche du neutre au rouge selon sa part d'erreurs, avec le pourcentage ; grise sans assez de frappes.
 *
 * Accessibilité : un nœud virtuel par touche ([KeyboardAccessibility]), décrit par [HeatmapDescriptions].
 */
@SuppressLint("ViewConstructor")
internal class HeatmapView(context: Context) : View(context) {

    private class Node(val rect: KeyRect, val heat: KeyHeatmap.KeyHeat, val offsetX: Float, val offsetY: Float, val learned: Int, val description: String)

    private var layoutKind = LayoutKind.AZERTY
    private var mode = HeatmapMode.DENSITY
    private var maxCell = 0
    private var geometry: KeyboardGeometry? = null
    private var nodes: List<Node> = emptyList()
    private var descriptions: HeatmapDescriptions? = null

    private val density = resources.displayMetrics.density
    private val rowHeightPx = ROW_HEIGHT_DP * density
    private val gapPx = ROW_GAP_DP * density
    private val inset = KEY_INSET_DP * density
    private val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }

    private val canvasColor = resources.getColor(R.color.keyboard_background, context.theme)
    private val keyColor = resources.getColor(R.color.key_background, context.theme)
    private val functionColor = resources.getColor(R.color.key_function_background, context.theme)
    private val labelColor = resources.getColor(R.color.key_label, context.theme)
    private val accentColor = resources.getColor(R.color.heat_density, context.theme)
    private val errorColor = resources.getColor(R.color.heat_error, context.theme)
    private val arrowColor = resources.getColor(R.color.heat_arrow, context.theme)
    private val outlineColor = resources.getColor(R.color.heat_outline, context.theme)
    private val focusColor = resources.getColor(R.color.key_focus_outline, context.theme)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cell = RectF()
    private val box = RectF()

    private val host = object : KeyboardAccessibility.Host {
        override val count: Int get() = nodes.size

        override fun bounds(id: Int, out: Rect): Boolean {
            val node = nodes.getOrNull(id) ?: return false
            out.set(
                node.rect.left.roundToInt(), node.rect.top.roundToInt(),
                node.rect.right.roundToInt(), node.rect.bottom.roundToInt(),
            )
            return true
        }

        override fun description(id: Int): String? = nodes.getOrNull(id)?.description

        override fun state(id: Int): String? = null

        override fun nodeAt(x: Float, y: Float): Int = nodes.indexOfFirst { x >= it.rect.left && x < it.rect.right && y >= it.rect.top && y < it.rect.bottom }

        // La carte est en lecture seule : activer une touche ne fait rien.
        override fun click(id: Int): Boolean = false
    }
    private val accessibility = KeyboardAccessibility(this, host)

    init {
        setBackgroundColor(canvasColor)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /**
     * Met à jour la carte. [spatial] : modèle appris (décalages), `null` tant qu'il n'est pas chargé. [descriptions]
     * compose les textes de TalkBack.
     */
    fun show(layout: LayoutKind, mode: HeatmapMode, heatmap: KeyHeatmap, spatial: SpatialModel?, descriptions: HeatmapDescriptions) {
        this.layoutKind = layout
        this.mode = mode
        this.descriptions = descriptions
        maxCell = heatmap.maxCell
        val byLetter = HashMap<Char, KeyHeatmap.KeyHeat>()
        val byKind = HashMap<KeyKind, KeyHeatmap.KeyHeat>()
        for (heat in heatmap.keys()) {
            val letter = heat.letter
            if (letter != null) byLetter[letter] = heat else byKind[heat.kind] = heat
        }
        this.heats = Heats(byLetter, byKind)
        this.spatial = spatial
        rebuild()
        requestLayout()
        invalidate()
    }

    private class Heats(val byLetter: Map<Char, KeyHeatmap.KeyHeat>, val byKind: Map<KeyKind, KeyHeatmap.KeyHeat>)

    private var heats = Heats(emptyMap(), emptyMap())
    private var spatial: SpatialModel? = null

    private fun heatOf(rect: KeyRect): KeyHeatmap.KeyHeat? = when {
        rect.key.kind == KeyKind.LETTER -> rect.key.label.singleOrNull()?.lowercaseChar()?.let { heats.byLetter[it] }
        else -> heats.byKind[rect.key.kind]
    }

    private fun offsetX(rect: KeyRect): Float {
        val model = spatial ?: return 0f
        return if (rect.key.kind == KeyKind.LETTER) model.offsetX(rect.key.label[0].lowercaseChar()) else model.keyOffsetX(rect.key.kind)
    }

    private fun offsetY(rect: KeyRect): Float {
        val model = spatial ?: return 0f
        return if (rect.key.kind == KeyKind.LETTER) model.offsetY(rect.key.label[0].lowercaseChar()) else model.keyOffsetY(rect.key.kind)
    }

    private fun learnedTaps(rect: KeyRect): Int {
        val model = spatial ?: return 0
        return if (rect.key.kind == KeyKind.LETTER) model.sampleCount(rect.key.label[0].lowercaseChar()) else model.sampleCount(rect.key.kind)
    }

    private fun rebuild() {
        val viewWidth = this.width.toFloat()
        if (viewWidth <= 0f) {
            geometry = null
            nodes = emptyList()
            return
        }
        val geometry = KeyboardGeometry.compute(KeyboardLayouts.of(layoutKind), viewWidth, rowHeightPx, gapPx).also { this.geometry = it }
        val texts = descriptions
        nodes = geometry.rows.flatten().mapNotNull { rect ->
            val heat = heatOf(rect) ?: return@mapNotNull null
            val x = offsetX(rect)
            val y = offsetY(rect)
            val learned = learnedTaps(rect)
            Node(rect, heat, x, y, learned, texts?.describe(heat, mode, x, y, learned).orEmpty())
        }
        accessibility.invalidateNodes()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = KeyboardLayouts.of(layoutKind).rows.size
        val height = (rows * rowHeightPx + (rows - 1) * gapPx).roundToInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        val geometry = geometry ?: return
        for (rect in geometry.rows.flatten()) {
            val heat = heatOf(rect)
            box.set(rect.left + inset, rect.top + inset / 2f, rect.right - inset, rect.bottom - inset / 2f)
            drawKey(canvas, rect, heat)
        }
        val focused = nodes.getOrNull(accessibility.focusedId)
        if (focused != null) {
            stroke.color = focusColor
            stroke.strokeWidth = FOCUS_STROKE_DP * density
            box.set(focused.rect.left + inset, focused.rect.top + inset / 2f, focused.rect.right - inset, focused.rect.bottom - inset / 2f)
            canvas.drawRoundRect(box, CORNER_DP * density, CORNER_DP * density, stroke)
        }
    }

    private fun drawKey(canvas: Canvas, rect: KeyRect, heat: KeyHeatmap.KeyHeat?) {
        val corner = CORNER_DP * density
        val base = if (rect.key.kind == KeyKind.LETTER) keyColor else functionColor
        val rate = if (mode == HeatmapMode.ERRORS) heat?.errorRate else null
        fill.color = when {
            heat == null -> functionColor
            mode == HeatmapMode.ERRORS && rate != null -> HeatmapStyle.blend(base, errorColor, HeatmapStyle.errorLevel(rate) ?: 0f) or OPAQUE
            // Pas assez de frappes pour estimer un taux : touche grise.
            mode == HeatmapMode.ERRORS -> functionColor
            else -> base
        }
        fill.alpha = 255
        canvas.drawRoundRect(box, corner, corner, fill)

        if (heat != null && mode == HeatmapMode.DENSITY) drawDensity(canvas, heat, corner)

        stroke.color = outlineColor
        stroke.strokeWidth = OUTLINE_DP * density
        canvas.drawRoundRect(box, corner, corner, stroke)

        val label = labelOf(rect)
        textPaint.color = labelColor
        textPaint.alpha = 255
        when (mode) {
            HeatmapMode.DENSITY -> {
                textPaint.textSize = rowHeightPx * 0.34f
                canvas.drawText(label, box.centerX(), box.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            }
            HeatmapMode.OFFSETS -> {
                textPaint.textSize = rowHeightPx * 0.22f
                canvas.drawText(label, box.left + textPaint.measureText(label) / 2f + 3 * density, box.top + textPaint.textSize, textPaint)
                if (heat != null) drawOffset(canvas, rect)
            }
            HeatmapMode.ERRORS -> {
                textPaint.textSize = rowHeightPx * 0.28f
                canvas.drawText(label, box.centerX(), box.centerY() - textPaint.textSize * 0.15f, textPaint)
                textPaint.textSize = rowHeightPx * 0.22f
                val value = if (rate == null) DASH else percent.format(rate)
                canvas.drawText(value, box.centerX(), box.bottom - textPaint.textSize * 0.5f, textPaint)
            }
        }
    }

    private fun labelOf(rect: KeyRect): String = when (rect.key.kind) {
        KeyKind.LETTER -> rect.key.label.uppercase()
        KeyKind.SPACE -> SPACE_LABEL
        else -> rect.key.label
    }

    private fun drawDensity(canvas: Canvas, heat: KeyHeatmap.KeyHeat, corner: Float) {
        val cols = KeyHeatmap.COLS
        val rows = KeyHeatmap.ROWS
        val cellWidth = box.width() / cols
        val cellHeight = box.height() / rows
        canvas.save()
        val clip = Path().apply { addRoundRect(box, corner, corner, Path.Direction.CW) }
        canvas.clipPath(clip)
        fill.color = accentColor
        val cells = heat.cells
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val alpha = HeatmapStyle.cellAlpha(cells[row * cols + col], maxCell)
                if (alpha == 0) continue
                fill.alpha = alpha
                cell.set(box.left + col * cellWidth, box.top + row * cellHeight, box.left + (col + 1) * cellWidth, box.top + (row + 1) * cellHeight)
                canvas.drawRect(cell, fill)
            }
        }
        fill.alpha = 255
        canvas.restore()
    }

    private fun drawOffset(canvas: Canvas, rect: KeyRect) {
        val cx = box.centerX()
        val cy = box.centerY() + rowHeightPx * 0.08f
        fill.color = arrowColor
        fill.alpha = 255
        canvas.drawCircle(cx, cy, DOT_DP * density, fill)
        val learned = learnedTaps(rect)
        if (learned > 0) {
            val (dx, dy) = HeatmapStyle.arrow(offsetX(rect), offsetY(rect), box.width(), box.height(), ARROW_MARGIN_DP * density)
            if (HeatmapStyle.isVisibleArrow(dx, dy, MIN_ARROW_DP * density)) {
                stroke.color = arrowColor
                stroke.strokeWidth = ARROW_STROKE_DP * density
                val ex = cx + dx
                val ey = cy + dy
                canvas.drawLine(cx, cy, ex, ey, stroke)
                val length = hypot(dx, dy)
                val ux = dx / length
                val uy = dy / length
                val head = HEAD_DP * density
                // Pointe : deux traits à ±30° de la flèche, repliés vers son origine.
                for (sign in intArrayOf(-1, 1)) {
                    val cos = COS_30
                    val sin = SIN_30 * sign
                    val hx = -(ux * cos - uy * sin) * head
                    val hy = -(ux * sin + uy * cos) * head
                    canvas.drawLine(ex, ey, ex + hx, ey + hy, stroke)
                }
            }
        }
        textPaint.color = labelColor
        textPaint.textSize = rowHeightPx * 0.19f
        textPaint.alpha = 255
        val count = NumberFormat.getIntegerInstance().format(learned)
        canvas.drawText(count, box.centerX(), box.bottom - 2 * density, textPaint)
    }

    // Exploration au toucher (TalkBack) : annonce le nœud survolé, comme le clavier.
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return (manager != null && manager.isTouchExplorationEnabled && accessibility.onHover(event)) || super.dispatchHoverEvent(event)
    }

    private companion object {
        const val ROW_HEIGHT_DP = 60f
        const val ROW_GAP_DP = 2f
        const val KEY_INSET_DP = 2f
        const val CORNER_DP = 6f
        const val OUTLINE_DP = 1.5f
        const val FOCUS_STROKE_DP = 3f
        const val DOT_DP = 2.5f
        const val ARROW_STROKE_DP = 2f
        const val ARROW_MARGIN_DP = 4f
        const val MIN_ARROW_DP = 3f
        const val HEAD_DP = 6f
        const val COS_30 = 0.8660254f
        const val SIN_30 = 0.5f
        const val OPAQUE = -0x1000000
        const val DASH = "—"
        const val SPACE_LABEL = "␣"
    }
}
