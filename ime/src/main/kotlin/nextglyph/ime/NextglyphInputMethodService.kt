package nextglyph.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.input.KeyDispatcher
import nextglyph.engine.layout.LayoutKind
import nextglyph.ime.view.KeyboardView

/** Service de méthode de saisie : affiche le clavier au repos et relaie les appuis à l'éditeur. */
class NextglyphInputMethodService : InputMethodService() {

    private val dispatcher = KeyDispatcher(InputConnectionEditor { currentInputConnection })
    private var enterBehavior: EnterBehavior = EnterBehavior.EnterKey

    override fun onCreateInputView(): View {
        val language = resources.configuration.locales.get(0)?.language
        return KeyboardView(this).apply {
            layoutKind = LayoutKind.defaultForLanguage(language)
            onKeyListener = KeyboardView.OnKeyListener { key -> dispatcher.onKey(key, enterBehavior) }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        enterBehavior = EnterBehaviorResolver.resolve(info.imeOptions, info.inputType)
    }
}
