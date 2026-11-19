package nextglyph.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import nextglyph.engine.adapt.AdaptiveKeyboard
import nextglyph.engine.adapt.TapDecoding
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.lexicon.Lexicon
import nextglyph.ime.R

/**
 * Clavier dessiné sur Canvas : barre de suggestions (vide pour l'instant) puis les rangées, dont les
 * largeurs, opacités et échelles de libellé viennent de [AdaptiveKeyboard] (spec §5.2) : la vue
 * transmet les événements tactiles et le temps, et dessine l'image courante.
 *
 * - **Gel** : chaque pointeur posé ou levé est signalé ; l'image ne change pas tant qu'un doigt est posé.
 * - **Décodage** : chaque appui est décodé par le décodeur de tap (§5.3) sur la disposition affichée et
 *   celle au repos ; sans lexique, λ = 0.
 * - **Accessibilité** (§13) : avec TalkBack (exploration tactile), disposition au repos ; les animations
 *   suivent `ANIMATOR_DURATION_SCALE`.
 * - **Dessin** : aucune allocation dans [onDraw] (peintures, rectangle de travail et tampons préparés
 *   à la création) ; l'animation avance par `postInvalidateOnAnimation`.
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
            keyboard = createKeyboard()
            rebuildGeometry()
            invalidate()
        }

    /** Adaptation permise par le champ de saisie (faux : disposition au repos, aucune lecture de texte). */
    var adaptationAllowed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyRestOnly()
            invalidate()
        }

    private var keyboardLayout: KeyboardLayout = KeyboardLayouts.of(layoutKind)
    private var lexicon: Lexicon? = null
    private var prefix: String? = null
    private var touchExploration = false
    private var animatorScale = 1f
    private var keyboard: AdaptiveKeyboard = createKeyboard()

    private var restGeometry: KeyboardGeometry? = null

    /** Géométrie de l'image affichée, construite au premier doigt posé (l'image est alors gelée). */
    private var displayedGeometry: KeyboardGeometry? = null

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
    private val labelColor = context.getColor(R.color.key_label)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val letterTextPx = textPx(LETTER_TEXT_SP)
    private val letterPaint = labelPaint(letterTextPx)
    private val functionPaint = labelPaint(textPx(FUNCTION_TEXT_SP))
    private val letterBaseline = baselineOffset(letterPaint)
    private val functionBaseline = baselineOffset(functionPaint)
    private val keyRect = RectF()

    /** Touche enfoncée par identifiant de pointeur (multi-touch : deux pouces) : rangée et index, −1 si aucune. */
    private val pressedRow = IntArray(MAX_POINTERS) { NONE }
    private val pressedKey = IntArray(MAX_POINTERS) { NONE }

    private val explorationListener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        touchExploration = enabled
        applyRestOnly()
        invalidate()
    }

    init {
        contentDescription = context.getString(R.string.keyboard_content_description)
    }

    private fun createKeyboard() = AdaptiveKeyboard(keyboardLayout).also { created ->
        created.setLexicon(lexicon, now())
        created.onPrefix(prefix, now())
        created.keyboardWidthDp = if (width > 0) width / density else 0f
        created.setRestOnly(restOnly(), now())
    }

    private fun restOnly() = touchExploration || !adaptationAllowed

    private fun applyRestOnly() = keyboard.setRestOnly(restOnly(), now())

    private fun now() = SystemClock.uptimeMillis()

    private fun textPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    private fun labelPaint(sizePx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textAlign = Paint.Align.CENTER
        textSize = sizePx
    }

    private fun baselineOffset(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return -(metrics.ascent + metrics.descent) / 2f
    }

    /** Installe (ou retire) le lexique de la langue active : l'adaptation et le décodage s'en servent aussitôt. */
    fun setLexicon(lexicon: Lexicon?) {
        this.lexicon = lexicon
        keyboard.setLexicon(lexicon, now())
        invalidate()
    }

    /** Le mot en cours a un nouveau préfixe (`""` : début de mot ; `null` : pas un mot, clavier neutre). */
    fun onPrefix(prefix: String?) {
        this.prefix = prefix
        animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        keyboard.onPrefix(prefix, now())
        invalidate()
    }

    /** Nouveau champ de saisie : disposition au repos immédiate. */
    fun resetAdaptation() {
        prefix = null
        keyboard.reset()
        pressedRow.fill(NONE)
        pressedKey.fill(NONE)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val manager = context.getSystemService(AccessibilityManager::class.java)
        manager?.addTouchExplorationStateChangeListener(explorationListener)
        touchExploration = manager?.isTouchExplorationEnabled == true
        animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        applyRestOnly()
    }

    override fun onDetachedFromWindow() {
        context.getSystemService(AccessibilityManager::class.java)?.removeTouchExplorationStateChangeListener(explorationListener)
        keyboard.cancelPointers(now())
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (KeyboardMetrics.totalHeightDp(keyboardLayout.rows.size) * density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildGeometry()
    }

    private fun rebuildGeometry() {
        pressedRow.fill(NONE)
        pressedKey.fill(NONE)
        displayedGeometry = null
        keyboard.cancelPointers(now())
        keyboard.keyboardWidthDp = if (width > 0) width / density else 0f
        restGeometry = if (width > 0) KeyboardGeometry.compute(keyboardLayout, width.toFloat(), rowHeightPx, rowGapPx) else null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        if (width <= 0) return
        val now = now()
        val pending = keyboard.advance(now, animatorScale)
        val frame = keyboard.frame
        val unit = width / KeyboardLayout.ROW_UNITS
        val rows = keyboardLayout.rows
        for (r in 0 until rows.size) {
            val row = rows[r]
            val keys = row.keys
            val top = barPx + r * (rowHeightPx + rowGapPx)
            var edge = row.leadingInset
            for (k in 0 until keys.size) {
                val left = edge * unit
                edge += frame.width(r, k)
                // La dernière touche rejoint exactement le bord : pas de trou lié aux arrondis.
                val right = if (k == keys.size - 1) width.toFloat() else edge * unit
                drawKey(canvas, keys[k], r, k, left, top, right, frame.opacity(r, k), frame.labelScale(r, k))
            }
        }
        // Le dessin suivant n'est utile que si l'image peut changer : pas pendant le gel (le
        // relâchement du dernier doigt redessine), et seulement à l'échéance d'une mise à jour différée.
        if (keyboard.pointersDown == 0) {
            if (keyboard.isAnimating) {
                postInvalidateOnAnimation()
            } else if (pending) {
                postInvalidateDelayed(maxOf(1L, keyboard.nextUpdateTimeMs - now))
            }
        }
    }

    private fun drawKey(
        canvas: Canvas,
        key: Key,
        row: Int,
        index: Int,
        left: Float,
        top: Float,
        right: Float,
        opacity: Float,
        labelScale: Float,
    ) {
        keyRect.set(left + keyInsetPx, top, right - keyInsetPx, top + rowHeightPx)
        val isLetterLike = key.kind == KeyKind.LETTER || key.kind == KeyKind.SPACE
        val color = when {
            isHeld(row, index) -> pressedColor
            isLetterLike -> keyColor
            else -> functionKeyColor
        }
        fillPaint.color = color
        fillPaint.alpha = (Color.alpha(color) * opacity).toInt()
        canvas.drawRoundRect(keyRect, cornerPx, cornerPx, fillPaint)

        val label = key.label
        if (label.isNotEmpty()) {
            val letter = key.kind == KeyKind.LETTER
            val paint = if (letter) letterPaint else functionPaint
            // Seul le libellé des lettres change d'échelle ; la ligne de base suit la taille du texte.
            val scale = if (letter) labelScale else 1f
            val baseline = (if (letter) letterBaseline else functionBaseline) * scale
            if (letter) paint.textSize = letterTextPx * scale
            paint.alpha = (Color.alpha(labelColor) * KeyStyle.labelOpacity(opacity)).toInt()
            canvas.drawText(label, keyRect.centerX(), keyRect.centerY() + baseline, paint)
        }
    }

    private fun isHeld(row: Int, index: Int): Boolean {
        for (i in pressedRow.indices) if (pressedRow[i] == row && pressedKey[i] == index) return true
        return false
    }

    // Le clavier n'expose pas encore d'actions d'accessibilité par touche (TalkBack : tâche à venir, §13).
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rest = restGeometry ?: return false
        val now = now()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                keyboard.pointerDown(now)
                // Le premier doigt gèle l'image : c'est elle qu'il vise, et que le décodeur utilise.
                if (keyboard.pointersDown == 1 || displayedGeometry == null) displayedGeometry = displayedGeometry(rest)
                val index = event.actionIndex
                val decoded = decode(event.getX(index), event.getY(index), rest)
                press(event.getPointerId(index), decoded?.rowIndex ?: NONE, decoded?.keyIndex ?: NONE)
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    if (id < MAX_POINTERS && pressedRow[id] != NONE) {
                        decode(event.getX(index), event.getY(index), rest)?.let { press(id, it.rowIndex, it.keyIndex) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val key = if (id < MAX_POINTERS) keyUnderPointer(id, event.getX(index), event.getY(index), rest) else null
                if (id < MAX_POINTERS) press(id, NONE, NONE)
                keyboard.pointerUp(now)
                invalidate()
                if (key != null) onKeyListener?.onKey(key)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow.fill(NONE)
                pressedKey.fill(NONE)
                keyboard.cancelPointers(now)
                invalidate()
            }
            else -> return false
        }
        return true
    }

    private fun displayedGeometry(rest: KeyboardGeometry): KeyboardGeometry =
        KeyboardGeometry.compute(keyboardLayout, rest.width, rest.rowHeight, rest.rowGap, keyboard.frame.widthsByRow())

    /** Résultat du décodage au point `(x, y)` de la vue (tampon réutilisé), ou `null` hors du clavier. */
    private fun decode(x: Float, y: Float, rest: KeyboardGeometry): TapDecoding? {
        val displayed = displayedGeometry ?: return null
        return keyboard.decode(x, y - barPx, displayed, rest)
    }

    /** Touche décodée au relèvement, ou à défaut la dernière touche enfoncée par ce pointeur. */
    private fun keyUnderPointer(id: Int, x: Float, y: Float, rest: KeyboardGeometry): Key? {
        val decoded = decode(x, y, rest)
        if (decoded != null) return decoded.key
        val row = pressedRow[id]
        return if (row == NONE) null else keyboardLayout.rows[row].keys[pressedKey[id]]
    }

    private fun press(pointerId: Int, row: Int, index: Int) {
        if (pointerId >= MAX_POINTERS || (pressedRow[pointerId] == row && pressedKey[pointerId] == index)) return
        pressedRow[pointerId] = row
        pressedKey[pointerId] = index
        invalidate()
    }

    private companion object {
        const val MAX_POINTERS = 10
        const val NONE = -1
        const val KEY_INSET_DP = 2f
        const val KEY_CORNER_DP = 6f
        const val LETTER_TEXT_SP = 22f
        const val FUNCTION_TEXT_SP = 15f
    }
}
