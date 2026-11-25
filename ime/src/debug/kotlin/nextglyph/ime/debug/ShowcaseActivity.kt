package nextglyph.ime.debug

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import nextglyph.ime.R

/**
 * Fausse messagerie sobre qui sert de décor aux visuels des stores (variante debug uniquement, jamais livrée) :
 * quelques bulles déjà présentes, un champ de saisie `showcase_input` qui prend le focus et ouvre le clavier au
 * démarrage. Le test instrumenté `StoreMediaTest` y tape les scénarios de capture ; `docs/PUBLISHING.md` décrit le flux.
 */
class ShowcaseActivity : Activity() {

    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.showcase_activity)
        messages = findViewById(R.id.showcase_messages)
        scroll = findViewById(R.id.showcase_scroll)
        input = findViewById(R.id.showcase_input)

        // Bord à bord imposé dès Android 15 : le décor laisse la place aux barres système et au clavier.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findViewById<LinearLayout>(R.id.showcase_root).setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        addBubble(R.layout.showcase_bubble_received, getString(R.string.showcase_message_1))
        addBubble(R.layout.showcase_bubble_sent, getString(R.string.showcase_message_2))
        addBubble(R.layout.showcase_bubble_received, getString(R.string.showcase_message_3))
        addBubble(R.layout.showcase_bubble_sent, getString(R.string.showcase_message_4))

        findViewById<android.view.View>(R.id.showcase_send).setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) send()
            actionId == EditorInfo.IME_ACTION_SEND
        }
        input.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Le focus est déjà sur le champ : il ne reste qu'à demander le clavier une fois la fenêtre affichée.
        input.post { getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        addBubble(R.layout.showcase_bubble_sent, text)
        input.setText("")
    }

    private fun addBubble(layout: Int, text: String) {
        val bubble = LayoutInflater.from(this).inflate(layout, messages, false) as TextView
        bubble.text = text
        messages.addView(bubble)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
