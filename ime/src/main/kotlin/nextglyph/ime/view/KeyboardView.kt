package nextglyph.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.LayoutKind
import nextglyph.ime.R

/**
 * Clavier dessiné sur Canvas : barre de suggestions (vide pour l'instant) puis les rangées
 * de la disposition au repos fournie par `engine`. Aucune allocation dans [onDraw] : peintures,
 * rectangle de travail et géométrie sont préparés à la création et au changement de taille.
 */
class KeyboardView(context: Context) : View(context) {

    /** Reçoit chaque appui validé (relâchement du doigt sur une touche). */
    fun interface OnKeyListener {
        fun onKey(key: Key)
    }

    var onKeyListener: OnKeyListener? = null

    var layoutKind: LayoutKind = LayoutKind.QWERTY
        set(value) {
            if (field == value) return
            field = value
            keyboardLayout = KeyboardLayouts.of(value)
            rebuildGeometry()
            invalidate()
        }

    private var keyboardLayout: KeyboardLayout = KeyboardLayouts.of(layoutKind)
    private var geometry: KeyboardGeometry? = null

    private val density = resources.displayMetrics.density
    private val barPx = KeyboardMetrics.SUGGESTION_BAR_DP * density
    private val rowHeightPx = KeyboardMetrics.DEFAULT_ROW_HEIGHT_DP * density
    private val rowGapPx = KeyboardMetrics.ROW_GAP_DP * density
    private val keyInsetPx = KEY_INSET_DP * density
    private val cornerPx = KEY_CORNER_DP * density

    private val backgroundColor = context.getColor(R.color.keyboard_background)
    private val keyColor = context.getColor(R.color.key_background)
    private val functionKeyColor = context.getColor(R.color.key_function_background)
    private val pressedColor = context.getColor(R.color.key_pressed_background)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val letterPaint = labelPaint(LETTER_TEXT_SP)
    private val functionPaint = labelPaint(FUNCTION_TEXT_SP)
    private val letterBaseline = baselineOffset(letterPaint)
    private val functionBaseline = baselineOffset(functionPaint)
    private val keyRect = RectF()

    /** Touche enfoncée par identifiant de pointeur (multi-touch : deux pouces). */
    private val pressed = arrayOfNulls<KeyRect>(MAX_POINTERS)

    init {
        contentDescription = context.getString(R.string.keyboard_content_description)
    }

    private fun labelPaint(sp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_label)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    }

    private fun baselineOffset(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return -(metrics.ascent + metrics.descent) / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (KeyboardMetrics.totalHeightDp(keyboardLayout.rows.size) * density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        pressed.fill(null)
        geometry = if (width > 0) {
            KeyboardGeometry.compute(keyboardLayout, width.toFloat(), rowHeightPx, rowGapPx)
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        val geometry = geometry ?: return
        val rows = geometry.rows
        for (r in 0 until rows.size) {
            val row = rows[r]
            for (k in 0 until row.size) {
                drawKey(canvas, row[k])
            }
        }
    }

    private fun drawKey(canvas: Canvas, rect: KeyRect) {
        keyRect.set(rect.left + keyInsetPx, rect.top + barPx, rect.right - keyInsetPx, rect.bottom + barPx)
        val isLetterLike = rect.key.kind == KeyKind.LETTER || rect.key.kind == KeyKind.SPACE
        fillPaint.color = when {
            isHeld(rect) -> pressedColor
            isLetterLike -> keyColor
            else -> functionKeyColor
        }
        canvas.drawRoundRect(keyRect, cornerPx, cornerPx, fillPaint)

        val label = rect.key.label
        if (label.isNotEmpty()) {
            val letter = rect.key.kind == KeyKind.LETTER
            canvas.drawText(
                label,
                keyRect.centerX(),
                keyRect.centerY() + if (letter) letterBaseline else functionBaseline,
                if (letter) letterPaint else functionPaint,
            )
        }
    }

    private fun isHeld(rect: KeyRect): Boolean {
        for (i in pressed.indices) if (pressed[i] === rect) return true
        return false
    }

    // Le clavier n'expose pas encore d'actions d'accessibilité par touche (TalkBack : tâche à venir, §13).
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val geometry = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                pressKey(event.getPointerId(index), geometry.keyAt(event.getX(index), event.getY(index) - barPx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    if (id < MAX_POINTERS && pressed[id] != null) {
                        pressKey(id, geometry.keyAt(event.getX(index), event.getY(index) - barPx) ?: pressed[id])
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                if (id < MAX_POINTERS) {
                    val key = geometry.keyAt(event.getX(index), event.getY(index) - barPx) ?: pressed[id]
                    pressKey(id, null)
                    if (key != null) onKeyListener?.onKey(key.key)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed.fill(null)
                invalidate()
            }
            else -> return false
        }
        return true
    }

    private fun pressKey(pointerId: Int, key: KeyRect?) {
        if (pointerId >= MAX_POINTERS || pressed[pointerId] === key) return
        pressed[pointerId] = key
        invalidate()
    }

    private companion object {
        const val MAX_POINTERS = 10
        const val KEY_INSET_DP = 2f
        const val KEY_CORNER_DP = 6f
        const val LETTER_TEXT_SP = 22f
        const val FUNCTION_TEXT_SP = 15f
    }
}
