package nextglyph.engine.predict

import nextglyph.engine.input.EditOperation
import nextglyph.engine.input.TextEdit
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.lexicon.Distribution
import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.lexicon.LexiconSet
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
 * @param lexicons lexiques actifs par code de langue (`fr`, `en`) ; l'ordre est celui des langues
 *   du [LanguageState] attendu.
 * @param layout disposition courante (adjacence des touches pour la correction).
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
) {
    /** Codes des langues actives. */
    val languages: List<String> = lexicons.keys.toList()

    private val sets: List<LexiconSet> = languages.map { lexicons.getValue(it) }
    private val adjacency = KeyAdjacency(layout, config.neighborDistance)
    private val sources: List<CandidateSource> = sets.map { CandidateSource(it, adjacency, config) }
    private val display = DisplayForms(config.accentDominance)
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

    /** État de langue de départ, [preferred] étant la langue préférée (§10.6, §14). */
    fun initialLanguageState(preferred: String): LanguageState =
        LanguageState.initial(languages, preferred, config.languageWindow, config.languagePrior, config.preferredLanguageShare)

    /**
     * Décision de l'espace et barre de suggestions pour le texte [textBeforeCursor] (au moins
     * `WordPrefix.LOOKBACK` caractères ; `null` si indisponible).
     *
     * @throws IllegalArgumentException si [language] ne porte pas sur les langues de ce prédicteur.
     */
    fun predict(textBeforeCursor: CharSequence?, language: LanguageState, settings: PredictionSettings): Prediction {
        requireLanguages(language)
        val sentence = language.sentenceLanguage
        if (!settings.suggestionsEnabled) return Prediction("", "", SpaceCase.DISABLED, "", emptyList(), SuggestionBar.EMPTY, sentence)
        val raw = WordPrefix.currentWord(textBeforeCursor, 0, elisionLanguage(language)).orEmpty()
        if (raw.isEmpty()) return Prediction("", "", SpaceCase.NO_WORD, "", emptyList(), SuggestionBar.EMPTY, sentence)
        val key = keyOf(raw) ?: return Prediction(raw, "", SpaceCase.LITERAL, raw, emptyList(), SuggestionBar.EMPTY, sentence)
        return decide(raw, key, language, settings)
    }

    /**
     * Décision pour le mot [raw] de clé [key] déjà extraits (simulations, mesures) ; voir [predict].
     */
    fun decide(raw: String, key: String, language: LanguageState, settings: PredictionSettings): Prediction {
        requireLanguages(language)
        val weights = languageWeights(language)
        val active = sets.filterIndexed { i, _ -> weights[i] > 0.0 }
        val sentenceSet = sets[languages.indexOf(language.sentenceLanguage)]
        var isWord = false
        var total = 0.0
        val scores = LinkedHashMap<String, ScoredKey>()
        for (i in sets.indices) {
            val pi = weights[i]
            if (pi <= 0.0) continue
            val found = caches?.get(i)?.getOrPut(key) { sources[i].lookup(key) } ?: sources[i].lookup(key)
            isWord = isWord || found.isWord
            total += pi * found.total
            for (c in found.top) {
                val previous = scores[c.key]
                scores[c.key] = c.copy(score = (previous?.score ?: 0.0) + pi * c.score)
            }
        }
        // Les candidats des langues sont fusionnés puis ramenés aux formsPerPrefix meilleurs.
        val ranked = if (total <= 0.0) emptyList() else scores.values.sortedWith(CandidateSource.SCORE_ORDER).take(config.formsPerPrefix).map {
            Candidate(it.key, display(raw, it, active, sentenceSet), it.score / total)
        }
        val p1 = ranked.getOrNull(0)?.probability ?: 0.0
        val p2 = ranked.getOrNull(1)?.probability ?: 0.0
        val case: SpaceCase
        val inserted: String
        when {
            isWord -> {
                case = SpaceCase.WORD
                val restored = if (settings.autocommitEnabled) display.forTypedWord(raw, key, active, sentenceSet) else raw
                inserted = if (restored != raw && isRejected(raw, restored)) raw else restored
            }
            settings.autocommitEnabled && ranked.isNotEmpty() && p1 >= settings.prudence.minProb &&
                p1 >= config.minRatio * p2 && !isRejected(raw, ranked[0].text) -> {
                case = SpaceCase.DOMINANT
                inserted = ranked[0].text
            }
            else -> {
                case = SpaceCase.LITERAL
                inserted = raw
            }
        }
        val bar = bar(raw, key, case, inserted, ranked, active)
        return Prediction(raw, key, case, inserted, ranked, bar, language.sentenceLanguage)
    }

    /**
     * Validation par espace (§5.5) : remplace le mot en cours par [Prediction.inserted] et ajoute
     * une espace automatique. Sans mot en cours, ou suggestions désactivées, insère une simple espace.
     */
    fun commitSpace(prediction: Prediction, language: LanguageState, settings: PredictionSettings): Commit {
        if (prediction.case == SpaceCase.NO_WORD || prediction.case == SpaceCase.DISABLED) {
            return Commit(TextEdit(listOf(EditOperation.Insert(" "))), null, language)
        }
        val edit = TextEdit(replace(prediction.word, prediction.inserted + " "), endsWithAutoSpace = true)
        val undo = if (prediction.inserted != prediction.word) UndoToken(prediction.word, prediction.inserted) else null
        return Commit(edit, undo, observe(language, prediction.inserted, settings))
    }

    /**
     * Validation du mot en cours avant un signe de ponctuation (§5.6, « Pendant un mot en cours ») :
     * mêmes règles que l'espace, sans espace automatique ; le signe est ensuite appliqué par le
     * module de ponctuation. Pas de jeton d'annulation : le ⌫ suivant efface le signe.
     */
    fun commitBeforePunctuation(prediction: Prediction, language: LanguageState, settings: PredictionSettings): Commit {
        if (prediction.case == SpaceCase.NO_WORD || prediction.case == SpaceCase.DISABLED) return Commit(TextEdit.NONE, null, language)
        return Commit(TextEdit(replace(prediction.word, prediction.inserted)), null, observe(language, prediction.inserted, settings))
    }

    /** Tap sur une suggestion (§5.4) : remplace le mot en cours par [suggestion] et ajoute une espace automatique. */
    fun pickSuggestion(prediction: Prediction, suggestion: Suggestion, language: LanguageState, settings: PredictionSettings): Commit {
        val edit = TextEdit(replace(prediction.word, suggestion.text + " "), endsWithAutoSpace = true)
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

    private fun display(raw: String, candidate: ScoredKey, active: List<LexiconSet>, sentenceSet: LexiconSet): String {
        if (candidate.restStart == 0) return display.forCandidate(raw, candidate.key, active, sentenceSet)
        // Élision reconstituée : l'élision garde la casse tapée, le mot élidé est restitué.
        val elisionLength = candidate.restStart - 1
        val head = raw.take(elisionLength)
        val rest = display.forCandidate(raw.drop(elisionLength).lowercase(Locale.ROOT), candidate.key.substring(candidate.restStart), active, sentenceSet)
        return "$head'$rest"
    }

    private fun isRejected(raw: String, inserted: String): Boolean = rejections.rejections(raw, inserted) >= config.rejectLimit

    private fun bar(raw: String, key: String, case: SpaceCase, inserted: String, ranked: List<Candidate>, active: List<LexiconSet>): SuggestionBar {
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
                for (variant in variants(raw, key, active)) add(Suggestion(variant))
            }
            SpaceCase.DOMINANT -> {
                add(Suggestion(inserted, highlighted = true))
                add(Suggestion(raw, isTyped = true))
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
    private fun variants(raw: String, key: String, active: List<LexiconSet>): List<String> {
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
                    result += raw.take(elision.length) + "'" + display.forCandidate(raw.drop(elision.length).lowercase(Locale.ROOT), rest, active, sets[frenchIndex])
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

        /** Opérations qui remplacent [old] (juste avant le curseur) par [new], en gardant leur début commun. */
        fun replace(old: String, new: String): List<EditOperation> {
            val common = old.commonPrefixWith(new).length
            val ops = ArrayList<EditOperation>(2)
            if (old.length > common) ops += EditOperation.DeleteBefore(old.length - common)
            if (new.length > common) ops += EditOperation.Insert(new.substring(common))
            return ops
        }
    }
}
