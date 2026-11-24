package nextglyph.engine.predict

import nextglyph.engine.input.EditOperation
import nextglyph.engine.input.TextEdit
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.lexicon.Distribution
import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.text.TextNormalizer
import nextglyph.engine.text.WordPrefix
import java.util.Locale

/**
 * Prédiction, validation par espace et bilinguisme (spec §5.4, §5.5, §5.8, §10.4, §10.6).
 *
 * API sans état propre (en dehors de la mémoire des refus, [RejectionStore]) : chaque appel prend
 * l'état (texte avant le curseur, [LanguageState], [PredictionSettings]) et renvoie une décision
 * ([Prediction]) ou une modification abstraite du texte ([TextEdit]) que l'IME applique via
 * `InputConnection`. Les modifications se composent avec celles de `nextglyph.engine.edit`
 * (ponctuation, typographie) : [commitSpace] se termine par une espace automatique marquée
 * ([TextEdit.endsWithAutoSpace]) que la ponctuation peut remplacer, et [commitBeforePunctuation]
 * valide le mot sans espace avant un signe (§5.6).
 *
 * Séquence type dans l'IME :
 * 1. après chaque frappe : `predict(texte, langue, réglages)` → barre et libellé de l'espace ;
 * 2. espace : `commitSpace(prédiction, langue, réglages)` → appliquer `edit`, garder `undo` et `language` ;
 * 3. ⌫ immédiatement après : `undo(texte, jeton, réglages)` → appliquer, sinon effacement normal ;
 * 4. ponctuation finale : `language.endSentence()`.
 *
 * **Erreurs d'espace** (§5.5, issue #52) : quand le texte tapé n'est pas un mot, les coupures (`jesuis`,
 * espace tapée sur `n`, `b`, `v`, `c`) et l'espace en trop (`ordina teur`, le mot précédent étant lu dans
 * [predict]) sont des candidats comme les autres ([SpaceEdits]) ; [Prediction.span] dit alors quel texte
 * [commitSpace] remplace et [UndoToken.typed] ce que [undo] restitue. Les bigrammes de [useNgrams] les départagent.
 *
 * @param lexicons lexiques actifs par code de langue (`fr`, `en`) ; l'ordre est celui des langues
 *   du [LanguageState] attendu.
 * @param layout disposition courante (adjacence des touches pour la correction).
 * @param blockedAutocommit clés qui ne peuvent jamais être insérées par complétion ou correction (cas 2
 *   du §5.5) : noms de contacts hors du contexte qui les justifie (§5.5, §6.3). Elles restent proposées
 *   dans la barre ; sans effet sur un mot tapé qui est déjà un mot (cas 1).
 * @param cacheSize nombre de textes tapés dont les candidats sont gardés en mémoire, par langue
 *   (0 : aucun cache). Les candidats d'une langue ne dépendent que du texte tapé : un cache évite de
 *   les recalculer après ⌫ ou dans les simulations. Avec un cache, l'instance n'est pas thread-safe.
 */
