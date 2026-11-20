package nextglyph.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeProvider
import nextglyph.engine.adapt.AdaptationConfig
import nextglyph.engine.adapt.AdaptationMode
import nextglyph.engine.adapt.AdaptiveKeyboard
import nextglyph.engine.adapt.TapSpatialModel
import nextglyph.engine.adapt.DistributionSource
import nextglyph.engine.adapt.TapDecoding
import nextglyph.engine.edit.ShiftState
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.session.BarItem
import nextglyph.engine.session.BarMode
import nextglyph.engine.session.EnterKeyLabel
import nextglyph.engine.session.FieldKeyboard
import nextglyph.engine.session.KeyPopups
import nextglyph.engine.session.KeyboardState
import nextglyph.engine.session.PopupAction
import nextglyph.engine.session.PopupItem
import nextglyph.engine.session.SessionField
import nextglyph.engine.session.SymbolsMenu
import nextglyph.engine.session.TapOffset
import nextglyph.ime.settings.HapticLevel
import nextglyph.ime.R
import java.util.Locale
import kotlin.math.min

/**
 * Clavier dessiné sur Canvas : barre (suggestions, ponctuation ou raccourcis, §5.4, §5.6, §9.1) puis
 * les rangées de la disposition du champ ([FieldKeyboard] : compacte adaptée §5.2, mot de passe §7.1,
 * pavés §8) ou de sa couche `?123` (au repos). Les pavés n'ont ni barre ni couche ; le mot de passe n'a
 * pas de barre. Une couche plus courte que la disposition principale est alignée en bas (`ABC` à la place
 * de `?123`).
 *
 * - **Adaptation** : largeurs, opacités et échelles de libellé des lettres viennent d'[AdaptiveKeyboard],
 *   alimenté par la source de distributions bilingue de la session (§10.6). Gel tant qu'un doigt est posé.
 * - **Gestes** (§5.9) : glisser vers le haut sur une lettre → majuscule ; appui long → menu (accents,
 *   ponctuation rapide sur `?123`, langues sur l'espace, symboles voisins, `⇪` sur `ABC`), choix en
 *   glissant puis en levant le doigt.
 * - **Accessibilité** (§13) : avec TalkBack, disposition au repos ; animations selon `ANIMATOR_DURATION_SCALE`.
 * - **Dessin** : aucune allocation dans [onDraw] (libellés, tailles et peintures préparés hors du dessin).
 */
class KeyboardView(context: Context) : View(context) {

    /** Événements de la vue, transmis à la session de saisie par le service. */
    interface Listener {
        /**
         * Appui validé sur [key] ; [upper] : glissé vers le haut sur une lettre ou sur l'espace ; [offset] : position de la
         * frappe par rapport à la touche au repos (lettres tapées seulement, modèle spatial §5.3).
         */
        fun onKey(key: Key, upper: Boolean, offset: TapOffset?)

        /** Case choisie dans un menu d'appui long. */
        fun onPopup(action: PopupAction)

        /** Tap sur la case [index] de la barre. */
        fun onBarItem(index: Int)

        /** Appui long sur la case [index] de la barre (§10.5 : supprimer un mot appris). */
        fun onBarItemLongPress(index: Int)
    }

    var listener: Listener? = null

    private var fieldKeyboard: FieldKeyboard = FieldKeyboard.of(SessionField.DEFAULT.profile, LayoutKind.QWERTY)
    private var enterLabel: String = EnterKeyLabel.ENTER

