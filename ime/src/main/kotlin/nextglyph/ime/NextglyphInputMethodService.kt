package nextglyph.ime

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import nextglyph.engine.adapt.DistributionSource
import nextglyph.engine.contacts.ContactName
import nextglyph.engine.emoji.EmojiRecents
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.input.PcKey
import nextglyph.engine.input.PcKeyEvent
import nextglyph.engine.input.PcModifier
import nextglyph.engine.input.PcModifiers
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.InMemoryRejectionStore
import nextglyph.engine.profile.DecimalSeparatorPolicy
import nextglyph.engine.profile.FieldKey
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.session.EnterKeyLabel
import nextglyph.engine.session.FieldKeyboard
import nextglyph.engine.session.InputSession
import nextglyph.engine.session.PopupAction
import nextglyph.engine.session.PredictionStack
import nextglyph.engine.session.SessionField
import nextglyph.engine.session.SessionLearning
import nextglyph.engine.session.TapOffset
import nextglyph.ime.contacts.ContactsRepository
import nextglyph.ime.learn.LearnedStore
import nextglyph.ime.lexicon.AssetLexicons
import nextglyph.ime.lexicon.LexiconLoader
import nextglyph.ime.settings.KeyboardSettings
import nextglyph.ime.settings.SettingsActivity
import nextglyph.ime.view.KeyboardView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Service de méthode de saisie : câble la vue ([KeyboardView]) et l'éditeur du champ
 * ([InputConnectionTarget]) à la session de saisie du moteur ([InputSession]), qui décide de tout
 * (spec §4 à §10, §11.2, §13, §14).
 *
 * - Les lexiques des deux langues sont chargés en arrière-plan au démarrage ; le clavier reste neutre
 *   et sans suggestions tant qu'ils ne sont pas prêts.
 * - **Prédicteurs** ([PredictionStack]) : lexiques des langues actives, mots appris (§10.5) et noms des
 *   contacts (§6.3), reconstruits en arrière-plan quand l'un d'eux change, puis installés dans la session.
 * - **Apprentissage** ([LearnedStore]) : chargé au démarrage, enregistré quelques secondes après un
 *   changement et à la sortie du champ, en arrière-plan ; rien n'est appris en incognito, mot de passe,
 *   e-mail, URL ni dans les pavés (`LearningPolicy`).
 * - **Réglages** (§14) : relus et appliqués à chaque modification, sans redémarrer le clavier.
 * - Mot de passe, code PIN et pavés : aucune lecture du champ (sauf le séparateur décimal, §8.1).
 * - Aucun texte saisi ni nom de contact n'est journalisé (§12).
 */
class NextglyphInputMethodService : InputMethodService() {

    private val target = InputConnectionTarget { currentInputConnection }
    private val session = InputSession(target)
    private val source = DistributionSource { prefix -> session.distribution(prefix) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var services: AppServices
    private var settings = KeyboardSettings()
    private var keyboardView: KeyboardView? = null
    private var layoutKind = LayoutKind.QWERTY
    private var lexicons: Map<String, LexiconSet>? = null
    private var worker: ExecutorService? = null
    private var loader: LexiconLoader<Map<String, LexiconSet>>? = null

    /** Génération de la dernière reconstruction demandée : un résultat plus ancien est ignoré. */
    private var stackGeneration = 0
    private var lexiconRevision = Long.MIN_VALUE
    private var learnedRevision = Long.MIN_VALUE
    private var resetLanguage = false

    /** Mémoire volatile du séparateur décimal accepté par chaque champ (§8.1). */
    private var decimalPolicy = DecimalSeparatorPolicy()

    // Champ courant : clavier, comportement de la session et clé (paquet + id) pour le séparateur décimal.
    private var fieldKeyboard = FieldKeyboard.of(SessionField.DEFAULT.profile, layoutKind)
    private var field = SessionField.DEFAULT
    private var fieldKey = FieldKey("", 0)
    private var fieldInputType = 0

    /** Modificateurs collants du panneau PC (§5.10). */
    private val pcModifiers = PcModifiers()

    /** Émojis récents (§5.11), enregistrés dans les préférences ; jamais alimentés sans apprentissage. */
    private var emojiRecents = EmojiRecents()

    private val save = Runnable { services.learned.saveIfChanged() }

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }
    private val learnedListener = LearnedStore.Listener { data -> onLearnedChanged(data) }
    private val contactsListener = ContactsRepository.Listener { _ -> rebuildPredictors() }

