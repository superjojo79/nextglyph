package nextglyph.ime.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import nextglyph.ime.R

/** Écran de réglages minimal : explique l'activation du clavier et ouvre les écrans système. */
class SettingsActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.settings_status)

        // Bord à bord imposé dès Android 15 : on laisse la place aux barres système (API 30+ pour les insets typés).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findViewById<View>(R.id.settings_root).setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        findViewById<View>(R.id.settings_open).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.settings_choose).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        status.setText(
            if (KeyboardActivation.isEnabled(enabled, packageName)) {
                R.string.settings_status_enabled
            } else {
                R.string.settings_status_disabled
            },
        )
    }
}
