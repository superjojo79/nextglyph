package nextglyph.engine.session

import nextglyph.engine.edit.AutoCapitalization
import nextglyph.engine.edit.EditConfig
import nextglyph.engine.edit.EditContext
import nextglyph.engine.edit.EditState
import nextglyph.engine.edit.SentenceLanguage
import nextglyph.engine.edit.ShiftState
import nextglyph.engine.edit.SpaceOrigin
import nextglyph.engine.edit.TextEditor
import nextglyph.engine.input.EditOperation
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.input.TextEdit
import nextglyph.engine.input.TextUnits
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.lexicon.Distribution
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.predict.LanguageState
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.Suggestion
import nextglyph.engine.predict.UndoToken
import nextglyph.engine.text.WordPrefix
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Session de saisie (spec §5.4 à §5.9, §9, §10.6, §11.2) : relie les touches à la prédiction
 * ([Predictor]) et à l'édition ([TextEditor]), et applique leurs décisions à l'éditeur du champ
 * ([SessionTarget]). L'IME ne fait que transmettre les événements et afficher [state].
 *
 * - **Mot en cours** en composition (`setComposingText`), validé par `commitText` ; chaque
 *   événement est un seul lot (`beginBatchEdit` / `endBatchEdit`).
 * - **Sélection** : la session suit la position attendue du curseur après ses propres
 *   modifications ; une mise à jour qui ne correspond à aucune d'elles est un déplacement externe
 *   (toucher, autre application) : composition validée, annulation et espace automatique oubliées.
 * - **Validation par espace** (§5.5), annulation par ⌫ immédiat, **barre** (suggestions, puis
 *   ponctuation après une validation, raccourcis en e-mail / URL), **ponctuation** et typographie
 *   selon la langue de la phrase (§5.6), **majuscules** automatiques et manuelles (§5.7),
 *   **langues** : deux lexiques actifs, verrouillage depuis le menu de l'espace (§10.6).
 * - Champs qui ne lisent pas le texte (mot de passe, pavés) : chaque touche est insérée telle
 *   quelle, sans lecture ni suggestion.
 *
 * Non thread-safe : à utiliser depuis le fil de l'interface. Aucun texte n'est journalisé.
 */