    override fun onCreate() {
        super.onCreate()
        services = AppServices.get(this)
        val locale = resources.configuration.locales.get(0)
        decimalPolicy = DecimalSeparatorPolicy(DecimalSeparators.preferred(locale))
        settings = services.settings
        layoutKind = settings.layoutFor(locale?.language)
        fieldKeyboard = FieldKeyboard.of(SessionField.DEFAULT.profile, layoutKind, textRowHeightDp = settings.rowHeightDp.toFloat())
        session.setEditConfig(settings.editConfig)
        session.onStateChanged = { state ->
            keyboardView?.setState(state)
            onLearningEvent()
        }
        session.onWordReplaced = { word -> keyboardView?.announceInserted(word) }
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nextglyph-lexicon").apply { isDaemon = true } }
        worker = executor
        val assets = AssetLexicons(assets)
        val open = { key: String -> assets.openAll(key.split(LANGUAGE_SEPARATOR)) }
        loader = LexiconLoader(open, executor) { handler.post(it) }.also { loader ->
            loader.load(LexiconLanguage.SUPPORTED.joinToString(LANGUAGE_SEPARATOR)) { _, sets ->
                lexicons = sets
                rebuildPredictors()
            }
        }
        services.preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        services.learned.addListener(learnedListener)
        services.contacts.addListener(contactsListener)
        services.learned.load()
        services.syncContacts()
        emojiRecents = EmojiRecents.parse(services.preferences.getString(KEY_EMOJI_RECENTS, null))
    }

