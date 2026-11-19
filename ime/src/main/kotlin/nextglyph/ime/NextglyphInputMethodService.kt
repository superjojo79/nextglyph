package nextglyph.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import nextglyph.engine.adapt.DistributionSource
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.InMemoryRejectionStore
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.session.InputSession
import nextglyph.engine.session.PopupAction
import nextglyph.engine.session.SessionField
import nextglyph.ime.lexicon.AssetLexicons
import nextglyph.ime.lexicon.LexiconLoader
import nextglyph.ime.view.KeyboardView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Service de méthode de saisie : câble la vue ([KeyboardView]) et l'éditeur du champ
 * ([InputConnectionTarget]) à la session de saisie du moteur ([InputSession]), qui décide de tout
 * (spec §5, §9, §10.6, §11.2).
 *
 * - Les lexiques des deux langues (français : un fichier par licence ; anglais) sont chargés en
 *   arrière-plan au démarrage ; le clavier reste neutre et sans suggestions tant qu'ils ne sont pas
 *   prêts. La langue préférée de départ suit le sous-type, puis la locale système.
 * - Le comportement par champ vient d'[InputProfileResolver] ; dans les champs qui ne lisent pas
 *   le texte (mot de passe, pavés), la disposition reste au repos, sans suggestions (M3 à venir).
 * - Aucun texte saisi n'est journalisé (§12).
 */
class NextglyphInputMethodService : InputMethodService() {

    private val target = InputConnectionTarget { currentInputConnection }
    private val session = InputSession(target)
    private val rejections = InMemoryRejectionStore()
    private val source = DistributionSource { prefix -> session.distribution(prefix) }
    private var keyboardView: KeyboardView? = null
    private var layoutKind = LayoutKind.QWERTY
    private var lexicons: Map<String, LexiconSet>? = null
    private var worker: ExecutorService? = null
    private var loader: LexiconLoader<Map<String, LexiconSet>>? = null

    override fun onCreate() {
        super.onCreate()
        layoutKind = LayoutKind.defaultForLanguage(resources.configuration.locales.get(0)?.language)
        session.onStateChanged = { state -> keyboardView?.setState(state) }
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nextglyph-lexicon").apply { isDaemon = true } }
        worker = executor
        val handler = Handler(Looper.getMainLooper())
        val assets = AssetLexicons(assets)
        val open = { key: String -> assets.openAll(key.split(LANGUAGE_SEPARATOR)) }
        loader = LexiconLoader(open, executor) { handler.post(it) }.also { loader ->
            loader.load(LexiconLanguage.SUPPORTED.joinToString(LANGUAGE_SEPARATOR)) { _, sets ->
                lexicons = sets
                installPredictor()
            }
        }
    }

    override fun onDestroy() {
        loader?.cancel()
        worker?.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardView(this).apply {
        layoutKind = this@NextglyphInputMethodService.layoutKind
        languages = session.predictor?.languages.orEmpty()
        listener = object : KeyboardView.Listener {
            override fun onKey(key: Key, upper: Boolean) = session.onKey(key, upper)

            override fun onPopup(action: PopupAction) = when (action) {
                is PopupAction.Text -> onPopupText(action.text)
                is PopupAction.LockLanguage -> session.lockLanguage(action.language)
                PopupAction.CapsLock -> session.toggleCapsLock()
            }

            override fun onBarItem(index: Int) = session.onBarItem(index)
        }
        setSource(if (session.predictor != null) source else null)
        setState(session.state)
        keyboardView = this
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val profile = InputProfileResolver.resolve(info.inputType, info.imeOptions, info.privateImeOptions)
        val field = SessionField(profile, EnterBehaviorResolver.resolve(info.imeOptions, info.inputType))
        keyboardView?.let { view ->
            view.resetAdaptation()
            view.adaptationAllowed = field.adaptation
        }
        session.onStartInput(field, info.initialSelStart, info.initialSelEnd)
    }

    override fun onFinishInput() {
        session.onFinishInput()
        super.onFinishInput()
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
        session.onSelectionUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    /** Lettre accentuée ou symbole choisi dans un menu d'appui long. */
    private fun onPopupText(text: String) {
        val lower = text.lowercase(Locale.ROOT)
        if (text.length == 1 && text[0].isLetter()) session.onLetter(lower, upper = lower != text) else session.onCharacter(text)
    }

    /** Lexiques chargés : prédicteur bilingue (§10.6) et adaptation par sa distribution mélangée. */
    private fun installPredictor() {
        val sets = lexicons ?: return
        val predictor = Predictor(sets, KeyboardLayouts.of(layoutKind), rejections = rejections)
        session.setPredictor(predictor, preferredLanguage())
        keyboardView?.let { view ->
            view.languages = predictor.languages
            view.setSource(source)
            view.setState(session.state)
        }
    }

    private fun preferredLanguage(): String {
        val subtype = getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
        return LexiconLanguage.choose(subtype?.languageTag, resources.configuration.locales.get(0)?.toLanguageTag())
    }

    private companion object {
        const val LANGUAGE_SEPARATOR = "+"
    }
}
