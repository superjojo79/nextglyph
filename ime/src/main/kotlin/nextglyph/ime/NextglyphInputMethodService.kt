package nextglyph.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import nextglyph.engine.adapt.DistributionSource
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.InMemoryRejectionStore
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.DecimalSeparatorPolicy
import nextglyph.engine.profile.FieldKey
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.session.EnterKeyLabel
import nextglyph.engine.session.FieldKeyboard
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
 * (spec §4, §5, §7, §8, §9, §10.6, §11.2).
 *
 * - Les lexiques des deux langues (français : un fichier par licence ; anglais) sont chargés en
 *   arrière-plan au démarrage ; le clavier reste neutre et sans suggestions tant qu'ils ne sont pas
 *   prêts. La langue préférée de départ suit le sous-type, puis la locale système.
 * - À chaque `onStartInputView`, [InputProfileResolver] choisit le profil et [FieldKeyboard] la
 *   disposition : compacte, mot de passe (§7), pavés (§8). L'appui long sur `?123` d'un profil texte
 *   propose la disposition classique (§4), et le retour.
 * - Mot de passe, code PIN et pavés : aucune lecture du champ (seule exception : la vérification du
 *   séparateur décimal, §8.1), aucune suggestion, aucun apprentissage.
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

    /** Mémoire volatile du séparateur décimal accepté par chaque champ (§8.1). */
    private var decimalPolicy = DecimalSeparatorPolicy()

    // Champ courant : clavier, comportement de la session et clé (paquet + id) pour le séparateur décimal.
    private var fieldKeyboard = FieldKeyboard.of(SessionField.DEFAULT.profile, layoutKind)
    private var field = SessionField.DEFAULT
    private var fieldKey = FieldKey("", 0)
    private var fieldInputType = 0

    override fun onCreate() {
        super.onCreate()
        val locale = resources.configuration.locales.get(0)
        layoutKind = LayoutKind.defaultForLanguage(locale?.language)
        decimalPolicy = DecimalSeparatorPolicy(DecimalSeparators.preferred(locale))
        fieldKeyboard = FieldKeyboard.of(SessionField.DEFAULT.profile, layoutKind)
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
        decimalPolicy.clear()
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardView(this).apply {
        setKeyboard(fieldKeyboard, EnterKeyLabel.of(field.enter))
        adaptationAllowed = field.adaptation
        languages = session.predictor?.languages.orEmpty()
        listener = object : KeyboardView.Listener {
            override fun onKey(key: Key, upper: Boolean) = onKeyPressed(key, upper)

            override fun onPopup(action: PopupAction) = when (action) {
                is PopupAction.Text -> onPopupText(action.text)
                is PopupAction.LockLanguage -> session.lockLanguage(action.language)
                PopupAction.CapsLock -> session.toggleCapsLock()
                is PopupAction.ClassicLayout -> switchClassic(action.enabled)
            }

            override fun onBarItem(index: Int) = session.onBarItem(index)
        }
        setSource(if (session.predictor != null) source else null)
        setState(session.state)
        keyboardView = this
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val key = FieldKey(info.packageName.orEmpty(), info.fieldId)
        // Champ redémarré (même champ, même type) : l'échappatoire choisie reste active.
        val keepClassic = restarting && fieldKeyboard.classic && key == fieldKey && info.inputType == fieldInputType
        fieldKey = key
        fieldInputType = info.inputType
        val profile = InputProfileResolver.resolve(info.inputType, info.imeOptions, info.privateImeOptions)
        val enter = EnterBehaviorResolver.resolve(info.imeOptions, info.inputType)
        fieldKeyboard = FieldKeyboard.of(profile, layoutKind, decimalPolicy.separatorFor(key)).withClassic(keepClassic)
        startField(enter, info.initialSelStart, info.initialSelEnd)
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

    /** Applique [fieldKeyboard] : comportement de la session, disposition et libellé de `↵`. */
    private fun startField(enter: EnterBehavior, selStart: Int, selEnd: Int) {
        field = SessionField(fieldKeyboard.profile, enter)
        keyboardView?.let { view ->
            view.setKeyboard(fieldKeyboard, EnterKeyLabel.of(enter))
            view.resetAdaptation()
            view.adaptationAllowed = field.adaptation
        }
        session.onStartInput(field, selStart, selEnd)
    }

    /** Échappatoire « Disposition classique » (§4) : bascule, ou retour à la disposition du champ. */
    private fun switchClassic(enabled: Boolean) {
        val next = fieldKeyboard.withClassic(enabled)
        if (next === fieldKeyboard) return
        session.onFinishInput()
        fieldKeyboard = next
        startField(field.enter, -1, -1)
    }

    private fun onKeyPressed(key: Key, upper: Boolean) {
        when (key.kind) {
            KeyKind.SHIFT -> session.onShiftKey(SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout().toLong())
            KeyKind.DECIMAL -> onDecimalKey()
            else -> session.onKey(key, upper)
        }
    }

    private fun onDecimalKey() {
        session.onDecimalSeparator(decimalPolicy, fieldKey)
        // Repli appris (le champ refuse la virgule) : la touche affiche désormais le séparateur accepté.
        val learned = fieldKeyboard.withDecimalLabel(decimalPolicy.separatorFor(fieldKey))
        if (learned !== fieldKeyboard) {
            fieldKeyboard = learned
            keyboardView?.setKeyboard(learned, EnterKeyLabel.of(field.enter))
        }
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