    override fun onDestroy() {
        services.preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        services.learned.removeListener(learnedListener)
        services.contacts.removeListener(contactsListener)
        handler.removeCallbacks(save)
        services.learned.saveIfChanged()
        loader?.cancel()
        worker?.shutdownNow()
        decimalPolicy.clear()
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardView(this).apply {
        setKeyboard(fieldKeyboard, EnterKeyLabel.of(field.enter))
        adaptationAllowed = field.adaptation
        adaptationMode = settings.adaptationMode
        haptics = settings.haptics
        bottomMarginDp = settings.bottomMarginDp
        services.learned.data?.let { spatialModel = it.spatial }
        languages = session.predictor?.languages.orEmpty()
        recentEmojis = emojiRecents.list
        listener = object : KeyboardView.Listener {
            override fun onKey(key: Key, upper: Boolean, offset: TapOffset?) = onKeyPressed(key, upper, offset)

            override fun onPopup(action: PopupAction) = when (action) {
                is PopupAction.Text -> onPopupText(action.text)
                is PopupAction.LockLanguage -> session.lockLanguage(action.language)
                PopupAction.CapsLock -> session.toggleCapsLock()
                is PopupAction.ClassicLayout -> switchClassic(action.enabled)
                PopupAction.OpenSettings -> openSettings()
                is PopupAction.SetAdaptation -> savePreference(KeyboardSettings.KEY_ADAPTATION, KeyboardSettings.storedName(action.mode))
                is PopupAction.SetLayout -> savePreference(KeyboardSettings.KEY_LAYOUT, KeyboardSettings.storedName(action.kind))
                // Le panneau PC est une couche de la vue, ouverte par la vue elle-même.
                PopupAction.PcKeys -> Unit
                // Le panneau des émojis aussi.
                PopupAction.EmojiPanel -> Unit
            }

            override fun onEmoji(emoji: String) = onEmojiChosen(emoji)

            override fun onBarItem(index: Int) = session.onBarItem(index)

            override fun onBarItemLongPress(index: Int) = forgetSuggestion(index)
        }
        setSource(if (session.predictor != null) source else null)
        setState(session.state)
        keyboardView = this
    }

    /** Insère [emoji] et, hors incognito et champs sans apprentissage, le place en tête des récents. */
    private fun onEmojiChosen(emoji: String) {
        session.onEmoji(emoji)
        if (!field.profile.learning || field.profile.incognito) return
        emojiRecents.add(emoji)
        services.preferences.edit().putString(KEY_EMOJI_RECENTS, emojiRecents.serialize()).apply()
        keyboardView?.recentEmojis = emojiRecents.list
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // La permission a pu être accordée ou retirée depuis les réglages du système.
        services.syncContacts()
        val key = FieldKey(info.packageName.orEmpty(), info.fieldId)
        // Champ redémarré (même champ, même type) : l'échappatoire choisie reste active.
        val keepClassic = restarting && fieldKeyboard.classic && key == fieldKey && info.inputType == fieldInputType
        fieldKey = key
        fieldInputType = info.inputType
        val profile = InputProfileResolver.resolve(info.inputType, info.imeOptions, info.privateImeOptions)
        val enter = EnterBehaviorResolver.resolve(info.imeOptions, info.inputType)
        fieldKeyboard = FieldKeyboard.of(profile, layoutKind, decimalPolicy.separatorFor(key), settings.rowHeightDp.toFloat())
            .withClassic(keepClassic)
        pcModifiers.reset()
        showModifiers()
        startField(enter, info.initialSelStart, info.initialSelEnd)
    }

    override fun onFinishInput() {
        session.onFinishInput()
        handler.removeCallbacks(save)
        if (::services.isInitialized) services.learned.saveIfChanged()
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
        field = SessionField(fieldKeyboard.profile, enter, settings.autocommit, settings.prudence)
        keyboardView?.let { view ->
            view.setKeyboard(fieldKeyboard, EnterKeyLabel.of(enter))
            view.resetAdaptation()
            view.adaptationAllowed = field.adaptation
        }
        session.onStartInput(field, selStart, selEnd)
    }

    /** Menu rapide `⚙` (issue #27) : ouvre l'activité des réglages. */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Menu rapide `⚙` : enregistre un réglage, appliqué aussitôt par l'écouteur des préférences (§14). */
    private fun savePreference(key: String, value: String) {
        services.preferences.edit().putString(key, value).apply()
    }

    /** Échappatoire « Disposition classique » (§4) : bascule, ou retour à la disposition du champ. */
    private fun switchClassic(enabled: Boolean) {
        val next = fieldKeyboard.withClassic(enabled)
        if (next === fieldKeyboard) return
        session.onFinishInput()
        fieldKeyboard = next
        startField(field.enter, -1, -1)
    }

    private fun onKeyPressed(key: Key, upper: Boolean, offset: TapOffset?) {
        when (key.kind) {
            KeyKind.SHIFT -> session.onShiftKey(SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout().toLong())
            KeyKind.DECIMAL -> onDecimalKey()
            KeyKind.SPACE -> if (upper) session.onSpaceSwipeUp() else session.onKey(key, upper, offset)
            KeyKind.PC -> onPcKey(key)
            else -> if (!sendModified(key)) session.onKey(key, upper, offset)
        }
    }

    /** Touche du panneau PC (§5.10) : événement clavier, ou bascule de `Ctrl` / `Alt`. */
    private fun onPcKey(key: Key) {
        val pc = PcKey.of(key.label) ?: return
        pcModifiers.press(pc, SystemClock.uptimeMillis(), ViewConfiguration.getDoubleTapTimeout().toLong())?.let(::sendKeyEvent)
        showModifiers()
    }

    /** Lettre ou chiffre tapé pendant qu'un modificateur PC est actif : `Ctrl+C`... (§5.10). Vrai si envoyé. */
    private fun sendModified(key: Key): Boolean {
        if (key.kind != KeyKind.LETTER && key.kind != KeyKind.CHARACTER) return false
        val event = key.label.singleOrNull()?.let(pcModifiers::character) ?: return false
        sendKeyEvent(event)
        showModifiers()
        return true
    }

    private fun sendKeyEvent(event: PcKeyEvent) {
        val connection = currentInputConnection ?: return
        // Le mot en cours est validé tel quel : l'événement agit sur le texte réel du champ.
        connection.finishComposingText()
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, event.keyCode, 0, event.meta))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, event.keyCode, 0, event.meta))
    }

    private fun showModifiers() {
        keyboardView?.activeModifiers = PcModifier.entries.filter { pcModifiers.state(it) != PcModifiers.State.OFF }.toSet()
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

    /** Appui long sur une suggestion : la retire du dictionnaire personnel si elle y est (§10.5). */
    private fun forgetSuggestion(index: Int) {
        val data = services.learned.data ?: return
        val item = session.state.bar.items.getOrNull(index) ?: return
        val removed = LexiconLanguage.SUPPORTED.map { data.personal.remove(item.text, it) }.any { it }
        if (removed) {
            Toast.makeText(this, getString(R.string.learned_word_removed, item.text), Toast.LENGTH_SHORT).show()
            onLearningEvent()
        }
    }

    /**
     * Après chaque événement : si l'état appris a changé, enregistrement différé ; si les mots promus ont
     * changé, reconstruction des prédicteurs.
     */
    private fun onLearningEvent() {
        val data = services.learned.data ?: return
        if (data.revision != learnedRevision) {
            learnedRevision = data.revision
            handler.removeCallbacks(save)
            handler.postDelayed(save, SAVE_DELAY_MS)
        }
        if (data.personal.lexiconRevision != lexiconRevision) rebuildPredictors()
    }

    /** État appris chargé, importé ou effacé (§10.5, §12). */
    private fun onLearnedChanged(data: LearnedData) {
        session.learning = SessionLearning(data)
        keyboardView?.spatialModel = data.spatial
        learnedRevision = data.revision
        rebuildPredictors()
    }

    /**
     * Reconstruit les prédicteurs en arrière-plan (lexiques des langues actives, mots appris, contacts) puis
     * les installe ; une reconstruction plus récente rend les précédentes caduques.
     */
    private fun rebuildPredictors() {
        val all = lexicons ?: return
        val executor = worker ?: return
        val base = LinkedHashMap<String, LexiconSet>()
        for (language in settings.languages) all[language]?.let { base[language] = it }
        if (base.isEmpty()) return
        val data = services.learned.data
        lexiconRevision = data?.personal?.lexiconRevision ?: Long.MIN_VALUE
        val personal = data?.personal?.copy()
        val contacts: List<ContactName> = services.contacts.contacts
        val layout = KeyboardLayouts.of(layoutKind)
        val rejections = data?.rejections ?: InMemoryRejectionStore()
        val generation = ++stackGeneration
        executor.execute {
            val stack = PredictionStack.build(base, layout, personal, contacts, rejections = rejections)
            handler.post { if (generation == stackGeneration) installPredictors(stack) }
        }
    }

    /** Prédicteurs prêts : bilinguisme (§10.6) et adaptation par la distribution mélangée. */
    private fun installPredictors(stack: PredictionStack) {
        // Langue préférée modifiée : l'état de langue repart de la nouvelle préférence.
        if (resetLanguage) session.setPredictors(null)
        resetLanguage = false
        session.setPredictors(stack, preferredLanguage())
        keyboardView?.let { view ->
            view.languages = stack.languages
            view.setSource(source)
            view.setState(session.state)
        }
    }

    /** Réglages modifiés (§14) : appliqués aussitôt, sans redémarrer le clavier. */
    private fun applySettings() {
        val previous = settings
        settings = services.settings
        val kind = settings.layoutFor(resources.configuration.locales.get(0)?.language)
        session.setEditConfig(settings.editConfig)
        keyboardView?.let { view ->
            view.adaptationMode = settings.adaptationMode
            view.haptics = settings.haptics
            view.bottomMarginDp = settings.bottomMarginDp
        }
        val kindChanged = kind != layoutKind
        if (kindChanged || settings.rowHeightDp != previous.rowHeightDp ||
            settings.autocommit != previous.autocommit || settings.prudence != previous.prudence
        ) {
            layoutKind = kind
            fieldKeyboard = FieldKeyboard.of(fieldKeyboard.requested, kind, fieldKeyboard.decimalLabel, settings.rowHeightDp.toFloat())
            session.onFinishInput()
            startField(field.enter, -1, -1)
        }
        val languagesChanged = settings.languages != previous.languages || settings.preferredLanguage != previous.preferredLanguage
        if (languagesChanged) resetLanguage = true
        if (kindChanged || languagesChanged) rebuildPredictors()
        if (settings.contacts != previous.contacts) services.syncContacts()
    }

    private fun preferredLanguage(): String {
        val subtype = getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
        return settings.preferredFor(subtype?.languageTag, resources.configuration.locales.get(0)?.toLanguageTag())
    }

    private companion object {
        const val LANGUAGE_SEPARATOR = "+"
        const val SAVE_DELAY_MS = 3000L
        const val KEY_EMOJI_RECENTS = "emoji_recents"
    }
}