    /** Adaptation permise par le champ de saisie (faux : disposition au repos). */
    var adaptationAllowed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyRestOnly()
            invalidate()
        }

    /** Langues actives (menu de l'espace). */
    var languages: List<String> = emptyList()

    /** Mode d'adaptation (§5.2, §14) ; appliqué aussitôt. */
    var adaptationMode: AdaptationMode = AdaptationMode.CALM_REINFORCED
        set(value) {
            if (field == value) return
            field = value
            letters = createLetters()
            rebuildGeometry()
            invalidate()
        }

    /** Modèle spatial personnalisé (§5.3) lu par le décodeur des lettres. */
    var spatialModel: TapSpatialModel = TapSpatialModel.NONE
        set(value) {
            if (field === value) return
            field = value
            letters = createLetters()
            rebuildGeometry()
            invalidate()
        }

    /** Intensité du retour haptique à l'appui d'une touche (§14). */
    var haptics: HapticLevel = HapticLevel.MEDIUM

    /** Marge basse réglée (§14), ajoutée aux encarts système ; appliquée aussitôt. */
    var bottomMarginDp: Int = 0
        set(value) {
            if (field == value) return
            field = value
            applyBottomPadding()
        }

    /** Encarts système du bas reçus en dernier, en pixels (§13). */
    private var systemBottomInsetsPx: List<Int> = emptyList()

    private val descriptions = KeyDescriptions { id, arg -> if (arg == null) context.getString(id) else context.getString(id, arg) }
    private val hostRef = AccessibilityHost()
    private val accessibility = KeyboardAccessibility(this, hostRef)

    private var lettersLayout: KeyboardLayout = fieldKeyboard.main
    private var symbolsLayout: KeyboardLayout? = fieldKeyboard.symbols
    private var source: DistributionSource? = null
    private var prefix: String? = null
    private var touchExploration = false
    private var animatorScale = 1f
    private var letters: AdaptiveKeyboard = createLetters()
    private var symbols: AdaptiveKeyboard? = createSymbols()
    private var symbolsShown = false

    private val keyboardLayout: KeyboardLayout get() = if (symbolsShown) symbolsLayout ?: lettersLayout else lettersLayout
    private val keyboard: AdaptiveKeyboard get() = if (symbolsShown) symbols ?: letters else letters

    private var restGeometry: KeyboardGeometry? = null
    private var displayedGeometry: KeyboardGeometry? = null

    private val density = resources.displayMetrics.density
    private var barPx = KeyboardMetrics.SUGGESTION_BAR_DP * density
    private var rowHeightPx = fieldKeyboard.rowHeightDp * density

    /** Haut de la première rangée de la couche affichée : sous la barre, couche courte alignée en bas. */
    private var layerTopPx = barPx
    private val rowGapPx = KeyboardMetrics.ROW_GAP_DP * density
    private val keyInsetPx = KEY_INSET_DP * density
    private val cornerPx = KEY_CORNER_DP * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private val backgroundColor = context.getColor(R.color.keyboard_background)
    private val keyColor = context.getColor(R.color.key_background)
    private val functionKeyColor = context.getColor(R.color.key_function_background)
    private val pressedColor = context.getColor(R.color.key_pressed_background)
    private val labelColor = context.getColor(R.color.key_label)
    private val focusColor = context.getColor(R.color.key_focus_outline)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val letterTextPx = textPx(LETTER_TEXT_SP)
    private val functionTextPx = textPx(FUNCTION_TEXT_SP)
    private val barTextPx = textPx(BAR_TEXT_SP)
    private val letterPaint = labelPaint(letterTextPx)
    private val functionPaint = labelPaint(functionTextPx)
    private val smallPaint = labelPaint(textPx(SMALL_TEXT_SP))
    private val barPaint = labelPaint(barTextPx)
    private val barBoldPaint = labelPaint(barTextPx).apply { typeface = Typeface.DEFAULT_BOLD }
    private val letterBaseline = baselineOffset(letterPaint)
    private val functionBaseline = baselineOffset(functionPaint)
    private val keyRect = RectF()
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FOCUS_STROKE_DP * resources.displayMetrics.density
        color = focusColor
    }
    private val focusRect = android.graphics.Rect()

    // Libellés préparés hors du dessin : minuscules et majuscules, par rangée puis par touche.
    private var lowerLabels: Array<Array<String>> = emptyArray()
    private var upperLabels: Array<Array<String>> = emptyArray()
    private var symbolLabels: Array<Array<String>> = emptyArray()

    /** Indices des menus d'appui long (§5.1, issue #21), par rangée et touche ; `null` : pas d'indice. */
    private var mainHints: Array<Array<String?>> = emptyArray()
    private var symbolHints: Array<Array<String?>> = emptyArray()

    // État affiché, issu de la session.
    private var shift = ShiftState.OFF
    private var spaceLabel: String? = null
    private var languageLabel: String? = null
    private var barMode = BarMode.EMPTY
    private var barItems: List<BarItem?> = emptyList()
    private var barTexts: Array<String> = emptyArray()
    private var barScales: FloatArray = FloatArray(0)
    private var languageVersion = -1

    /** Touche enfoncée par identifiant de pointeur : rangée et index, −1 si aucune. */
    private val pressedRow = IntArray(MAX_POINTERS) { NONE }
    private val pressedKey = IntArray(MAX_POINTERS) { NONE }
    private val downKey = arrayOfNulls<Key>(MAX_POINTERS)
    private val downY = FloatArray(MAX_POINTERS)
    private var barPointer = NONE
    private var barPressed = NONE
    private var barLongPressed = false

    // Menu d'appui long.
    private var popupItems: List<PopupItem> = emptyList()
    private var popupPointer = NONE
    private var popupLeft = 0f
    private var popupTop = 0f
    private var popupItemWidth = 0f
    private var popupSelected = NONE
    private var longPressPointer = NONE
    private var longPressKey: Key? = null
    private var longPressX = 0f
    private var longPressY = 0f
    private val longPress = Runnable { openPopup() }

    private val explorationListener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        touchExploration = enabled
        applyRestOnly()
        invalidate()
    }

    init {
        contentDescription = context.getString(R.string.keyboard_content_description)
        prepareLabels()
    }

    private fun createLetters() = AdaptiveKeyboard(lettersLayout, AdaptationConfig(mode = adaptationMode), spatialModel).also { created ->
        created.setSource(source, now())
        created.onPrefix(prefix, now())
        created.keyboardWidthDp = if (width > 0) width / density else 0f
        created.setRestOnly(restOnly(), now())
    }

    private fun createSymbols() = symbolsLayout?.let { layout -> AdaptiveKeyboard(layout).also { it.setRestOnly(true, now()) } }

    private fun restOnly() = touchExploration || !adaptationAllowed || adaptationMode == AdaptationMode.OFF

    private fun applyRestOnly() = letters.setRestOnly(restOnly(), now())

    private fun now() = SystemClock.uptimeMillis()

    private fun textPx(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    private fun labelPaint(sizePx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textAlign = Paint.Align.CENTER
        textSize = sizePx
    }

    /** Décalage de la ligne de base pour centrer le texte verticalement (sans allocation). */
    private fun baselineOffset(paint: Paint): Float = -(paint.ascent() + paint.descent()) / 2f

    private fun prepareLabels() {
        lowerLabels = labels(lettersLayout, upper = false)
        upperLabels = labels(lettersLayout, upper = true)
        symbolLabels = symbolsLayout?.let { labels(it, upper = false) } ?: emptyArray()
        // Mêmes règles que popupFor : pas de menu sur les pavés, pas de menu de `?123` sur la couche.
        val hasMenus = fieldKeyboard.symbols != null
        mainHints = hints(lettersLayout, fieldKeyboard.symbolsMenu, hasMenus)
        symbolHints = symbolsLayout?.let { hints(it, SymbolsMenu.NONE, hasMenus) } ?: emptyArray()
    }

    private fun hints(layout: KeyboardLayout, menu: SymbolsMenu, hasMenus: Boolean): Array<Array<String?>> =
        layout.rows.map { row ->
            Array(row.keys.size) { k -> if (hasMenus) KeyPopups.hint(row.keys[k], menu) else null }
        }.toTypedArray()

    private fun labels(layout: KeyboardLayout, upper: Boolean): Array<Array<String>> = layout.rows.map { row ->
        row.keys.map { key ->
            when {
                key.kind == KeyKind.ENTER -> enterLabel
                upper && key.kind == KeyKind.LETTER -> key.label.uppercase(Locale.ROOT)
                else -> key.label
            }
        }.toTypedArray()
    }.toTypedArray()

    /**
     * Clavier du champ (disposition, couche `?123`, hauteur des rangées, barre, menu de `?123`) et libellé de
     * `↵` (§5.1). Les dispositions ne sont reconstruites que si elles changent ([FieldKeyboard.id]).
     */
    fun setKeyboard(keyboard: FieldKeyboard, enterLabel: String) {
        val layoutsChanged = keyboard.id != fieldKeyboard.id
        val labelChanged = enterLabel != this.enterLabel
        fieldKeyboard = keyboard
        this.enterLabel = enterLabel
        if (layoutsChanged) {
            closePopup()
            cancelPendingPopup()
            letters.cancelPointers(now())
            symbols?.cancelPointers(now())
            symbolsShown = false
            lettersLayout = keyboard.main
            symbolsLayout = keyboard.symbols
            letters = createLetters()
            symbols = createSymbols()
        }
        if (layoutsChanged || labelChanged) prepareLabels()
        if (layoutsChanged) accessibility.invalidateNodes()
        val newBar = if (keyboard.showsBar) KeyboardMetrics.SUGGESTION_BAR_DP * density else 0f
        val newRow = keyboard.rowHeightDp * density
        if (newBar != barPx || newRow != rowHeightPx || layoutsChanged) {
            barPx = newBar
            rowHeightPx = newRow
            rebuildGeometry()
            requestLayout()
        }
        invalidate()
    }

    /** Installe (ou retire) la source des distributions de l'adaptation (§5.2, §10.6). */
    fun setSource(source: DistributionSource?) {
        this.source = source
        letters.setSource(source, now())
        invalidate()
    }

    /** Nouvel état de la session : barre, libellés, majuscule, préfixe de l'adaptation. */
    fun setState(state: KeyboardState) {
        if (touchExploration && (state.shift != shift || state.bar.items != barItems || state.spaceLabel != spaceLabel)) {
            accessibility.invalidateNodes()
        }
        shift = state.shift
        spaceLabel = state.spaceLabel
        languageLabel = state.languageLabel
        if (state.bar.mode != barMode || state.bar.items != barItems) {
            barMode = state.bar.mode
            barItems = state.bar.items
            barTexts = Array(barItems.size) { barItems[it]?.text.orEmpty() }
            barScales = FloatArray(barItems.size)
            barPressed = NONE
            measureBar()
        }
        animatorScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        prefix = state.prefix
        if (state.languageVersion != languageVersion) {
            languageVersion = state.languageVersion
            letters.setSource(source, now())
        }
        letters.onPrefix(state.prefix, now())
        invalidate()
    }

    /** Nouveau champ de saisie : couche lettres, disposition au repos immédiate. */
    fun resetAdaptation() {
        prefix = null
        showSymbols(false)
        letters.reset()
        closePopup()
        pressedRow.fill(NONE)
        pressedKey.fill(NONE)
        invalidate()
    }

    private fun showSymbols(shown: Boolean) {
        if (symbolsShown == shown || (shown && symbolsLayout == null)) return
        keyboard.cancelPointers(now())
        symbolsShown = shown
        rebuildGeometry()
        accessibility.invalidateNodes()
        invalidate()
    }

    /** Annonce [word], inséré par la validation par espace, si TalkBack est actif (§13). */
    fun announceInserted(word: String) {
        if (touchExploration) accessibility.announce(descriptions.inserted(word))
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        (touchExploration && accessibility.onHover(event)) || super.dispatchHoverEvent(event)

    /** Nœuds d'accessibilité : les touches de la couche affichée, puis les cases de la barre. */
    private inner class AccessibilityHost : KeyboardAccessibility.Host {
        private val keyCount: Int get() = keyboardLayout.rows.sumOf { it.keys.size }

        override val count: Int get() = keyCount + if (fieldKeyboard.showsBar) barTexts.size else 0

        private fun keyRow(id: Int): Int {
            var rest = id
            for ((r, row) in keyboardLayout.rows.withIndex()) {
                if (rest < row.keys.size) return r
                rest -= row.keys.size
            }
            return NONE
        }

        private fun keyIndex(id: Int): Int {
            var rest = id
            for (row in keyboardLayout.rows) {
                if (rest < row.keys.size) return rest
                rest -= row.keys.size
            }
            return NONE
        }

        override fun bounds(id: Int, out: android.graphics.Rect): Boolean {
            val rest = restGeometry ?: return false
            if (id < 0 || id >= count) return false
            if (id >= keyCount) {
                val slot = width.toFloat() / barTexts.size
                val i = id - keyCount
                if (barItems.getOrNull(i) == null) return false
                out.set((i * slot).toInt(), 0, ((i + 1) * slot).toInt(), barPx.toInt())
                return true
            }
            val rect = rest.rows[keyRow(id)][keyIndex(id)]
            if (rect.key.kind == KeyKind.EMPTY) return false
            out.set(rect.left.toInt(), (rect.top + layerTopPx).toInt(), rect.right.toInt(), (rect.bottom + layerTopPx).toInt())
            return true
        }

        override fun description(id: Int): String? {
            if (id >= keyCount) return barItems.getOrNull(id - keyCount)?.let { descriptions.suggestion(it.text) }
            val r = keyRow(id)
            if (r == NONE) return null
            val k = keyIndex(id)
            val key = keyboardLayout.rows[r].keys[k]
            val label = if (symbolsShown) symbolLabels[r][k] else lowerLabels[r][k]
            return descriptions.describe(key, label, shift, spaceLabel, enterLabel)
        }

        override fun state(id: Int): String? {
            val r = keyRow(id)
            if (r == NONE) return null
            return if (keyboardLayout.rows[r].keys[keyIndex(id)].kind == KeyKind.SHIFT) descriptions.shiftState(shift) else null
        }

        override fun nodeAt(x: Float, y: Float): Int {
            val rest = restGeometry ?: return View.NO_ID
            if (y < barPx) {
                if (!fieldKeyboard.showsBar || barTexts.isEmpty()) return View.NO_ID
                val i = (x / (width.toFloat() / barTexts.size)).toInt().coerceIn(0, barTexts.size - 1)
                return if (barItems[i] == null) View.NO_ID else keyCount + i
            }
            val rect = rest.keyAt(x, y - layerTopPx) ?: return View.NO_ID
            if (rect.key.kind == KeyKind.EMPTY) return View.NO_ID
            var id = 0
            for (r in 0 until rect.rowIndex) id += keyboardLayout.rows[r].keys.size
            return id + rect.keyIndex
        }

        override fun click(id: Int): Boolean {
            if (id < 0 || id >= count) return false
            if (id >= keyCount) {
                listener?.onBarItem(id - keyCount)
                return true
            }
            val key = keyboardLayout.rows[keyRow(id)].keys[keyIndex(id)]
            if (key.kind == KeyKind.EMPTY) return false
            dispatch(key, false, null)
            return true
        }
    }

    private fun measureBar() {
        if (width <= 0 || barTexts.isEmpty()) return
        val slot = width.toFloat() / barTexts.size - 2 * BAR_PADDING_DP * density
        for (i in barTexts.indices) {
            val measured = barBoldPaint.measureText(barTexts[i])
            barScales[i] = if (measured <= slot || measured == 0f) 1f else maxOf(MIN_BAR_SCALE, slot / measured)
        }
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
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Le padding bas réserve la place de la barre de navigation (fenêtre bord à bord).
        val height = (barPx + keysHeightPx(lettersLayout.rows.size)).toInt() + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        systemBottomInsetsPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Navigation par gestes avec les boutons du clavier : la barre est un encart captionBar() (#20).
            listOf(
                WindowInsets.Type.navigationBars(),
                WindowInsets.Type.captionBar(),
                WindowInsets.Type.tappableElement(),
                WindowInsets.Type.displayCutout(),
            ).map { insets.getInsets(it).bottom }
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetBottom)
        }
        applyBottomPadding()
        return insets
    }

    /** Padding bas : encarts système et marge réglée (§13, §14). */
    private fun applyBottomPadding() {
        val bottom = BottomInset.resolve(systemBottomInsetsPx, Math.round(bottomMarginDp * density))
        if (bottom != paddingBottom) {
            setPadding(paddingLeft, paddingTop, paddingRight, bottom)
            requestLayout()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildGeometry()
        measureBar()
    }

    private fun keysHeightPx(rows: Int): Float = rows * rowHeightPx + (rows - 1) * rowGapPx

    private fun rebuildGeometry() {
        pressedRow.fill(NONE)
        pressedKey.fill(NONE)
        displayedGeometry = null
        keyboard.cancelPointers(now())
        letters.keyboardWidthDp = if (width > 0) width / density else 0f
        layerTopPx = barPx + keysHeightPx(lettersLayout.rows.size) - keysHeightPx(keyboardLayout.rows.size)
        restGeometry = if (width > 0) KeyboardGeometry.compute(keyboardLayout, width.toFloat(), rowHeightPx, rowGapPx) else null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        if (width <= 0) return
        drawBar(canvas)
        val now = now()
        val keyboard = keyboard
        val pending = keyboard.advance(now, animatorScale)
        val frame = keyboard.frame
        val unit = width / KeyboardLayout.ROW_UNITS
        val rows = keyboardLayout.rows
        for (r in 0 until rows.size) {
            val row = rows[r]
            val keys = row.keys
            val top = layerTopPx + r * (rowHeightPx + rowGapPx)
            var edge = row.leadingInset
            for (k in 0 until keys.size) {
                val left = edge * unit
                edge += frame.width(r, k)
                // La dernière touche rejoint exactement le bord : pas de trou lié aux arrondis.
                val right = if (k == keys.size - 1) width.toFloat() else edge * unit
                drawKey(canvas, keys[k], r, k, left, top, right, frame.opacity(r, k), frame.labelScale(r, k))
            }
        }
        drawPopup(canvas)
        drawAccessibilityFocus(canvas)
        // Le dessin suivant n'est utile que si l'image peut changer (pas pendant le gel).
        if (keyboard.pointersDown == 0) {
            if (keyboard.isAnimating) {
                postInvalidateOnAnimation()
            } else if (pending) {
                postInvalidateDelayed(maxOf(1L, keyboard.nextUpdateTimeMs - now))
            }
        }
    }

    private fun drawBar(canvas: Canvas) {
        val count = barTexts.size
        if (count == 0) return
        val slot = width.toFloat() / count
        val pad = BAR_PADDING_DP * density
        for (i in 0 until count) {
            val item = barItems[i] ?: continue
            if (item.highlighted || i == barPressed) {
                keyRect.set(i * slot + pad, pad, (i + 1) * slot - pad, barPx - pad)
                fillPaint.color = pressedColor
                fillPaint.alpha = Color.alpha(pressedColor)
                canvas.drawRoundRect(keyRect, cornerPx, cornerPx, fillPaint)
            }
            val paint = if (item.highlighted || (barMode == BarMode.WORDS && i == CENTER_SLOT)) barBoldPaint else barPaint
            paint.textSize = barTextPx * barScales[i]
            paint.alpha = if (item.typed) KeyStyle.TYPED_ALPHA else Color.alpha(labelColor)
            canvas.drawText(barTexts[i], (i + 0.5f) * slot, barPx / 2f + baselineOffset(paint), paint)
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
        // Emplacement vide d'un pavé : rien n'est dessiné (§8.1).
        if (key.kind == KeyKind.EMPTY) return
        keyRect.set(left + keyInsetPx, top, right - keyInsetPx, top + rowHeightPx)
        val isLetterLike = key.kind == KeyKind.LETTER || key.kind == KeyKind.SPACE || key.kind == KeyKind.CHARACTER ||
            key.kind == KeyKind.DECIMAL
        val color = when {
            isHeld(row, index) -> pressedColor
            // Majuscule active ou verrouillée : `⇧` toujours visible (§7.1).
            key.kind == KeyKind.SHIFT && shift != ShiftState.OFF -> pressedColor
            isLetterLike -> keyColor
            else -> functionKeyColor
        }
        fillPaint.color = color
        fillPaint.alpha = (Color.alpha(color) * opacity).toInt()
        canvas.drawRoundRect(keyRect, cornerPx, cornerPx, fillPaint)

        if (key.kind == KeyKind.SPACE) {
            drawSpace(canvas)
            return
        }
        val label = when {
            key.kind == KeyKind.SHIFT && shift == ShiftState.LOCKED -> CAPS_LOCK
            symbolsShown -> symbolLabels[row][index]
            shift != ShiftState.OFF -> upperLabels[row][index]
            else -> lowerLabels[row][index]
        }
        if (label.isEmpty()) return
        val letter = key.kind == KeyKind.LETTER
        // `⇧` et les libellés d'action de `↵` (flèches) restent lisibles à la taille des lettres.
        val large = letter || key.kind == KeyKind.CHARACTER || key.kind == KeyKind.DECIMAL || key.kind == KeyKind.SHIFT ||
            key.kind == KeyKind.ENTER
        val paint = if (large) letterPaint else functionPaint
        // Seul le libellé des lettres change d'échelle ; la ligne de base suit la taille du texte.
        val scale = if (letter) labelScale else 1f
        val baseline = (if (paint === letterPaint) letterBaseline else functionBaseline) * scale
        paint.textSize = (if (paint === letterPaint) letterTextPx else functionTextPx) * scale
        paint.alpha = (Color.alpha(labelColor) * KeyStyle.labelOpacity(opacity)).toInt()
        canvas.drawText(label, keyRect.centerX(), keyRect.centerY() + baseline, paint)
        // Majuscule verrouillée : affichée en permanence sur `?123` / `ABC` (§5.7).
        if (shift == ShiftState.LOCKED && (key.kind == KeyKind.SYMBOLS || key.kind == KeyKind.LETTERS)) {
            smallPaint.alpha = Color.alpha(labelColor)
            canvas.drawText(CAPS_LOCK, keyRect.right - SMALL_MARGIN_DP * 2 * density, keyRect.top + smallPaint.textSize, smallPaint)
            return
        }
        // Indice du menu d'appui long, dans le coin (contraste ≥ 3:1, comme l'indicateur de langue).
        val hint = (if (symbolsShown) symbolHints else mainHints).getOrNull(row)?.getOrNull(index) ?: return
        smallPaint.alpha = KeyStyle.LANGUAGE_ALPHA
        canvas.drawText(hint, keyRect.right - SMALL_MARGIN_DP * 2 * density, keyRect.top + smallPaint.textSize, smallPaint)
    }

    private fun drawSpace(canvas: Canvas) {
        val label = spaceLabel
        if (label != null) {
            functionPaint.textSize = functionTextPx
            functionPaint.alpha = Color.alpha(labelColor)
            val available = keyRect.width() - 2 * SMALL_MARGIN_DP * density
            val measured = functionPaint.measureText(label)
            if (measured > available) functionPaint.textSize = functionTextPx * maxOf(MIN_BAR_SCALE, available / measured)
            canvas.drawText(label, keyRect.centerX(), keyRect.centerY() + baselineOffset(functionPaint), functionPaint)
            functionPaint.textSize = functionTextPx
        }
        val language = languageLabel ?: return
        smallPaint.alpha = KeyStyle.LANGUAGE_ALPHA
        val y = if (label != null) keyRect.bottom - SMALL_MARGIN_DP * density else keyRect.centerY() + baselineOffset(smallPaint)
        canvas.drawText(language, keyRect.centerX(), y, smallPaint)
    }

    /** Contour de la touche qui a le focus de TalkBack (contraste ≥ 3:1, §13). */
    private fun drawAccessibilityFocus(canvas: Canvas) {
        val id = accessibility.focusedId
        if (id == View.NO_ID || !hostRef.bounds(id, focusRect)) return
        keyRect.set(focusRect)
        canvas.drawRoundRect(keyRect, cornerPx, cornerPx, focusPaint)
    }


    private fun drawPopup(canvas: Canvas) {
        val count = popupItems.size
        if (count == 0) return
        for (i in 0 until count) {
            val left = popupLeft + i * popupItemWidth
            keyRect.set(left, popupTop, left + popupItemWidth, popupTop + rowHeightPx)
            val color = if (i == popupSelected) pressedColor else keyColor
            fillPaint.color = color
            fillPaint.alpha = Color.alpha(color)
            canvas.drawRoundRect(keyRect, cornerPx, cornerPx, fillPaint)
            val label = popupItems[i].label
            val paint = if (label.length <= 2) letterPaint else functionPaint
            paint.textSize = if (paint === letterPaint) letterTextPx else functionTextPx
            // Libellé long (« Classique ») : réduit pour tenir dans la case.
            val available = keyRect.width() - 2 * SMALL_MARGIN_DP * density
            val measured = paint.measureText(label)
            if (measured > available) paint.textSize *= maxOf(MIN_BAR_SCALE, available / measured)
            paint.alpha = Color.alpha(labelColor)
            canvas.drawText(label, keyRect.centerX(), keyRect.centerY() + baselineOffset(paint), paint)
        }
    }

    private fun isHeld(row: Int, index: Int): Boolean {
        for (i in pressedRow.indices) if (pressedRow[i] == row && pressedKey[i] == index) return true
        return false
    }

    // Avec TalkBack, les touches sont des nœuds virtuels activés par ACTION_CLICK (KeyboardAccessibility, §13).
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rest = restGeometry ?: return false
        val now = now()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                if (y < barPx && popupItems.isEmpty() && fieldKeyboard.showsBar) {
                    if (id < MAX_POINTERS && barTexts.isNotEmpty()) {
                        barPointer = id
                        barPressed = (x / (width.toFloat() / barTexts.size)).toInt().coerceIn(0, barTexts.size - 1)
                        barLongPressed = false
                        postDelayed(barLongPress, longPressMs)
                        invalidate()
                    }
                    return true
                }
                // Un deuxième doigt valide le menu ouvert, comme un relâchement.
                if (popupItems.isNotEmpty()) commitPopup()
                keyboard.pointerDown(now)
                if (keyboard.pointersDown == 1 || displayedGeometry == null) displayedGeometry = displayedGeometry(rest)
                val decoded = decode(x, y, rest)
                press(id, decoded?.rowIndex ?: NONE, decoded?.keyIndex ?: NONE)
                if (decoded?.key != null) hapticTap()
                if (id < MAX_POINTERS) {
                    downKey[id] = decoded?.key
                    downY[id] = y
                }
                scheduleLongPress(id, decoded?.key, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    if (id == popupPointer) {
                        val selected = KeyPopups.indexAt(event.getX(index), popupLeft, popupItemWidth, popupItems.size)
                        if (selected != popupSelected) {
                            popupSelected = selected
                            invalidate()
                        }
                        continue
                    }
                    if (id == longPressPointer && movedTooFar(event.getX(index), event.getY(index))) cancelPendingPopup()
                    if (id < MAX_POINTERS && pressedRow[id] != NONE) {
                        decode(event.getX(index), event.getY(index), rest)?.let { press(id, it.rowIndex, it.keyIndex) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                if (id == barPointer) {
                    val item = barPressed
                    barPointer = NONE
                    barPressed = NONE
                    removeCallbacks(barLongPress)
                    invalidate()
                    if (item != NONE && !barLongPressed && event.getY(index) < barPx + rowGapPx) listener?.onBarItem(item)
                    return true
                }
                if (id == longPressPointer) cancelPendingPopup()
                if (id == popupPointer) {
                    if (id < MAX_POINTERS) press(id, NONE, NONE)
                    keyboard.pointerUp(now)
                    commitPopup()
                    return true
                }
                val x = event.getX(index)
                val y = event.getY(index)
                val decoded = if (id < MAX_POINTERS) decode(x, y, rest) else null
                // Position de la frappe pour le modèle spatial : lettres tapées sur la disposition principale.
                val offset = if (decoded?.key?.kind == KeyKind.LETTER && !symbolsShown) {
                    TapOffset.of(x, y - layerTopPx, rest.rows[decoded.rowIndex][decoded.keyIndex])
                } else {
                    null
                }
                val key = decoded?.key ?: if (id < MAX_POINTERS) lastPressedKey(id) else null
                // Glisser vers le haut : majuscule sur une lettre (§5.7), suggestion centrale sur l'espace (§5.9).
                val swipeUp = id < MAX_POINTERS && downKey[id]?.kind?.let { it in SWIPE_UP_KINDS } == true &&
                    downY[id] - y > rowHeightPx * SWIPE_UP_ROWS
                val target = if (swipeUp) downKey[id] else key
                if (id < MAX_POINTERS) {
                    press(id, NONE, NONE)
                    downKey[id] = null
                }
                keyboard.pointerUp(now)
                invalidate()
                if (target != null) dispatch(target, swipeUp, if (swipeUp) null else offset)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow.fill(NONE)
                pressedKey.fill(NONE)
                downKey.fill(null)
                barPointer = NONE
                barPressed = NONE
                removeCallbacks(barLongPress)
                cancelPendingPopup()
                closePopup()
                keyboard.cancelPointers(now)
                invalidate()
            }
            else -> return false
        }
        return true
    }

    private fun dispatch(key: Key, upper: Boolean, offset: TapOffset?) {
        when (key.kind) {
            KeyKind.SYMBOLS -> showSymbols(true)
            KeyKind.LETTERS -> showSymbols(false)
            else -> listener?.onKey(key, upper, offset)
        }
    }

    /** Retour haptique d'un appui (§14), selon l'intensité choisie et le réglage système. */
    private fun hapticTap() {
        val constant = when (haptics) {
            HapticLevel.OFF -> return
            HapticLevel.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
            HapticLevel.MEDIUM -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticLevel.STRONG -> HapticFeedbackConstants.LONG_PRESS
        }
        performHapticFeedback(constant)
    }

    private val barLongPress = Runnable {
        val item = barPressed
        if (item == NONE || barItems.getOrNull(item) == null) return@Runnable
        barLongPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onBarItemLongPress(item)
    }

    private fun scheduleLongPress(id: Int, key: Key?, x: Float, y: Float) {
        cancelPendingPopup()
        if (key == null || popupFor(key).isEmpty()) return
        longPressPointer = id
        longPressKey = key
        longPressX = x
        longPressY = y
        postDelayed(longPress, longPressMs)
    }

    private fun cancelPendingPopup() {
        removeCallbacks(longPress)
        longPressPointer = NONE
        longPressKey = null
    }

    private fun movedTooFar(x: Float, y: Float): Boolean =
        kotlin.math.abs(x - longPressX) > touchSlop * 2 || kotlin.math.abs(y - longPressY) > touchSlop * 2

    private fun popupFor(key: Key): List<PopupItem> {
        // Pas de menu d'appui long sur les pavés (aucune couche) ; menu des langues seulement avec la barre.
        if (fieldKeyboard.symbols == null) return emptyList()
        val menu = if (symbolsShown) SymbolsMenu.NONE else fieldKeyboard.symbolsMenu
        return KeyPopups.forKey(key, shift != ShiftState.OFF, if (fieldKeyboard.showsBar) languages else emptyList(), menu)
    }

    private fun openPopup() {
        val key = longPressKey ?: return
        val items = popupFor(key)
        if (items.isEmpty()) return
        popupItems = items
        popupPointer = longPressPointer
        val wide = items.any { it.label.length > 2 }
        popupItemWidth = min(width.toFloat() / items.size, (if (wide) POPUP_WIDE_DP else POPUP_ITEM_DP) * density)
        // La première case sous le doigt : un appui long relâché sans glisser choisit la variante la plus courante.
        popupLeft = KeyPopups.popupLeft(longPressX + (items.size - 1) * popupItemWidth / 2f, popupItemWidth, items.size, width.toFloat())
        val row = rowOf(longPressY)
        popupTop = maxOf(0f, layerTopPx + row * (rowHeightPx + rowGapPx) - rowHeightPx - rowGapPx)
        popupSelected = KeyPopups.indexAt(longPressX, popupLeft, popupItemWidth, items.size)
        longPressPointer = NONE
        longPressKey = null
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun rowOf(y: Float): Int =
        ((y - layerTopPx) / (rowHeightPx + rowGapPx)).toInt().coerceIn(0, keyboardLayout.rows.size - 1)

    private fun commitPopup() {
        val selected = popupItems.getOrNull(popupSelected)
        closePopup()
        invalidate()
        val action = selected?.action ?: return
        listener?.onPopup(action)
    }

    private fun closePopup() {
        popupItems = emptyList()
        popupPointer = NONE
        popupSelected = NONE
    }

    private fun displayedGeometry(rest: KeyboardGeometry): KeyboardGeometry =
        KeyboardGeometry.compute(keyboardLayout, rest.width, rest.rowHeight, rest.rowGap, keyboard.frame.widthsByRow())

    /** Résultat du décodage au point `(x, y)` de la vue (tampon réutilisé), ou `null` hors du clavier. */
    private fun decode(x: Float, y: Float, rest: KeyboardGeometry): TapDecoding? {
        val displayed = displayedGeometry ?: return null
        return keyboard.decode(x, y - layerTopPx, displayed, rest)
    }

    /** Dernière touche enfoncée par le pointeur [id] (relèvement hors du clavier). */
    private fun lastPressedKey(id: Int): Key? {
        val row = pressedRow[id]
        return if (row == NONE) null else keyboardLayout.rows[row].keys[pressedKey[id]]
    }

    private fun press(pointerId: Int, row: Int, index: Int) {
        if (pointerId >= MAX_POINTERS || (pressedRow[pointerId] == row && pressedKey[pointerId] == index)) return
        pressedRow[pointerId] = row
        pressedKey[pointerId] = index
        invalidate()
    }

    companion object {
        /** Indicateur de la majuscule verrouillée. */
        private const val CAPS_LOCK: String = KeyPopups.CAPS_LOCK_LABEL

        private const val MAX_POINTERS = 10
        private const val NONE = -1
        private const val CENTER_SLOT = 1
        private const val KEY_INSET_DP = 2f
        private const val KEY_CORNER_DP = 6f
        private const val LETTER_TEXT_SP = 22f
        private const val FUNCTION_TEXT_SP = 15f
        private const val SMALL_TEXT_SP = 10f
        private const val BAR_TEXT_SP = 16f
        private const val BAR_PADDING_DP = 3f
        private const val SMALL_MARGIN_DP = 3f
        private const val MIN_BAR_SCALE = 0.6f
        private const val POPUP_ITEM_DP = 40f
        private const val POPUP_WIDE_DP = 64f
        private const val SWIPE_UP_ROWS = 0.6f

        /** Touches qui réagissent au glissement vers le haut. */
        private val SWIPE_UP_KINDS = setOf(KeyKind.LETTER, KeyKind.SPACE)
        private const val FOCUS_STROKE_DP = 3f
    }
}