class Predictor(
    lexicons: Map<String, LexiconSet>,
    layout: KeyboardLayout,
    val config: PredictionConfig = PredictionConfig(),
    private val rejections: RejectionStore = InMemoryRejectionStore(),
    cacheSize: Int = 0,
    private val blockedAutocommit: (String) -> Boolean = { false },
) {
    /** Codes des langues actives. */
    val languages: List<String> = lexicons.keys.toList()

    private val sets: List<LexiconSet> = languages.map { lexicons.getValue(it) }
    private val adjacency = KeyAdjacency(layout, config.neighborDistance)
    private val substitution = SpatialSubstitution(layout, config)
    private val sources: List<CandidateSource> = sets.map { CandidateSource(it, adjacency, config, substitution) }
    private val display = DisplayForms(config.accentDominance)
    private val spaceEdits = SpaceEdits(sets, languages, config.spaceEdits)
    private val confusions = Confusions(config.confusion)
    private val elisionWords: Set<String> = config.elisions.values.flatten().toSet()
    private val frenchIndex = languages.indexOfFirst { it in config.elisions.keys }
    private val caches: List<LruCache>? =
        if (cacheSize > 0) sets.map { LruCache(cacheSize) } else null

    init {
        require(cacheSize >= 0) { "La taille du cache doit être positive ou nulle ($cacheSize)." }
        require(languages.isNotEmpty()) { "Au moins une langue active est requise." }
        lexicons.forEach { (code, set) ->
            require(set.language == code) { "Le lexique de « $code » est en « ${set.language} »." }
        }
    }

    /**
     * Modèles de mot suivant par code de langue (issue #51) qui départagent les coupures des erreurs d'espace
     * (issue #52, §5.5) par `P(droite | gauche)` et choisissent entre les formes confondues d'après le mot précédent
     * (issue #55, §5.5). Vide : unigrammes, aucune confusion corrigée. Rien n'en est retenu d'autre.
     */
    fun useNgrams(models: Map<String, NgramModel>) {
        spaceEdits.models = LinkedHashMap(models)
        confusions.models = LinkedHashMap(models)
    }

    /** État de langue de départ, [preferred] étant la langue préférée (§10.6, §14). */
    fun initialLanguageState(preferred: String): LanguageState =
        LanguageState.initial(languages, preferred, config.languageWindow, config.languagePrior, config.preferredLanguageShare)

    /**
     * Décision de l'espace et barre de suggestions pour le texte [textBeforeCursor] (au moins
     * `WordPrefix.LOOKBACK` caractères ; `null` si indisponible).
     *
     * [taps] : frappes du mot en cours, qui pondèrent les substitutions d'après la position réelle des
     * doigts (issue #53) ; `null` si inconnues.
     *
     * @throws IllegalArgumentException si [language] ne porte pas sur les langues de ce prédicteur.
     */
    fun predict(textBeforeCursor: CharSequence?, language: LanguageState, settings: PredictionSettings, taps: WordTaps? = null): Prediction {
        requireLanguages(language)
        val sentence = language.sentenceLanguage
        if (!settings.suggestionsEnabled) return Prediction("", "", SpaceCase.DISABLED, "", emptyList(), SuggestionBar.EMPTY, sentence)
        val raw = WordPrefix.currentWord(textBeforeCursor, 0, elisionLanguage(language)).orEmpty()
        if (raw.isEmpty()) return Prediction("", "", SpaceCase.NO_WORD, "", emptyList(), SuggestionBar.EMPTY, sentence)
        val key = keyOf(raw) ?: return Prediction(raw, "", SpaceCase.LITERAL, raw, emptyList(), SuggestionBar.EMPTY, sentence)
        val technical = Confusions.isTechnical(textBeforeCursor, raw)
        return decide(raw, key, language, settings, taps, previousWord(textBeforeCursor, raw), contextWord(textBeforeCursor, raw), technical)
    }

    /**
     * Décision pour le mot [raw] de clé [key] déjà extraits (simulations, mesures) ; voir [predict].
     * Les candidats ne passent pas par le cache quand [taps] s'aligne sur [key].
     *
     * [previous] : le mot écrit juste avant une seule espace, elle-même juste avant [raw] (`null` sinon) : il permet
     * de supprimer une espace en trop (§5.5, issue #52). [context] : le mot qui précède [raw] une seule espace,
     * même après une élision (`qu'il a`), qui décide entre les formes confondues (issue #55) ; `null` si inconnu
     * (début de phrase, ponctuation, trait d'union). [technical] : le mot en cours est collé à `@`, `/`, `_` ou à un
     * point intérieur ; il n'est alors jamais remplacé, pas plus qu'un sigle (§5.5, issue #55).
     */
    fun decide(
        raw: String,
        key: String,
        language: LanguageState,
        settings: PredictionSettings,
        taps: WordTaps? = null,
        previous: String? = null,
        context: String? = null,
        technical: Boolean = false,
    ): Prediction {
        requireLanguages(language)
        val literal = config.confusion.protectTechnical && (technical || Confusions.isAcronym(raw))
        val weights = languageWeights(language)
        val active = sets.filterIndexed { i, _ -> weights[i] > 0.0 }
        val activeWeights = weights.filter { it > 0.0 }
        val spatialTaps = if (taps?.alignedTo(key) != null) taps else null
        val sentenceSet = sets[languages.indexOf(language.sentenceLanguage)]
        var isWord = false
        var total = 0.0
        val scores = LinkedHashMap<String, ScoredKey>()
        for (i in sets.indices) {
            val pi = weights[i]
            if (pi <= 0.0) continue
            val found = if (spatialTaps != null) {
                sources[i].lookup(key, spatialTaps)
            } else {
                caches?.get(i)?.getOrPut(key) { sources[i].lookup(key) } ?: sources[i].lookup(key)
            }
            isWord = isWord || found.isWord
            total += pi * found.total
            for (c in found.top) {
                val previous = scores[c.key]
                scores[c.key] = c.copy(score = (previous?.score ?: 0.0) + pi * c.score)
            }
        }
        // Erreurs d'espace (issue #52) : coupures du texte tapé, espace en trop avec le mot précédent. Elles
        // concourent avec les autres candidats, sous la même règle de dominance.
        val edits = if (settings.autocommitEnabled && !literal) spaceEditsFor(raw, key, previous, spatialTaps, isWord, weights) else emptyList()
        val editsByKey = HashMap<String, SpaceEdit>()
        for (edit in edits) {
            editsByKey[edit.key] = edit
            scores[edit.key] = ScoredKey(edit.key, edit.score)
            total += edit.score
        }
        // Les candidats des langues sont fusionnés puis ramenés aux formsPerPrefix meilleurs.
        val ranked = if (total <= 0.0) emptyList() else scores.values.sortedWith(CandidateSource.SCORE_ORDER).take(config.formsPerPrefix).map {
            val edit = editsByKey[it.key]
            if (edit == null) {
                Candidate(it.key, display(raw, it, active, activeWeights, sentenceSet), it.score / total)
            } else {
                Candidate(it.key, editText(edit, active, activeWeights, sentenceSet), it.score / total, edit.replaces)
            }
        }
        val p1 = ranked.getOrNull(0)?.probability ?: 0.0
        val p2 = ranked.getOrNull(1)?.probability ?: 0.0
        val top = ranked.firstOrNull()
        val topEdit = top?.let { editsByKey[it.key] }
        val dominant = settings.autocommitEnabled && !literal && top != null && p1 >= settings.prudence.minProb &&
            p1 >= settings.prudence.minRatio * p2 && !isRejected(top.replaces ?: raw, top.text) &&
            !(topEdit?.parts ?: listOf(top.key)).any(blockedAutocommit)
        // Un mot très rare, absorbé par l'espace en trop qui le sépare de son début, n'est pas un mot (§5.5, cas 3).
        val absorbed = isWord && dominant && topEdit?.replaces != null
        // Formes confondues (`a`/`à`, `ces`/`ses`) : le mot précédent choisit, sinon l'autre forme est seulement proposée.
        val alternatives = if (isWord && !literal && !absorbed && context != null) confusions.alternatives(language.sentenceLanguage, raw, context) else emptyList()
        val offered = alternatives.filter { it.share >= config.confusion.offerShare && it.total >= config.confusion.minOfferCount }.map { it.text }
        val case: SpaceCase
        val inserted: String
        var span = raw
        when {
            literal -> {
                case = SpaceCase.LITERAL
                inserted = raw
            }
            isWord && !absorbed -> {
                case = SpaceCase.WORD
                val restored = if (settings.autocommitEnabled) display.forTypedWord(raw, key, active, sentenceSet, activeWeights) else raw
                val kept = if (restored != raw && isRejected(raw, restored)) raw else restored
                val swap = alternatives.firstOrNull()?.takeIf { isConfident(it, settings) && !isRejected(raw, it.text) }
                inserted = if (kept == raw && swap != null) swap.text else kept
            }
            dominant -> {
                case = SpaceCase.DOMINANT
                inserted = ranked[0].text
                span = ranked[0].replaces ?: raw
            }
            else -> {
                case = SpaceCase.LITERAL
                inserted = raw
            }
        }
        val bar = bar(raw, span, key, case, inserted, ranked, active, activeWeights, offered)
        return Prediction(raw, key, case, inserted, ranked, bar, language.sentenceLanguage, span)
    }

    /** La forme [alternative] remplace le mot tapé sans demander (§5.5, issue #55) : part et occurrences selon la prudence. */
    private fun isConfident(alternative: Alternative, settings: PredictionSettings): Boolean =
        alternative.auto && settings.autocommitEnabled &&
            alternative.share >= settings.prudence.confusionShare && alternative.total >= settings.prudence.confusionCount

    private fun spaceEditsFor(raw: String, key: String, previous: String?, taps: WordTaps?, isWord: Boolean, weights: DoubleArray): List<SpaceEdit> {
        val result = ArrayList<SpaceEdit>()
        if (!isWord) result += spaceEdits.splits(raw, key, taps, weights)
        val previousKey = previous?.takeIf { !Confusions.isAcronym(it) }?.let(::keyOf)
        if (previous != null && previousKey != null) {
            spaceEdits.join(previous, previousKey, raw, key, isWord, weights)?.let(result::add)
        }
        return result
    }

    /** Texte inséré par une correction d'espace : chaque mot en forme d'affichage, séparés par une espace (un seul mot : espace supprimée). */
    private fun editText(edit: SpaceEdit, active: List<LexiconSet>, activeWeights: List<Double>, sentenceSet: LexiconSet): String =
        edit.parts.indices.joinToString(" ") { display.forCandidate(edit.rawParts[it], edit.parts[it], active, sentenceSet, activeWeights) }

    /**
     * Le mot écrit juste avant [raw], séparé de lui par une seule espace ordinaire (`null` sinon : ponctuation,
     * espace insécable, élision, début de champ, mot coupé par la fenêtre lue, mot collé à un chiffre ou à un
     * trait d'union).
     */
    private fun previousWord(text: CharSequence?, raw: String): String? {
        if (text == null || raw.isEmpty() || !text.endsWith(raw)) return null
        val space = text.length - raw.length - 1
        if (space < 1 || text[space] != ' ') return null
        var start = space
        while (start > 0 && text[start - 1].isLetter()) start--
        if (start == space) return null
        if (start == 0 && text.length >= WordPrefix.LOOKBACK) return null
        if (start > 0 && (text[start - 1].isDigit() || text[start - 1] in JOINERS)) return null
        return text.substring(start, space)
    }

    /**
     * Le mot écrit juste avant [raw], séparé de lui par une seule espace ordinaire, comme [previousWord] mais aussi
     * après une élision (`qu'il a`) ; `null` si ce mot est collé à un trait d'union, à un chiffre, à `@`, `/`, `_`, ou à
     * une apostrophe qui ne suit pas une élision (`aujourd'hui`), ou si la fenêtre lue le coupe.
     */
    private fun contextWord(text: CharSequence?, raw: String): String? {
        if (text == null || raw.isEmpty() || !text.endsWith(raw)) return null
        val space = text.length - raw.length - 1
        if (space < 1 || text[space] != ' ') return null
        var start = space
        while (start > 0 && text[start - 1].isLetter()) start--
        if (start == space) return null
        if (start == 0 && text.length >= WordPrefix.LOOKBACK) return null
        val word = text.substring(start, space)
        if (start > 0) {
            val joiner = text[start - 1]
            if (joiner.isDigit() || joiner == '-' || joiner == '‑') return null
            if (joiner in APOSTROPHES && !endsWithElision(text, start - 1)) return null
        }
        return word.takeUnless { Confusions.isTechnical(text.subSequence(0, space), it) }
    }

    /** Vrai si les lettres qui précèdent l'apostrophe en [apostrophe] sont une élision (`qu'`, `l'`). */
    private fun endsWithElision(text: CharSequence, apostrophe: Int): Boolean {
        var start = apostrophe
        while (start > 0 && text[start - 1].isLetter()) start--
        return start < apostrophe && text.substring(start, apostrophe).lowercase(Locale.ROOT) in elisionWords
    }

    /**
     * Validation par espace (§5.5) : remplace le mot en cours par [Prediction.inserted] et ajoute
     * une espace automatique. Sans mot en cours, ou suggestions désactivées, insère une simple espace.
     */
    fun commitSpace(prediction: Prediction, language: LanguageState, settings: PredictionSettings): Commit {
        if (prediction.case == SpaceCase.NO_WORD || prediction.case == SpaceCase.DISABLED) {
            return Commit(TextEdit(listOf(EditOperation.InsertText(" "))), null, language)
        }
        val edit = TextEdit(replace(prediction.span, prediction.inserted + " "), endsWithAutoSpace = true)
        val undo = if (prediction.inserted != prediction.span) UndoToken(prediction.span, prediction.inserted, prediction.case) else null
        return Commit(edit, undo, observe(language, prediction.inserted, settings))
    }

    /**
     * Validation du mot en cours avant un signe de ponctuation (§5.6, « Pendant un mot en cours ») :
     * mêmes règles que l'espace, sans espace automatique ; le signe est ensuite appliqué par le
     * module de ponctuation. Pas de jeton d'annulation : le ⌫ suivant efface le signe.
     */
    fun commitBeforePunctuation(prediction: Prediction, language: LanguageState, settings: PredictionSettings): Commit {
        if (prediction.case == SpaceCase.NO_WORD || prediction.case == SpaceCase.DISABLED) return Commit(TextEdit.NONE, null, language)
        return Commit(TextEdit(replace(prediction.span, prediction.inserted)), null, observe(language, prediction.inserted, settings))
    }

    /** Tap sur une suggestion (§5.4) : remplace le mot en cours par [suggestion] et ajoute une espace automatique. */
    fun pickSuggestion(prediction: Prediction, suggestion: Suggestion, language: LanguageState, settings: PredictionSettings): Commit {
        val replaced = when {
            suggestion.isTyped -> prediction.span
            else -> prediction.candidates.firstOrNull { it.text == suggestion.text }?.replaces ?: prediction.word
        }
        val edit = TextEdit(replace(replaced, suggestion.text + " "), endsWithAutoSpace = true)
        return Commit(edit, null, observe(language, suggestion.text, settings))
    }

    /**
     * Annulation d'une validation automatique par ⌫ (§5.5) : si [textBeforeCursor] se termine par le
     * texte inséré suivi de l'espace automatique, le remplace par le texte tapé à l'origine, sans
     * espace, et compte un refus (sauf incognito). Renvoie `null` si l'annulation ne s'applique pas
     * (l'IME efface alors normalement).
     */
    fun undo(textBeforeCursor: CharSequence?, token: UndoToken, settings: PredictionSettings): TextEdit? {
        val expected = token.inserted + " "
        if (textBeforeCursor == null || !textBeforeCursor.endsWith(expected)) return null
        if (settings.learningEnabled) rejections.recordRejection(token.typed, token.inserted)
        return TextEdit(replace(expected, token.typed))
    }

    /**
     * Vrai si le refus de [token] a atteint `rejectLimit` : la correction n'est plus appliquée (§5.5,
     * « Apprentissage du refus »). À appeler après [undo].
     */
    fun isRefusalLearned(token: UndoToken): Boolean = rejections.rejections(token.typed, token.inserted) >= config.rejectLimit

    /**
     * Score de langue après la validation du texte [text] (un mot, ou une élision et son mot :
     * `l'école` compte `l'` puis `école`). Utilisé par les validations ; à appeler aussi par l'IME
     * pour un mot terminé autrement (ponctuation tapée sans validation, par exemple). En mode mot de
     * passe, rien n'est observé (§7.2).
     */
    fun observe(language: LanguageState, text: String, settings: PredictionSettings = PredictionSettings()): LanguageState {
        requireLanguages(language)
        if (settings.profile == FieldProfile.PASSWORD) return language
        var state = language
        for (key in observedKeys(text)) state = state.observe(evidence(key, language.languages))
        return state
    }

    /**
     * Indice de langue du mot [key] (§10.6) : `(f_L) / Σ f`, où `f_L` est la fréquence du mot
     * divisée par la masse totale du lexique `L` ; tous nuls pour un mot inconnu.
     */
    fun evidence(key: String, order: List<String> = languages): DoubleArray {
        val f = DoubleArray(order.size) { i ->
            val set = sets[languages.indexOf(order[i])]
            set.displayForms(key).sumOf { it.frequency.toDouble() } / set.totalMass
        }
        val sum = f.sum()
        if (sum > 0.0) for (i in f.indices) f[i] /= sum
        return f
    }

    /**
     * Distribution mélangée de la lettre suivante pour l'adaptation visuelle (§10.6, « Mélange ») :
     * `P(c | p) = Σ π_L · P_L(c | p)`, sur les langues où [prefix] est connu (poids renormalisés) ;
     * `P(fin | p)` de même. La masse est `Σ π_L · masse_L(p) / masse_L` (relative). Utilisable comme
     * `DistributionSource` du calcul des poids (`nextglyph.engine.adapt`).
     */
    fun distribution(prefix: String, language: LanguageState): Distribution {
        requireLanguages(language)
        val weights = languageWeights(language)
        val parts = sets.indices.mapNotNull { i ->
            if (weights[i] <= 0.0) return@mapNotNull null
            val d = sets[i].distribution(prefix)
            if (d.isUnknownPrefix) null else Triple(weights[i], d, sets[i].totalMass)
        }
        if (parts.isEmpty()) return Distribution.UNKNOWN
        if (parts.size == 1) return parts[0].second
        val norm = parts.sumOf { it.first }
        val probabilities = DoubleArray(LexiconAlphabet.SIZE)
        var end = 0.0
        var mass = 0.0
        for ((pi, d, root) in parts) {
            val w = pi / norm
            for (c in probabilities.indices) probabilities[c] += w * d.probability(LexiconAlphabet.CHARS[c])
            end += w * d.endProbability
            mass += pi * d.totalMass / root
        }
        return Distribution(false, mass, end, probabilities)
    }

    private fun requireLanguages(language: LanguageState) {
        require(language.languages == languages) {
            "L'état de langue porte sur ${language.languages}, le prédicteur sur $languages."
        }
    }

    private fun languageWeights(language: LanguageState): DoubleArray = language.weights

    /** Langue dont les élisions sont retirées du mot en cours : le français s'il a un poids non nul. */
    private fun elisionLanguage(language: LanguageState): String =
        if (frenchIndex >= 0 && language.weights[frenchIndex] > 0.0) languages[frenchIndex] else ""

    private fun keyOf(raw: String): String? {
        val key = try {
            TextNormalizer.normalize(raw.replace('‑', '-'))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return key.takeIf(LexiconAlphabet::isValidKey)
    }

    private fun display(raw: String, candidate: ScoredKey, active: List<LexiconSet>, activeWeights: List<Double>, sentenceSet: LexiconSet): String {
        if (candidate.restStart == 0) return display.forCandidate(raw, candidate.key, active, sentenceSet, activeWeights)
        // Élision reconstituée : l'élision garde la casse tapée, le mot élidé est restitué.
        val elisionLength = candidate.restStart - 1
        val head = raw.take(elisionLength)
        val rest = display.forCandidate(raw.drop(elisionLength).lowercase(Locale.ROOT), candidate.key.substring(candidate.restStart), active, sentenceSet, activeWeights)
        return "$head'$rest"
    }

    private fun isRejected(raw: String, inserted: String): Boolean = rejections.rejections(raw, inserted) >= config.rejectLimit

    private fun bar(
        raw: String,
        typed: String,
        key: String,
        case: SpaceCase,
        inserted: String,
        ranked: List<Candidate>,
        active: List<LexiconSet>,
        activeWeights: List<Double>,
        offered: List<String>,
    ): SuggestionBar {
        val list = ArrayList<Suggestion>(BAR_SIZE + 2)
        val seen = HashSet<String>()
        fun add(suggestion: Suggestion) {
            if (list.size < BAR_SIZE && seen.add(suggestion.text.lowercase(Locale.ROOT))) list += suggestion
        }
        val replaces = inserted != raw
        when (case) {
            SpaceCase.WORD -> {
                add(Suggestion(inserted, highlighted = replaces))
                if (replaces) add(Suggestion(raw, isTyped = true))
                for (text in offered) add(Suggestion(text))
                for (variant in variants(raw, key, active, activeWeights)) add(Suggestion(variant))
            }
            SpaceCase.DOMINANT -> {
                add(Suggestion(inserted, highlighted = true))
                add(Suggestion(typed, isTyped = true))
            }
            else -> Unit
        }
        for (candidate in ranked) if (candidate.key != key) add(Suggestion(candidate.text))
        return SuggestionBar.of(list)
    }

    /**
     * Variantes d'un mot tapé, proposées dans la barre sans jamais être insérées automatiquement :
     * ses autres formes d'affichage (`a` → `à`), l'élision française qu'il peut cacher (`jaime` →
     * `j'aime`) et les mots obtenus en insérant une apostrophe ou un trait d'union (`its` → `it's`,
     * `well` → `we'll`).
     */
    private fun variants(raw: String, key: String, active: List<LexiconSet>, activeWeights: List<Double>): List<String> {
        val result = ArrayList<String>()
        for (set in active) {
            for (entry in set.displayForms(key)) {
                if (DisplayForms.isPlausibleCase(entry.form)) result += DisplayForms.applyTypedCase(entry.form, raw)
            }
        }
        if (frenchIndex >= 0 && sets[frenchIndex] in active) {
            for (elision in config.elisions.getValue(languages[frenchIndex])) {
                val rest = key.removePrefix(elision)
                if (rest.length == key.length || rest.isEmpty() || rest[0] !in config.elisionInitials) continue
                if (sets[frenchIndex].containsKey(rest) && sets[frenchIndex].containsKey("$elision'")) {
                    result += raw.take(elision.length) + "'" + display.forCandidate(raw.drop(elision.length).lowercase(Locale.ROOT), rest, active, sets[frenchIndex], activeWeights)
                }
            }
        }
        for (i in 1 until key.length) {
            for (separator in SEPARATORS) {
                val variant = key.substring(0, i) + separator + key.substring(i)
                val forms = active.flatMap { it.displayForms(variant) }
                forms.maxByOrNull { it.frequency }?.let { result += DisplayForms.applyTypedCase(it.form, raw) }
            }
        }
        return result
    }

    private fun observedKeys(text: String): List<String> {
        // Une correction d'espace insère plusieurs mots (`je suis`) : chacun compte pour le score de langue.
        if (' ' in text) return text.split(' ').filter { it.isNotEmpty() }.flatMap(::observedKeys)
        val key = keyOf(text) ?: return emptyList()
        val apostrophe = key.indexOf('\'')
        if (frenchIndex >= 0 && apostrophe > 0 && key.length > apostrophe + 1) {
            val elision = key.substring(0, apostrophe)
            if (elision in config.elisions.getValue(languages[frenchIndex])) {
                return listOf(key.substring(0, apostrophe + 1), key.substring(apostrophe + 1))
            }
        }
        return listOf(key)
    }

    /** Nombre de textes tapés en cache, par langue (vide sans cache). */
    internal fun cachedTexts(): List<Int> = caches?.map { it.size }.orEmpty()

    /** Cache LRU : au-delà de [capacity] entrées, la moins récemment lue est retirée. */
    private class LruCache(private val capacity: Int) {
        private val map = LinkedHashMap<String, LanguageCandidates>(INITIAL_CAPACITY, LOAD_FACTOR, true)

        val size: Int get() = map.size

        fun getOrPut(key: String, compute: () -> LanguageCandidates): LanguageCandidates {
            map[key]?.let { return it }
            val value = compute()
            map[key] = value
            if (map.size > capacity) {
                val eldest = map.keys.iterator()
                eldest.next()
                eldest.remove()
            }
            return value
        }

        private companion object {
            const val INITIAL_CAPACITY = 16
            const val LOAD_FACTOR = 0.75f
        }
    }

    private companion object {
        const val BAR_SIZE = 3
        val SEPARATORS = charArrayOf('\'', '-')
        const val JOINERS = "'’ʼ-‑"
        const val APOSTROPHES = "'’ʼ"

        /** Opérations qui remplacent [old] (juste avant le curseur) par [new], en gardant leur début commun. */
        fun replace(old: String, new: String): List<EditOperation> {
            val common = old.commonPrefixWith(new).length
            val ops = ArrayList<EditOperation>(2)
            if (old.length > common) ops += EditOperation.DeleteBeforeCursor(old.length - common)
            if (new.length > common) ops += EditOperation.InsertText(new.substring(common))
            return ops
        }
    }
}
