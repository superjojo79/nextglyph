package nextglyph.ime.debug

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import nextglyph.ime.R

/**
 * Panel « Champs de test » (spec §15.3), présent seulement dans la variante debug : un `EditText` par cas
 * de [FieldTestCases], suivi d'un écho du texte exact et de la dernière action d'éditeur reçue, pour vérifier
 * la disposition choisie, le texte inséré et la touche `↵` (sur émulateur, avec `uiautomator dump`).
 *
 * L'écho affiche aussi le contenu des champs de mot de passe : c'est un outil de test, jamais livré en release.
 */
class FieldTestsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.field_tests_title)
        val padding = dp(16f)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        for (case in FieldTestCases.ALL) addCase(column, case)
        val scroll = ScrollView(this).apply {
            id = R.id.field_tests_root
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Bord à bord imposé dès Android 15 : le cadre laisse la place aux barres système et au clavier, et la
        // zone de défilement rétrécit d'autant (le champ actif peut alors être amené au-dessus du clavier).
        val frame = FrameLayout(this).apply { addView(scroll) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frame.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                view.post { (view.findFocus() as? EditText)?.let(::revealWithEcho) }
                WindowInsets.CONSUMED
            }
        }
        setContentView(frame)
    }

    private fun addCase(column: LinearLayout, case: FieldTestCase) {
        val label = TextView(this).apply {
            text = case.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val echo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = FieldTestCases.echo(case.key, "", null)
        }
        var lastAction: String? = null
        val field = EditText(this).apply {
            id = case.viewId
            hint = case.label
            inputType = case.inputType
            if (case.pointOnly) {
                // Champ qui refuse la virgule : repli du séparateur décimal sur le point (§8.1). Le filtre
                // change le type annoncé ; on rétablit celui du cas sans toucher au filtre.
                keyListener = DigitsKeyListener.getInstance("0123456789.")
                setRawInputType(case.inputType)
            }
            imeOptions = case.imeOptions
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) view.postDelayed({ if (view.hasFocus()) revealWithEcho(view as EditText) }, REVEAL_DELAY_MS)
            }
            setOnEditorActionListener { view, actionId, _ ->
                lastAction = FieldTestCases.actionName(actionId)
                echo.text = FieldTestCases.echo(case.key, view.text, lastAction)
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable) {
                    echo.text = FieldTestCases.echo(case.key, s, lastAction)
                }
            })
        }
        column.addView(label)
        column.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(echo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12f)
        })
    }

    private fun revealWithEcho(field: EditText) {
        field.requestRectangleOnScreen(Rect(0, -dp(24f), field.width, field.height * 2 + dp(12f)), true)
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private companion object {
        /** Attente de l'apparition du clavier avant de faire défiler le champ actif. */
        const val REVEAL_DELAY_MS = 500L
    }
}
