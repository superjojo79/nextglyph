package nextglyph.engine.session

import nextglyph.engine.contacts.ContactContext
import nextglyph.engine.edit.AutoCapitalization
import nextglyph.engine.edit.EditConfig
import nextglyph.engine.edit.EditContext
import nextglyph.engine.edit.EditState
import nextglyph.engine.edit.SentenceLanguage
import nextglyph.engine.edit.ShiftState
import nextglyph.engine.edit.SpaceOrigin
import nextglyph.engine.edit.TextEditor
import nextglyph.engine.adapt.TapSpatialModel
import nextglyph.engine.input.EditOperation
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.input.TextEdit
import nextglyph.engine.input.TextUnits
import nextglyph.engine.input.WordDeletion
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.lexicon.Distribution
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.ngram.NextWordContext
import nextglyph.engine.ngram.NextWordPredictor
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.LanguageState
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.Suggestion
import nextglyph.engine.predict.SuggestionBar
import nextglyph.engine.predict.TypedTap
import nextglyph.engine.predict.UndoToken
import nextglyph.engine.predict.WordTaps
import nextglyph.engine.profile.DecimalSeparatorPolicy
import nextglyph.engine.profile.FieldKey
import nextglyph.engine.profile.SeparatorEditor
import nextglyph.engine.learn.LearningPolicy
import nextglyph.engine.learn.SpatialModel
import nextglyph.engine.stats.KeyHeatmap
import nextglyph.engine.stats.TypingStats
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
    private var editor: TextEditor = TextEditor(),
) {
    /** Prédicteur de base (lexiques chargés), ou `null` tant qu'il n'est pas prêt : saisie sans suggestions. */
    var predictor: Predictor? = null
        private set

    /** Prédicteurs par niveau de contexte des contacts (§6.3), ou `null` tant qu'ils ne sont pas prêts. */
    var predictors: PredictionStack? = null
        private set

    /**
     * Prédicteur du mot suivant (issue #51), ou `null` : pas de modèle, la barre garde alors la ponctuation. Il ne
     * retient rien : le texte qui précède le curseur est lu à chaque rafraîchissement.
     */
    var nextWords: NextWordPredictor? = null
        private set

    // La barre affichée propose les mots suivants (et non les suggestions du mot en cours) : un tap les insère.
    private var nextWordBar = false

    /** Mesures locales (issue #36), ou `null` ; alimentées seulement dans les champs qui apprennent (§9.2). */
    var stats: TypingStats? = null

    private val measuring: TypingStats? get() = stats?.takeIf { this.field.profile.learning && !this.field.profile.incognito }

    /** Apprentissage (§5.3, §10.5), ou `null` : rien n'est appris (données pas encore chargées). */
    var learning: SessionLearning? = null

    /**
     * Carte de frappe (§15.5) : compteurs par touche, jamais de texte ; `null` pour ne rien compter. Alimentée
     * seulement là où [LearningPolicy.allowsTaps] est vrai (pas en incognito, mot de passe, code PIN, pavés).
     */
    var heatmap: KeyHeatmap? = null

    // Frappe de touche déjà comptée par `onKey`, qui appelle ensuite `onSpace` ou `onEnter` : évite un marqueur en double.
    private var tapCounted = false

    private val heat: KeyHeatmap? get() = heatmap?.takeIf { LearningPolicy.allowsTaps(this.field.prediction) }

    /**
     * Prévenu quand la validation par espace insère un autre mot que le texte tapé (§13 : annoncé par
     * TalkBack). Le texte n'est transmis qu'à l'IME, jamais journalisé.
     */
    var onWordReplaced: ((String) -> Unit)? = null

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

    // Frappes du mot en cours (issue #53) : en mémoire seulement, transmises à la seule prédiction.
    private val wordTaps = ArrayList<TypedTap?>()
    private var editState = EditState.INITIAL
    private var undo: PendingUndo? = null
    private var manualShift = ShiftState.OFF
    private var languageVersion = 0
    private var lastShiftTapMs: Long? = null

    // Majuscule initiale du mot en cours : due au début de phrase, ou tapée par l'utilisateur (§6.3, §10.5).
    private var wordAutoCapital = false
    private var wordTypedCapital = false

    private var cursor = UNKNOWN
    private var selectionLength = 0
    private val pending = ArrayDeque<Int>()

    private class PendingUndo(val token: UndoToken, val language: LanguageState)

    /**
     * Installe le prédicteur ; l'état de langue repart de [preferred] (§10.6), sauf s'il porte
     * déjà sur les mêmes langues.
     */
    fun setPredictor(predictor: Predictor?, preferred: String = LexiconLanguage.FRENCH) =
        setPredictors(predictor?.let(PredictionStack::of), preferred)

    /**
     * Installe les prédicteurs (lexiques, mots appris, contacts) ; l'état de langue repart de [preferred],
     * sauf s'il porte déjà sur les mêmes langues.
     */
    fun setPredictors(stack: PredictionStack?, preferred: String = LexiconLanguage.FRENCH) {
        val predictor = stack?.base
        this.predictors = stack
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

    /**
     * Installe les modèles de mot suivant (issue #51) par code de langue (`fr`, `en`), ou les retire si [models] est
     * vide. Sans modèle d'une langue active, cette langue ne propose rien. Le réglage « Prédiction du mot suivant »
     * se règle à chaque champ par [SessionField].
     */
    fun setNextWordModels(models: Map<String, NgramModel>) {
        nextWords = if (models.isEmpty()) null else NextWordPredictor(models)
        refresh()
    }

    /** Réglages de l'édition (§14 : typographie française, double espace), appliqués aussitôt. */
    fun setEditConfig(config: EditConfig) {
        editor = TextEditor(config)
        refresh()
    }

    /** Nouveau champ (ou même champ redémarré), sélection initiale [selStart]–[selEnd] (−1 : inconnue). */
    fun onStartInput(field: SessionField, selStart: Int, selEnd: Int) {
        learning?.onWordAbandoned()
        heatmap?.forgetRecent()
        this.field = field
        composing = ""
        editState = EditState.INITIAL
        undo = null
        manualShift = ShiftState.OFF
        lastShiftTapMs = null
        pending.clear()
        cursor = if (selStart >= 0 && selEnd >= 0) minOf(selStart, selEnd) else UNKNOWN
        selectionLength = if (cursor == UNKNOWN) 0 else abs(selEnd - selStart)
        refresh()
    }

    /** Fin de la saisie dans le champ : le mot en cours est validé tel quel. */
    fun onFinishInput() {
        learning?.onWordAbandoned()
        heatmap?.forgetRecent()
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
        learning?.onWordAbandoned()
        heatmap?.forgetRecent()
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

    /**
     * Appui validé sur [key] ; [upper] : glissé vers le haut sur une lettre (majuscule, §5.7) ; [offset] :
     * position de la frappe par rapport à la touche au repos (modèle spatial, §5.3), `null` si inconnue.
     *
     * Pour toute touche qui n'est pas une lettre, la frappe est d'abord signalée à l'apprentissage
     * ([SessionLearning.onKeyTap], touches de fonction apprises : espace, entrée, ⌫, `?123`), avant d'être
     * exécutée. Seul `onKey` rapporte des frappes : les appels directs à [onSpace], [onBackspace] ou
     * [onEnter] (glissés, gestes, répétition) n'en rapportent aucune.
     */
    fun onKey(key: Key, upper: Boolean = false, offset: TapOffset? = null) {
        if (key.kind != KeyKind.LETTER) learning?.onKeyTap(key.kind, offset, field.prediction)
        if (offset != null && key.kind in HEAT_KEYS && key.kind != KeyKind.SYMBOLS) {
            tapCounted = heat?.onTap(key.kind, offset.dx, offset.dy, field.prediction) ?: false
        }
        when (key.kind) {
            KeyKind.LETTER -> onLetter(key.label, upper, offset)
            KeyKind.CHARACTER -> onCharacter(key.label)
            KeyKind.SPACE -> onSpace()
            KeyKind.BACKSPACE -> onBackspace()
            KeyKind.ENTER -> onEnter()
            // Couches, majuscule du pavé mot de passe, séparateur décimal, emplacements vides :
            // gérés par l'IME (dispositions du profil, M3). Touches PC : événements clavier envoyés par l'IME (§5.10).
            KeyKind.SYMBOLS, KeyKind.LETTERS, KeyKind.SHIFT, KeyKind.DECIMAL, KeyKind.EMPTY, KeyKind.PC, KeyKind.NAV -> Unit
        }
        tapCounted = false
    }

    /**
     * Changement de couche par [key] (`?123` ou `ABC`), géré par la vue et non par la session : informe
     * seulement l'apprentissage ([SessionLearning.onKeyTap]) ; [offset] : position de la frappe, `null` si inconnue.
     */
    fun onLayerKey(key: Key, offset: TapOffset? = null) {
        learning?.onKeyTap(key.kind, offset, field.prediction)
        // ?123 : une frappe comptée, mais aucun caractère inséré (donc pas d'erreur possible).
        if (offset != null) heat?.onTap(key.kind, offset.dx, offset.dy, field.prediction)
    }

    /**
     * Lettre [letter] (en minuscule, ou variante accentuée), en majuscule si [upper] ou si la majuscule est
     * active ; [offset] : position de la frappe (modèle spatial, §5.3).
     */
    fun onLetter(letter: String, upper: Boolean = false, offset: TapOffset? = null) {
        require(letter.isNotEmpty()) { "Une lettre ne peut pas être vide." }
        undo = null
        editState = EditState.INITIAL
        val auto = !upper && state.shift == ShiftState.ONCE && manualShift == ShiftState.OFF
        val capital = upper || state.shift != ShiftState.OFF
        val text = if (capital) letter.uppercase(Locale.ROOT) else letter
        measuring?.onKey()
        learning?.onLetter(letter, offset, field.prediction)
        if (composing.isEmpty()) wordTaps.clear()
        addTap(letter, offset)
        trackLetter(letter, offset)
        batch { typeIntoWord(text) }
        if (composing == text) {
            // Première lettre d'un mot : d'où vient sa majuscule.
            wordAutoCapital = capital && auto
            wordTypedCapital = capital && !auto
        }
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
        measuring?.onKey()
        heat?.onUntracked()
        val sign = text.singleOrNull()
        when {
            !field.readsText || sign == null || sign.isDigit() -> plain(text, validate = false)
            sign in JOINERS && composing.isNotEmpty() -> {
                undo = null
                editState = EditState.INITIAL
                addTap(null)
                batch { typeIntoWord(text) }
                refresh()
            }
            sign in EditConfig.SUPPORTED_SIGNS -> onPunctuation(sign)
            else -> plain(text, validate = true)
        }
    }

    /**
     * Émoji choisi dans le panneau (§5.11) : le mot en cours est d'abord validé comme devant un signe
     * (§5.5, sans espace), puis l'émoji est inséré tel quel, quelle que soit sa longueur.
     */
    fun onEmoji(text: String) {
        require(text.isNotEmpty()) { "Un émoji ne peut pas être vide." }
        heatmap?.forgetRecent()
        plain(text, validate = field.readsText)
    }

    /** Texte de l'historique du presse-papiers (issue #35) : inséré comme un collage, après validation du mot en cours. */
    fun onClipText(text: String) {
        require(text.isNotEmpty()) { "Texte vide." }
        heatmap?.forgetRecent()
        plain(text, validate = field.readsText)
    }

    /** Raccourci de la barre (e-mail, URL) : inséré tel quel. */
    fun onShortcut(text: String) {
        heatmap?.forgetRecent()
        plain(text, validate = false)
    }

    /** Touche espace : validation par espace (§5.5), double espace (§5.6). */
    fun onSpace() {
        noteInsert()
        undo = null
        measuring?.onKey()
        if (!field.readsText) {
            plain(" ", validate = false)
            return
        }
        var replaced: String? = null
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
                    if (p.inserted != p.word) replaced = p.inserted
                }
                learning?.onValidated(p, language, field.prediction, wordAutoCapital)
                measuring?.onWord(
                    when {
                        replaced == null -> null
                        p.case == SpaceCase.WORD -> TypingStats.AutoKind.RESTORATION
                        else -> TypingStats.AutoKind.CORRECTION
                    },
                    field.prediction.prudence,
                )
            } else {
                learning?.onWordEnded()
                finishComposing()
                val result = editor.onSpace(context())
                apply(TextEdit(result.operations))
                editState = result.state
                if (result.operations.any { it is EditOperation.InsertText && it.text.startsWith('.') }) endSentence()
            }
        }
        refresh()
        replaced?.let { onWordReplaced?.invoke(it) }
    }

    /**
     * ⌫ : annulation d'une validation automatique (§5.5), sinon effacement d'un caractère. Dans un champ
     * qui ne lit pas le texte (mot de passe, pavés, §7.2), la touche Retour arrière est envoyée telle
     * quelle, sans rien lire du champ.
     */
    fun onBackspace() {
        val pendingUndo = undo
        undo = null
        editState = EditState.INITIAL
        if (!field.readsText) {
            measuring?.onBackspace()
            heatmap?.forgetRecent()
            target.sendBackspaceKey()
            cursor = UNKNOWN
            selectionLength = 0
            refresh()
            return
        }
        val predictor = predictor
        if (pendingUndo != null && predictor != null) {
            val edit = predictor.undo(target.textBeforeCursor(LOOKBACK), pendingUndo.token, field.prediction)
            if (edit != null) {
                // Une correction refusée assez souvent est adoptée dans le dictionnaire personnel (§5.5, §10.5).
                val token = pendingUndo.token
                val refused = token.takeIf { it.case == SpaceCase.DOMINANT && predictor.isRefusalLearned(it) }?.typed
                learning?.onUndo(refused, field.prediction)
                measuring?.onUndo()
                // Annuler une validation automatique n'est pas une erreur de frappe.
                heatmap?.forgetRecent()
                batch {
                    apply(edit)
                    reopenComposing()
                }
                setLanguage(pendingUndo.language)
                refresh()
                return
            }
        }
        measuring?.onBackspace()
        learning?.onErase()
        batch {
            when {
                composing.isNotEmpty() -> {
                    heat?.onErase()
                    val n = TextUnits.lastCodePointLength(composing)
                    wordTaps.removeLastOrNull()
                    composing = composing.dropLast(n)
                    target.setComposingText(composing)
                    move(-n)
                    if (composing.isEmpty()) target.finishComposingText()
                }
                selectionLength > 0 -> {
                    heatmap?.forgetRecent()
                    target.commitText("")
                    selectionLength = 0
                }
                else -> {
                    val n = TextUnits.lastCodePointLength(target.textBeforeCursor(2) ?: "")
                    if (n > 0) {
                        heat?.onErase()
                        target.deleteBefore(n)
                        move(-n)
                    } else {
                        heatmap?.forgetRecent()
                        target.sendBackspaceKey()
                        cursor = UNKNOWN
                    }
                    reopenComposing()
                }
            }
        }
        refresh()
    }

    /**
     * Glisser vers la gauche depuis ⌫ (§5.9, issue #33), un appel par pas : efface le mot précédent (espaces
     * de fin comprises, [WordDeletion]) ou la sélection. Le mot en cours est abandonné pour l'apprentissage.
     * Dans un champ qui ne lit pas le texte, envoie simplement la touche Retour arrière.
     */
    fun onDeleteWord() {
        measuring?.onBackspace()
        undo = null
        editState = EditState.INITIAL
        learning?.onWordAbandoned()
        heatmap?.forgetRecent()
        if (!field.readsText) {
            target.sendBackspaceKey()
            cursor = UNKNOWN
            selectionLength = 0
            refresh()
            return
        }
        batch {
            finishComposing()
            if (selectionLength > 0) {
                target.commitText("")
                selectionLength = 0
            } else {
                val n = WordDeletion.length(target.textBeforeCursor(LOOKBACK))
                if (n > 0) {
                    target.deleteBefore(n)
                    move(-n)
                }
            }
        }
        refresh()
    }

    /** Touche Entrée selon le champ : saut de ligne, action de l'éditeur ou touche Entrée. */
    fun onEnter() {
        undo = null
        editState = EditState.INITIAL
        learning?.onWordEnded()
        if (field.enter == EnterBehavior.NewLine) {
            noteInsert()
        } else {
            tapCounted = false
            heatmap?.forgetRecent()
        }
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
            BarMode.WORDS -> pickBarWord(item)
            BarMode.PUNCTUATION -> onCharacter(item.text)
            BarMode.SHORTCUTS -> onShortcut(item.text)
            BarMode.EMPTY -> Unit
        }
    }

    /**
     * Glisser vers le haut sur l'espace (§5.9) : insère la suggestion centrale de la barre de mots, même si
     * elle ne domine pas, comme un tap sur elle (§5.4). Sans suggestion centrale (pas de mot en cours, barre
     * de ponctuation ou de raccourcis), le geste vaut un appui sur l'espace.
     */
    fun onSpaceSwipeUp() {
        val bar = state.bar
        val center = bar.items.getOrNull(CENTER_ITEM)
        if (bar.mode == BarMode.WORDS && center != null) pickBarWord(center) else onSpace()
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
     * Touche `⇧` explicite (§7.1) à l'instant [nowMs] : un appui = une majuscule, un second appui dans les
     * [doubleTapMs] millisecondes = verrouillage ; un appui sur la majuscule active ou verrouillée l'annule.
     */
    fun onShiftKey(nowMs: Long, doubleTapMs: Long = DOUBLE_TAP_MS) {
        require(doubleTapMs >= 0) { "Le délai du double appui doit être positif ou nul : $doubleTapMs." }
        val last = lastShiftTapMs
        val doubleTap = manualShift == ShiftState.ONCE && last != null && nowMs - last in 0..doubleTapMs
        manualShift = when {
            doubleTap -> ShiftState.LOCKED
            manualShift == ShiftState.OFF -> ShiftState.ONCE
            else -> ShiftState.OFF
        }
        lastShiftTapMs = if (manualShift == ShiftState.ONCE) nowMs else null
        refresh()
    }

    /**
     * Touche du séparateur décimal du pavé numérique (§8.1) : [policy] insère `,` ou `.` selon ce que le champ
     * [fieldKey] accepte (vérification par le texte avant le curseur, seule lecture faite dans un pavé).
     *
     * @return le caractère inséré, ou `null` si le champ a refusé les deux.
     */
    fun onDecimalSeparator(policy: DecimalSeparatorPolicy, fieldKey: FieldKey): Char? {
        undo = null
        editState = EditState.INITIAL
        // Pavé numérique : jamais de mot en composition, rien à valider avant.
        val inserted = policy.insert(
            fieldKey,
            object : SeparatorEditor {
                override fun textBeforeCursor(): String? = target.textBeforeCursor(DECIMAL_LOOKBACK)?.toString()

                override fun commitText(text: String) = target.commitText(text)
            },
        )
        // Le champ a pu filtrer l'insertion : la position du curseur n'est plus connue.
        cursor = UNKNOWN
        selectionLength = 0
        refresh()
        return inserted
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

    /** Un caractère va être inséré par espace ou entrée : sa frappe est déjà comptée par `onKey`, sinon un marqueur. */
    private fun noteInsert() {
        if (tapCounted) tapCounted = false else heat?.onUntracked()
    }

    /** Compte la frappe sur [letter] ; hors `a`–`z`, sans position ou hors touche, un marqueur garde la pile alignée. */
    private fun trackLetter(letter: String, offset: TapOffset?) {
        val map = heat ?: return
        val base = letter.lowercase(Locale.ROOT).singleOrNull()
        val counted = offset != null && base != null && map.onTap(base, offset.dx, offset.dy, field.prediction)
        if (!counted) map.onUntracked()
    }

    private fun pickBarWord(item: BarItem) {
        if (nextWordBar) pickNextWord(item) else pickSuggestion(item)
    }

    /**
     * Insère le mot suivant [item] (ou le groupe de deux mots) suivi d'une espace automatique, sauf après une
     * élision (`j'`) : le mot d'après commence sans espace. Pas d'annulation, rien n'est appris ni mémorisé.
     */
    private fun pickNextWord(item: BarItem) {
        val predictor = predictor ?: return
        val language = language ?: return
        undo = null
        heatmap?.forgetRecent()
        batch {
            val elision = item.text.last() in ELISION_ENDS
            commit(if (elision) item.text else item.text + " ")
            editState = when {
                elision -> EditState.INITIAL
                atEnd() -> EditState(SpaceOrigin.AUTO)
                else -> EditState(SpaceOrigin.TYPED)
            }
            var next = language
            for (word in item.text.split(' ')) next = predictor.observe(next, word, field.prediction)
            setLanguage(next)
        }
        refresh()
    }

    private fun pickSuggestion(item: BarItem) {
        val predictor = predictor ?: return
        val language = language ?: return
        undo = null
        heatmap?.forgetRecent()
        batch {
            val p = currentPrediction() ?: return@batch
            val commit = predictor.pickSuggestion(p, Suggestion(item.text, item.highlighted, item.typed), language, field.prediction)
            // Le texte tapé, gardé alors qu'un candidat dominait : il n'est dans aucun lexique (cas 2 du §5.5).
            val unknown = item.typed && p.case == SpaceCase.DOMINANT
            learning?.onSuggestionPicked(item.text, unknown, language, field.prediction, wordAutoCapital)
            measuring?.onPick()
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
        if (p != null && predictor != null && language != null && p.case != SpaceCase.NO_WORD && p.case != SpaceCase.DISABLED) {
            val commit = predictor.commitBeforePunctuation(p, language, field.prediction)
            apply(commit.edit)
            setLanguage(commit.language)
            learning?.onValidated(p, language, field.prediction, wordAutoCapital)
        } else {
            learning?.onWordEnded()
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
        val language = language ?: return null
        if (predictors == null || !field.composes || selectionLength > 0) return null
        val before = target.textBeforeCursor(LOOKBACK)
        return predictorFor(before)?.predict(before, language, field.prediction, currentTaps())
    }

    /** Ajoute la frappe de [letter] (`null` : caractère sans position) ; au plus [MAX_TAPS], les plus anciennes sont retirées. */
    private fun addTap(letter: String?, offset: TapOffset? = null) {
        val c = letter?.singleOrNull()
        wordTaps += if (offset != null && c != null && c in 'a'..'z') TypedTap(c, offset.dx, offset.dy) else null
        while (wordTaps.size > MAX_TAPS) wordTaps.removeAt(0)
    }

    /** Frappes du mot en cours avec le modèle spatial appris, ou `null` sans frappe. */
    private fun currentTaps(): WordTaps? =
        if (wordTaps.isEmpty()) null else WordTaps(ArrayList(wordTaps), learning?.data?.spatial ?: TapSpatialModel.NONE)

    /** Prédicteur du niveau de contexte des contacts (§6.3) pour le texte [before]. */
    private fun predictorFor(before: CharSequence?): Predictor? {
        val stack = predictors ?: return null
        if (stack.directory == null) return stack.base
        val typedCapital = wordTypedCapital && composing.isNotEmpty()
        val personName = field.prediction.profile == FieldProfile.PERSON_NAME
        return stack.forContext(ContactContext.read(before, personName, typedCapital, elisionLanguage()))
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
            wordTaps.clear()
            KeyboardState(shortcutsOrEmpty(), null, null, manualShift, null, languageVersion)
        } else {
            val before = target.textBeforeCursor(LOOKBACK)
            val lang = language
            val predictor = if (lang != null && field.composes && selectionLength == 0) predictorFor(before) else null
            val p = if (predictor != null && lang != null) predictor.predict(before, lang, field.prediction, currentTaps()) else null
            prediction = p
            if (p == null || p.word.isEmpty()) wordTaps.clear()
            val shift = if (before == null) manualShift else AutoCapitalization.shiftFor(before, field.textProfile, field.capsMode, manualShift)
            val prefix = if (field.adaptation && lang != null) WordPrefix.of(before, selectionLength, elisionLanguage()) else null
            val following = if (p?.word.isNullOrEmpty()) nextWordSuggestions(lang, shift != ShiftState.OFF) else emptyList()
            nextWordBar = following.isNotEmpty() && field.shortcuts.isEmpty() && (p == null || p.bar.isEmpty)
            KeyboardState(bar(p, following), p?.spaceLabel?.let { "$SPACE_SYMBOL $it" }, languageLabel(lang), shift, prefix, languageVersion)
        }
        onStateChanged?.invoke(state)
    }

    /**
     * Mots suivants (issue #51) quand le curseur suit une espace (ou une élision) ou le début du champ : aucun mot
     * en cours, réglage actif, pas de sélection, pas de lettre juste après le curseur. [capitalize] : début de phrase.
     */
    private fun nextWordSuggestions(lang: LanguageState?, capitalize: Boolean): List<Suggestion> {
        val predictor = nextWords ?: return emptyList()
        if (lang == null || !field.composes || selectionLength > 0 || !field.prediction.nextWordEnabled) return emptyList()
        val after = target.textAfterCursor(1)
        if (!after.isNullOrEmpty() && after[0].isLetterOrDigit()) return emptyList()
        return predictor.suggest(target.textBeforeCursor(NextWordContext.LOOKBACK), lang, capitalize)
    }

    private fun bar(p: Prediction?, following: List<Suggestion>): Bar = when {
        field.shortcuts.isNotEmpty() -> shortcutsOrEmpty()
        p != null && !p.bar.isEmpty -> Bar(BarMode.WORDS, listOf(p.bar.left, p.bar.center, p.bar.right).map { it?.toItem() })
        following.isNotEmpty() -> SuggestionBar.of(following).let { Bar(BarMode.WORDS, listOf(it.left, it.center, it.right).map { s -> s?.toItem() }) }
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
        /** Case centrale de la barre de mots : le mot que l'espace insérera (§5.4). */
        const val CENTER_ITEM: Int = 1

        /** Caractères demandés avant le curseur. */
        const val LOOKBACK: Int = WordPrefix.LOOKBACK

        /** Signes de la barre de ponctuation (§5.6). */
        val PUNCTUATION_BAR: List<String> = listOf(",", ".", "?", "!", ":", "…", "'")

        /** Préfixe du libellé de l'espace quand elle insérera un autre mot (§5.5). */
        const val SPACE_SYMBOL: String = "␣"

        private const val UNKNOWN = -1
        private val HEAT_KEYS = SpatialModel.LEARNED_KEYS

        /** Délai maximal entre les deux appuis du verrouillage de `⇧` (§7.1), valeur par défaut d'Android. */
        const val DOUBLE_TAP_MS: Long = 300L

        /** Caractères lus pour vérifier l'insertion du séparateur décimal (§8.1). */
        private const val DECIMAL_LOOKBACK = 32
        private const val MAX_PENDING = 32
        private const val MAX_TAPS = 64
        private const val JOINERS = "'’-‑"
        private const val ELISION_ENDS = "'’"
        private const val SENTENCE_END = ".?!…"
    }
}
