package nextglyph.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.input.KeyDispatcher
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.text.WordPrefix
import nextglyph.ime.lexicon.AssetLexicons
import nextglyph.ime.lexicon.LexiconLoader
import nextglyph.ime.view.KeyboardView
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Service de méthode de saisie : affiche le clavier adaptatif et relaie les appuis à l'éditeur.
 *
 * - Le lexique de la langue active (sous-type courant : fr ou en) est chargé en arrière-plan ; le
 *   clavier reste neutre tant qu'il n'est pas prêt.
 * - Le préfixe du mot en cours est relu après chaque frappe et à chaque `onUpdateSelection` (curseur
 *   déplacé, sélection, suppression), puis transmis à la vue (spec §5.2, §11.2). Dans les champs
 *   où l'adaptation n'est pas permise (mot de passe, numérique...), le texte n'est jamais lu.
 */
class NextglyphInputMethodService : InputMethodService() {

    private val dispatcher = KeyDispatcher(InputConnectionEditor { currentInputConnection })
    private var enterBehavior: EnterBehavior = EnterBehavior.EnterKey
    private var keyboardView: KeyboardView? = null
    private var adaptationAllowed = true
    private var language = LexiconLanguage.ENGLISH
    private var lexicon: Lexicon? = null
    private var worker: java.util.concurrent.ExecutorService? = null
    private var loader: LexiconLoader? = null

    override fun onCreate() {
        super.onCreate()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nextglyph-lexicon").apply { isDaemon = true } }
        worker = executor
        val handler = Handler(Looper.getMainLooper())
        val lexicons = AssetLexicons(assets)
        loader = LexiconLoader(lexicons::open, executor, Executor { handler.post(it) })
        loadLexicon()
    }

    override fun onDestroy() {
        loader?.cancel()
        worker?.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val systemLanguage = resources.configuration.locales.get(0)?.language
        return KeyboardView(this).apply {
            layoutKind = LayoutKind.defaultForLanguage(systemLanguage)
            onKeyListener = KeyboardView.OnKeyListener { key ->
                dispatcher.onKey(key, enterBehavior)
                refreshPrefix(selectionLength = 0)
            }
            setLexicon(lexicon)
            keyboardView = this
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        enterBehavior = EnterBehaviorResolver.resolve(info.imeOptions, info.inputType)
        adaptationAllowed = AdaptationPolicy.allowsAdaptation(info.inputType)
        loadLexicon()
        keyboardView?.let { view ->
            view.resetAdaptation()
            view.adaptationAllowed = adaptationAllowed
        }
        refreshPrefix(selectionLength = 0)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshPrefix(selectionLength = abs(newSelEnd - newSelStart))
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        loadLexicon(newSubtype)
    }

    /** Charge (en arrière-plan) le lexique de la langue du sous-type courant, s'il a changé. */
    private fun loadLexicon(subtype: InputMethodSubtype? = null) {
        val current = subtype ?: getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
        @Suppress("DEPRECATION")
        val chosen = LexiconLanguage.choose(current?.locale, resources.configuration.locales.get(0)?.toLanguageTag())
        if (chosen != language) {
            // Le lexique de l'ancienne langue ne convient plus : clavier neutre jusqu'au nouveau.
            language = chosen
            lexicon = null
            keyboardView?.setLexicon(null)
        }
        loader?.load(chosen) { loaded, result ->
            if (loaded == language) {
                lexicon = result
                keyboardView?.setLexicon(result)
                refreshPrefix(selectionLength = 0)
            }
        }
    }

    /** Relit le texte avant le curseur et transmet le préfixe du mot en cours à la vue. */
    private fun refreshPrefix(selectionLength: Int) {
        val view = keyboardView ?: return
        if (!adaptationAllowed) {
            view.onPrefix(null)
            return
        }
        val before = currentInputConnection?.getTextBeforeCursor(WordPrefix.LOOKBACK, 0)
        view.onPrefix(WordPrefix.of(before, selectionLength, language))
    }
}