class InputSession(
    private val target: SessionTarget,
    private val editor: TextEditor = TextEditor(),
) {
    /** Prédicteur (lexiques chargés), ou `null` tant qu'il n'est pas prêt : saisie sans suggestions. */
    var predictor: Predictor? = null
        private set

    /** Score de langue courant, ou `null` sans prédicteur. */
    var language: LanguageState? = null
        private set

    var field: SessionField = SessionField.DEFAULT
        private set

    /** Ce que le clavier doit afficher. */
    var state: KeyboardState = KeyboardState.INITIAL
        private set

    /** Prévenu à chaque nouvel [state]. */
    var onStateChanged: ((KeyboardState) -> Unit)? = null

    /** Dernière prédiction (mot en cours), pour les mesures et les tests. */
    var prediction: Prediction? = null
        private set

    private var composing = ""
    private var editState = EditState.INITIAL
    private var undo: PendingUndo? = null
    private var manualShift = ShiftState.OFF
    private var languageVersion = 0

    private var cursor = UNKNOWN
    private var selectionLength = 0
    private val pending = ArrayDeque<Int>()

    private class PendingUndo(val token: UndoToken, val language: LanguageState)

    /**
     * Installe le prédicteur ; l'état de langue repart de [preferred] (§10.6), sauf s'il porte
     * déjà sur les mêmes langues.
     */
    fun setPredictor(predictor: Predictor?, preferred: String = LexiconLanguage.FRENCH) {
        this.predictor = predictor
        val current = language
        language = when {
            predictor == null -> null
            current != null && current.languages == predictor.languages -> current
            else -> predictor.initialLanguageState(if (preferred in predictor.languages) preferred else predictor.languages[0])
        }
        languageVersion++
        refresh()
    }

    /** Nouveau champ (ou même champ redémarré), sélection initiale [selStart]–[selEnd] (−1 : inconnue). */
    fun onStartInput(field: SessionField, selStart: Int, selEnd: Int) {
        this.field = field
        composing = ""
        editState = EditState.INITIAL
        undo = null
        manualShift = ShiftState.OFF
        pending.clear()
        cursor = if (selStart >= 0 && selEnd >= 0) minOf(selStart, selEnd) else UNKNOWN
        selectionLength = if (cursor == UNKNOWN) 0 else abs(selEnd - selStart)
        refresh()
    }

    /** Fin de la saisie dans le champ : le mot en cours est validé tel quel. */
    fun onFinishInput() {
        if (composing.isNotEmpty()) target.finishComposingText()
        composing = ""
        undo = null
        pending.clear()
    }

    /**
     * Mise à jour de la sélection par l'éditeur (`onUpdateSelection`). Les mises à jour qui suivent
     * les modifications de la session sont reconnues et ignorées ; toute autre est un déplacement.
     */
    fun onSelectionUpdate(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (selStart == selEnd) {
            val index = pending.indexOf(selStart)
            val expected = index >= 0 || (pending.isEmpty() && (cursor == selStart || cursor == UNKNOWN))
            if (expected) {
                repeat(index + 1) { pending.removeFirst() }
                selectionLength = 0
                if (pending.isEmpty()) {
                    cursor = selStart
                    // À jour : l'application a pu valider elle-même la composition.
                    if (composing.isNotEmpty() && (candidatesStart < 0 || candidatesEnd != selStart)) composing = ""
                }
                return
            }
        }
        pending.clear()
        cursor = if (selStart >= 0 && selEnd >= 0) minOf(selStart, selEnd) else UNKNOWN
        selectionLength = if (cursor == UNKNOWN) 0 else abs(selEnd - selStart)
        if (composing.isNotEmpty()) {
            target.finishComposingText()
            composing = ""
        }
        undo = null
        editState = EditState.INITIAL
        refresh()
    }

    /** Appui validé sur [key] ; [upper] : glissé vers le haut sur une lettre (majuscule, §5.7). */
    fun onKey(key: Key, upper: Boolean = false) {
        when (key.kind) {
            KeyKind.LETTER -> onLetter(key.label, upper)
            KeyKind.CHARACTER -> onCharacter(key.label)
            KeyKind.SPACE -> onSpace()
            KeyKind.BACKSPACE -> onBackspace()
            KeyKind.ENTER -> onEnter()
            // Couches, majuscule du pavé mot de passe, séparateur décimal, emplacements vides :
            // gérés par l'IME (dispositions du profil, M3).
            KeyKind.SYMBOLS, KeyKind.LETTERS, KeyKind.SHIFT, KeyKind.DECIMAL, KeyKind.EMPTY -> Unit
        }
    }

    /** Lettre [letter] (en minuscule, ou variante accentuée), en majuscule si [upper] ou si la majuscule est active. */
    fun onLetter(letter: String, upper: Boolean = false) {
        require(letter.isNotEmpty()) { "Une lettre ne peut pas être vide." }
        undo = null
        editState = EditState.INITIAL
        val capital = upper || state.shift != ShiftState.OFF
        val text = if (capital) letter.uppercase(Locale.ROOT) else letter
        batch { typeIntoWord(text) }
        if (manualShift == ShiftState.ONCE) manualShift = ShiftState.OFF
        refresh()
    }

    /**
     * Caractère qui n'est pas une lettre (couche `?123`, barre de ponctuation, menus) : signe de
     * ponctuation (§5.6, valide d'abord le mot en cours), apostrophe ou trait d'union à l'intérieur
     * d'un mot, chiffre ou symbole inséré tel quel.
     */
    fun onCharacter(text: String) {
        require(text.isNotEmpty()) { "Un caractère ne peut pas être vide." }
        val sign = text.singleOrNull()
        when {
            !field.readsText || sign == null || sign.isDigit() -> plain(text, validate = false)
            sign in JOINERS && composing.isNotEmpty() -> {
                undo = null
                editState = EditState.INITIAL
                batch { typeIntoWord(text) }
                refresh()
            }
            sign in EditConfig.SUPPORTED_SIGNS -> onPunctuation(sign)
            else -> plain(text, validate = true)
        }
    }

    /** Raccourci de la barre (e-mail, URL) : inséré tel quel. */
    fun onShortcut(text: String) = plain(text, validate = false)

    /** Touche espace : validation par espace (§5.5), double espace (§5.6). */
    fun onSpace() {
        undo = null
        if (!field.readsText) {
            plain(" ", validate = false)
            return
        }
        batch {
            val p = currentPrediction()
            val predictor = predictor
            val language = language
            if (p != null && predictor != null && language != null && p.case != SpaceCase.NO_WORD && p.case != SpaceCase.DISABLED) {
                if (p.inserted.last() in JOINERS) {
                    // Élision ou trait d'union final : le mot suivant commence sans espace (§5.8).
                    val commit = predictor.commitBeforePunctuation(p, language, field.prediction)
                    apply(commit.edit)
                    setLanguage(commit.language)
                    editState = EditState.INITIAL
                } else {
                    val commit = predictor.commitSpace(p, language, field.prediction)
                    apply(commit.edit)
                    setLanguage(commit.language)
                    editState = EditState(if (atEnd()) SpaceOrigin.AUTO else SpaceOrigin.TYPED)
                    undo = commit.undo?.let { PendingUndo(it, language) }
                }
            } else {
                finishComposing()
                val result = editor.onSpace(context())
                apply(TextEdit(result.operations))
                editState = result.state
                if (result.operations.any { it is EditOperation.InsertText && it.text.startsWith('.') }) endSentence()
            }
        }
        refresh()
    }

    /** ⌫ : annulation d'une validation automatique (§5.5), sinon effacement d'un caractère. */
    fun onBackspace() {
        val pendingUndo = undo
        undo = null
        editState = EditState.INITIAL
        val predictor = predictor
        if (pendingUndo != null && predictor != null) {
            val edit = predictor.undo(target.textBeforeCursor(LOOKBACK), pendingUndo.token, field.prediction)
            if (edit != null) {
                batch {
                    apply(edit)
                    reopenComposing()
                }
                setLanguage(pendingUndo.language)
                refresh()
                return
            }
        }
        batch {
            when {
                composing.isNotEmpty() -> {
                    val n = TextUnits.lastCodePointLength(composing)
                    composing = composing.dropLast(n)
                    target.setComposingText(composing)
                    move(-n)
                    if (composing.isEmpty()) target.finishComposingText()
                }
                selectionLength > 0 -> {
                    target.commitText("")
                    selectionLength = 0
                }
                else -> {
                    val n = TextUnits.lastCodePointLength(target.textBeforeCursor(2) ?: "")
                    if (n > 0) {
                        target.deleteBefore(n)
                        move(-n)
                    } else {
                        target.sendBackspaceKey()
                        cursor = UNKNOWN
                    }
                    reopenComposing()
                }
            }
        }
        refresh()
    }

    /** Touche Entrée selon le champ : saut de ligne, action de l'éditeur ou touche Entrée. */
    fun onEnter() {
        undo = null
        editState = EditState.INITIAL
        when (val enter = field.enter) {
            EnterBehavior.NewLine -> {
                batch { commit("\n") }
                endSentence()
            }
            is EnterBehavior.EditorAction -> {
                batch { finishComposing() }
                target.performEditorAction(enter.actionId)
            }
            EnterBehavior.EnterKey -> {
                batch { finishComposing() }
                target.sendEnterKey()
                cursor = UNKNOWN
                pending.clear()
            }
        }
        refresh()
    }

    /** Tap sur la case [index] de la barre. */
    fun onBarItem(index: Int) {
        val bar = state.bar
        val item = bar.items.getOrNull(index) ?: return
        when (bar.mode) {
            BarMode.WORDS -> pickSuggestion(item)
            BarMode.PUNCTUATION -> onCharacter(item.text)
            BarMode.SHORTCUTS -> onShortcut(item.text)
            BarMode.EMPTY -> Unit
        }
    }

    /** Menu de l'espace : verrouille [code], ou revient au mode automatique si `null` (§10.6). */
    fun lockLanguage(code: String?) {
        val current = language ?: return
        setLanguage(current.lock(code))
        refresh()
    }

    /** Majuscule verrouillée (`⇪`) ou déverrouillée (§5.7). */
    fun toggleCapsLock() {
        manualShift = if (manualShift == ShiftState.LOCKED) ShiftState.OFF else ShiftState.LOCKED
        refresh()
    }

    /** Majuscule manuelle pour la prochaine lettre, ou annulée si elle était déjà demandée. */
    fun toggleShift() {
        manualShift = if (manualShift == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
        refresh()
    }

    /**
     * Distribution mélangée de la lettre suivante (§10.6) pour l'adaptation visuelle, ou
     * [Distribution.UNKNOWN] sans prédicteur.
     */
    fun distribution(prefix: String): Distribution {
        val predictor = predictor ?: return Distribution.UNKNOWN
        val language = language ?: return Distribution.UNKNOWN
        return predictor.distribution(prefix, language)
    }

    private fun pickSuggestion(item: BarItem) {
        val predictor = predictor ?: return
        val language = language ?: return
        undo = null
        batch {
            val p = currentPrediction() ?: return@batch
            val commit = predictor.pickSuggestion(p, Suggestion(item.text, item.highlighted, item.typed), language, field.prediction)
            apply(commit.edit)
            setLanguage(commit.language)
            editState = EditState(if (atEnd()) SpaceOrigin.AUTO else SpaceOrigin.TYPED)
        }
        refresh()
    }

    private fun onPunctuation(sign: Char) {
        undo = null
        batch {
            validateWord()
            val result = editor.onPunctuation(context(), sign)
            apply(TextEdit(result.operations))
            editState = result.state
        }
        if (sign in SENTENCE_END) endSentence()
        refresh()
    }

    /** Insère [text] ; si [validate], valide d'abord le mot en cours (§5.6). */
    private fun plain(text: String, validate: Boolean) {
        undo = null
        editState = EditState.INITIAL
        batch {
            if (validate) validateWord()
            commit(text)
        }
        refresh()
    }

    /** Valide le mot en cours avant un signe (règles du §5.5, sans espace). */
    private fun validateWord() {
        val p = currentPrediction()
        val predictor = predictor
        val language = language
        if (p != null && predictor != null && language != null) {
            val commit = predictor.commitBeforePunctuation(p, language, field.prediction)
            apply(commit.edit)
            setLanguage(commit.language)
        }
        finishComposing()
    }

    private fun typeIntoWord(text: String) {
        if (field.composes && selectionLength == 0) {
            if (composing.isEmpty()) reopenComposing()
            composing += text
            target.setComposingText(composing)
            move(text.length)
        } else {
            commit(text)
        }
    }

    /** Remet en composition le mot qui précède le curseur, si le curseur est à sa fin. */
    private fun reopenComposing() {
        if (!field.composes || composing.isNotEmpty() || cursor == UNKNOWN || selectionLength > 0) return
        val word = WordPrefix.currentWord(target.textBeforeCursor(LOOKBACK), 0, elisionLanguage()).orEmpty()
        if (word.isEmpty() || word.length > cursor) return
        val next = target.textAfterCursor(1)
        if (!next.isNullOrEmpty() && next[0].isLetterOrDigit()) return
        target.setComposingRegion(cursor - word.length, cursor)
        composing = word
    }

    private fun commit(text: String) {
        finishComposing()
        target.commitText(text)
        selectionLength = 0
        move(text.length)
    }

    private fun finishComposing() {
        if (composing.isEmpty()) return
        target.finishComposingText()
        composing = ""
    }

    private fun apply(edit: TextEdit) {
        if (edit.operations.isEmpty()) return
        finishComposing()
        for (operation in edit.operations) {
            when (operation) {
                is EditOperation.DeleteBeforeCursor -> {
                    target.deleteBefore(operation.count)
                    move(-operation.count)
                }
                is EditOperation.InsertText -> {
                    target.commitText(operation.text)
                    selectionLength = 0
                    move(operation.text.length)
                }
            }
        }
    }

    private fun move(delta: Int) {
        if (cursor != UNKNOWN) cursor = max(0, cursor + delta)
    }

    private inline fun batch(block: () -> Unit) {
        target.beginBatchEdit()
        try {
            block()
        } finally {
            target.endBatchEdit()
        }
        if (cursor != UNKNOWN) {
            pending.addLast(cursor)
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
    }

    private fun atEnd(): Boolean = target.textAfterCursor(1).isNullOrEmpty()

    private fun currentPrediction(): Prediction? {
        val predictor = predictor ?: return null
        val language = language ?: return null
        if (!field.composes || selectionLength > 0) return null
        return predictor.predict(target.textBeforeCursor(LOOKBACK), language, field.prediction)
    }

    private fun context(): EditContext = EditContext(
        textBefore = target.textBeforeCursor(LOOKBACK) ?: "",
        textAfter = target.textAfterCursor(1) ?: "",
        profile = field.textProfile,
        language = sentenceLanguage(),
        capsMode = field.capsMode,
        shift = manualShift,
        state = editState,
    )

    private fun sentenceLanguage(): SentenceLanguage = when (language?.sentenceLanguage) {
        LexiconLanguage.FRENCH -> SentenceLanguage.FRENCH
        LexiconLanguage.ENGLISH -> SentenceLanguage.ENGLISH
        else -> SentenceLanguage.OTHER
    }

    /** Langue dont les élisions sont retirées du mot en cours : le français s'il a un poids non nul. */
    private fun elisionLanguage(): String =
        if ((language?.weight(LexiconLanguage.FRENCH) ?: 0.0) > 0.0) LexiconLanguage.FRENCH else ""

    private fun setLanguage(next: LanguageState) {
        if (next === language) return
        language = next
        languageVersion++
    }

    private fun endSentence() {
        language?.let { setLanguage(it.endSentence()) }
    }

    private fun refresh() {
        state = if (!field.readsText) {
            prediction = null
            KeyboardState(shortcutsOrEmpty(), null, null, manualShift, null, languageVersion)
        } else {
            val before = target.textBeforeCursor(LOOKBACK)
            val lang = language
            val predictor = predictor
            val p = if (predictor != null && lang != null && field.composes && selectionLength == 0) {
                predictor.predict(before, lang, field.prediction)
            } else {
                null
            }
            prediction = p
            val shift = if (before == null) manualShift else AutoCapitalization.shiftFor(before, field.textProfile, field.capsMode, manualShift)
            val prefix = if (field.adaptation && lang != null) WordPrefix.of(before, selectionLength, elisionLanguage()) else null
            KeyboardState(bar(p), p?.spaceLabel?.let { "$SPACE_SYMBOL $it" }, languageLabel(lang), shift, prefix, languageVersion)
        }
        onStateChanged?.invoke(state)
    }

    private fun bar(p: Prediction?): Bar = when {
        field.shortcuts.isNotEmpty() -> shortcutsOrEmpty()
        p != null && !p.bar.isEmpty -> Bar(BarMode.WORDS, listOf(p.bar.left, p.bar.center, p.bar.right).map { it?.toItem() })
        field.composes && editState.trailingSpace == SpaceOrigin.AUTO && p?.word.isNullOrEmpty() ->
            Bar(BarMode.PUNCTUATION, PUNCTUATION_BAR.map { BarItem(it) })
        else -> Bar.EMPTY
    }

    private fun shortcutsOrEmpty(): Bar =
        if (field.shortcuts.isEmpty()) Bar.EMPTY else Bar(BarMode.SHORTCUTS, field.shortcuts.map { BarItem(it) })

    private fun languageLabel(lang: LanguageState?): String? {
        if (lang == null || lang.languages.size < 2 || !field.composes) return null
        val locked = lang.locked
        return if (locked != null) "[${locked.uppercase(Locale.ROOT)}]" else lang.dominant.uppercase(Locale.ROOT)
    }

    private fun Suggestion.toItem() = BarItem(text, highlighted, isTyped)

    companion object {
        /** Caractères demandés avant le curseur. */
        const val LOOKBACK: Int = WordPrefix.LOOKBACK

        /** Signes de la barre de ponctuation (§5.6). */
        val PUNCTUATION_BAR: List<String> = listOf(",", ".", "?", "!", ":", "…", "'")

        /** Préfixe du libellé de l'espace quand elle insérera un autre mot (§5.5). */
        const val SPACE_SYMBOL: String = "␣"

        private const val UNKNOWN = -1
        private const val MAX_PENDING = 32
        private const val JOINERS = "'’-‑"
        private const val SENTENCE_END = ".?!…"
    }
}
