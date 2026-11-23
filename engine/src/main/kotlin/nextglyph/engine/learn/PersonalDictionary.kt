package nextglyph.engine.learn

import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.LanguageState
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.text.TextNormalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToLong

/** Effet d'une observation sur le dictionnaire personnel. */
enum class LearnOutcome {
    /** Rien n'a changé : apprentissage interdit, mot invalide ou déjà connu des lexiques. */
    IGNORED,

    /** Mot inconnu mis en attente (ou son compteur augmenté), pas encore promu. */
    PENDING,

    /** Le mot atteint [LearningConfig.minOccurrences] : il entre dans le dictionnaire personnel. */
    PROMOTED,

    /** Mot déjà du dictionnaire personnel : compteur d'utilisation augmenté. */
    USED,
}

/**
 * Mot du dictionnaire personnel.
 *
 * @property language langue à laquelle il est rattaché (`fr`, `en`), celle estimée à l'apprentissage.
 * @property form forme d'affichage la plus utilisée.
 * @property count nombre de validations (toutes formes confondues).
 */
data class PersonalWord(val language: String, val form: String, val count: Int)

/**
 * Dictionnaire personnel (spec §10.5) : les mots que l'utilisateur écrit et que les lexiques ne
 * connaissent pas.
 *
 * **Cycle.** [observe] reçoit chaque mot validé. Un mot inconnu est mis *en attente* ; à la
 * [LearningConfig.minOccurrences]-ième validation (2 par défaut) il est *promu*. L'annulation d'une
 * validation ([discard]) oublie le mot en attente : il faut recommencer, d'où « validations sans
 * annulation ». [remove] supprime un mot (appui long sur la suggestion) ; il peut être appris de
 * nouveau s'il est retapé deux fois.
 *
 * **Rattachement à la langue.** Chaque mot est rangé sous la langue estimée au moment de
 * l'apprentissage et n'enrichit que le lexique de cette langue.
 *
 * **Intégration à la prédiction.** [sets] renvoie, pour chaque langue, le [LexiconSet] du lexique
 * principal complété d'un [nextglyph.engine.lexicon.Lexicon] des mots appris, construit en mémoire
 * par [LexiconBuilder] (même format binaire que les autres, mêmes algorithmes : aucune source
 * parallèle à maintenir dans le prédicteur). Les lexiques étant immuables, il est reconstruit
 * (quelques millisecondes pour 2 000 mots) quand le dictionnaire change : l'IME rappelle [sets] quand
 * [lexiconRevision] change, puis recrée le `Predictor`. Sans mot appris, [sets] renvoie ses
 * arguments tels quels : le comportement est strictement celui d'avant.
 * La fréquence d'un mot est [LearningConfig.baseShare] fois la masse du lexique de sa langue,
 * multipliée par un facteur d'usage `min(maxUsageBoost, 1 + ln(validations))` ; chaque forme
 * d'affichage reçoit sa part selon ses validations.
 *
 * **Plafonds et éviction.** Au plus [LearningConfig.maxWords] mots (le moins récemment utilisé est
 * évincé) et [LearningConfig.maxPending] mots en attente (le moins récemment vu est oublié).
 *
 * **Quels mots.** De [LearningConfig.minWordLength] à [LearningConfig.maxWordLength] caractères,
 * lettres, apostrophes et traits d'union seulement (pas au début ni à la fin), de clé normalisée dans
 * l'alphabet des lexiques. Adresses, nombres, identifiants et lettres hors de l'alphabet (`ß`,
 * cyrillique) ne sont pas appris. Casse : la forme tapée est conservée ; en début de phrase, une simple
 * initiale majuscule est ramenée en minuscules (la majuscule vient de la phrase, pas du mot).
 * Seules les validations d'un champ qui permet l'apprentissage comptent ([LearningPolicy.allowsWords]).
 *
 * Non thread-safe : à utiliser depuis le fil de l'interface.
 */
class PersonalDictionary(val config: LearningConfig = LearningConfig()) {

    private data class Id(val language: String, val key: String)

    private class Entry {
        val forms = LinkedHashMap<String, Int>()
        fun count(): Int = forms.values.sum()
    }

    // Ordre d'insertion = ordre d'utilisation : le premier est le moins récemment utilisé.
    private val words = LinkedHashMap<Id, Entry>()
    private val pending = LinkedHashMap<Id, Entry>()
    private var cache: Cache? = null

    private class Cache(val revision: Long, val base: Map<String, LexiconSet>, val result: Map<String, LexiconSet>)

    /** Change à chaque modification (à enregistrer quand elle change). */
    var revision: Long = 0L
        private set

    /** Change quand l'ensemble des mots promus change (promotion, suppression, éviction) : l'IME reconstruit alors ses lexiques. */
    var lexiconRevision: Long = 0L
        private set

    /** Nombre de mots promus. */
    val size: Int get() = words.size

    /** Nombre de mots en attente. */
    val pendingSize: Int get() = pending.size

    /**
     * Observe la validation de [word] dans la langue estimée [language].
     *
     * @param unknown le mot n'est dans aucun lexique actif (hors dictionnaire personnel) : seul un mot
     *   inconnu est mis en attente. Un mot déjà du dictionnaire personnel est compté dans tous les cas.
     * @param atSentenceStart le mot était en début de phrase (majuscule automatique).
     */
    fun observe(
        word: String,
        language: String,
        unknown: Boolean,
        settings: PredictionSettings,
        atSentenceStart: Boolean = false,
    ): LearnOutcome = record(word, language, unknown, settings, atSentenceStart, immediate = false)

    /**
     * Adopte [word] tout de suite, sans attendre [LearningConfig.minOccurrences] : le refus répété d'une
     * correction vaut validation du mot tapé (§5.5, §10.5). Comme [observe] d'un mot inconnu, mais le mot est
     * promu (`PROMOTED`), ou compté s'il l'est déjà (`USED`) ; `IGNORED` si l'apprentissage est interdit ou le
     * mot invalide. Le mot sort de la liste d'attente.
     */
    fun adopt(word: String, language: String, settings: PredictionSettings, atSentenceStart: Boolean = false): LearnOutcome =
        record(word, language, true, settings, atSentenceStart, immediate = true)

    private fun record(
        word: String,
        language: String,
        unknown: Boolean,
        settings: PredictionSettings,
        atSentenceStart: Boolean,
        immediate: Boolean,
    ): LearnOutcome {
        if (!LearningPolicy.allowsWords(settings) || !isLanguage(language)) return LearnOutcome.IGNORED
        val typed = canonicalForm(word) ?: return LearnOutcome.IGNORED
        val form = if (atSentenceStart && hasOnlyInitialCapital(typed)) typed.lowercase(Locale.ROOT) else typed
        val id = Id(language, TextNormalizer.normalize(form))
        val known = words.remove(id)
        if (known != null) {
            addForm(known, form)
            words[id] = known
            revision++
            return LearnOutcome.USED
        }
        if (!unknown) return LearnOutcome.IGNORED
        val entry = pending.remove(id) ?: Entry()
        addForm(entry, form)
        revision++
        if (immediate || entry.count() >= config.minOccurrences) {
            promote(id, entry)
            return LearnOutcome.PROMOTED
        }
        pending[id] = entry
        if (pending.size > config.maxPending) evictOldest(pending)
        return LearnOutcome.PENDING
    }

    /**
     * Observe la validation décrite par [prediction] (cas 3 : mot inconnu inséré tel quel ; cas 1 : mot
     * connu, compté s'il est du dictionnaire personnel), dans la langue dominante de [language].
     */
    fun onValidated(prediction: Prediction, language: LanguageState, settings: PredictionSettings, atSentenceStart: Boolean = false): LearnOutcome =
        when (prediction.case) {
            SpaceCase.LITERAL -> observe(prediction.word, language.dominant, true, settings, atSentenceStart)
            SpaceCase.WORD -> observe(prediction.word, language.dominant, false, settings, atSentenceStart)
            else -> LearnOutcome.IGNORED
        }

    /**
     * Oublie le mot en attente [word] (validation annulée : le compteur repart de zéro). Faux s'il n'était pas en attente.
     */
    fun discard(word: String, language: String): Boolean {
        val id = idOf(word, language) ?: return false
        if (pending.remove(id) == null) return false
        revision++
        return true
    }

    /** Supprime [word] du dictionnaire et de la liste d'attente. Faux s'il n'y figurait pas. */
    fun remove(word: String, language: String): Boolean {
        val id = idOf(word, language) ?: return false
        val wasPending = pending.remove(id) != null
        val wasWord = words.remove(id) != null
        if (wasWord) lexiconRevision++
        if (wasPending || wasWord) revision++
        return wasPending || wasWord
    }

    /** Vrai si [word] est un mot promu de [language]. */
    fun contains(word: String, language: String): Boolean = idOf(word, language)?.let { it in words } ?: false

    /** Les mots promus de [language] (toutes les langues si `null`), triés par langue puis forme. */
    fun words(language: String? = null): List<PersonalWord> =
        words.entries.filter { language == null || it.key.language == language }
            .map { (id, entry) -> PersonalWord(id.language, dominantForm(entry), entry.count()) }
            .sortedWith(compareBy({ it.language }, { it.form.lowercase(Locale.ROOT) }, { it.form }))

    /** Les mots en attente de [language], avec leur nombre de validations (diagnostic et tests). */
    fun pendingWords(language: String? = null): List<PersonalWord> =
        pending.entries.filter { language == null || it.key.language == language }
            .map { (id, entry) -> PersonalWord(id.language, dominantForm(entry), entry.count()) }
            .sortedWith(compareBy({ it.language }, { it.form }))

    /**
     * Importe [word] comme mot promu de [language], avec [count] utilisations (importation,
     * restauration ; §14). Il devient le plus récemment utilisé. Faux si le mot, la langue ou le
     * compteur (`1..maxCount`) sont invalides ; la casse est conservée telle quelle.
     */
    fun importWord(word: String, language: String, count: Int): Boolean {
        if (!isLanguage(language) || count !in 1..config.maxCount) return false
        val form = canonicalForm(word) ?: return false
        val id = Id(language, TextNormalizer.normalize(form))
        pending.remove(id)
        val entry = words.remove(id) ?: Entry()
        entry.forms.merge(form, count) { a, b -> min(config.maxCount.toLong(), a.toLong() + b).toInt() }
        if (entry.forms.size > config.maxFormsPerWord) {
            entry.forms.entries.minBy { it.value }.let { entry.forms.remove(it.key) }
        }
        promote(id, entry)
        return true
    }

    /**
     * Copie indépendante (mots promus et en attente, ordre d'utilisation compris) : l'IME la passe à un
     * fil d'arrière-plan pour reconstruire les lexiques ([sets]) sans partager cet objet non thread-safe.
     */
    fun copy(): PersonalDictionary {
        val copy = PersonalDictionary(config)
        val (promoted, waiting) = snapshot()
        copy.restore(promoted, waiting)
        return copy
    }

    /** Oublie tout : mots promus et en attente (§12). */
    fun clear() {
        if (words.isNotEmpty()) lexiconRevision++
        words.clear()
        pending.clear()
        revision++
    }

    /**
     * Lexiques de la prédiction augmentés des mots appris (voir la documentation de la classe).
     * Renvoie [base] lui-même si aucun mot n'est promu. Le résultat est gardé tant que [revision] ne change pas.
     */
    fun sets(base: Map<String, LexiconSet>): Map<String, LexiconSet> {
        if (words.isEmpty()) return base
        cache?.let { if (it.revision == revision && it.base === base) return it.result }
        val byLanguage = words.entries.groupBy({ it.key.language }, { it.value })
        val result = LinkedHashMap<String, LexiconSet>()
        for ((language, set) in base) {
            val entries = byLanguage[language]
            result[language] = if (entries == null) set else set + lexiconOf(set, entries)
        }
        cache = Cache(revision, base, result)
        return result
    }

    private fun lexiconOf(set: LexiconSet, entries: List<Entry>): Lexicon {
        val builder = LexiconBuilder(set.languageTag)
        val unit = set.totalMass * config.baseShare
        for (entry in entries) {
            val count = entry.count()
            val total = unit * min(config.maxUsageBoost, 1.0 + ln(count.toDouble()))
            for ((form, n) in entry.forms) builder.add(form, maxOf(1L, (total * n / count).roundToLong()))
        }
        return builder.build()
    }

    /** Contenu pour l'enregistrement : mots promus puis en attente, du moins récent au plus récent. */
    internal fun snapshot(): Pair<List<StoredWord>, List<StoredWord>> =
        words.map { (id, e) -> StoredWord(id.language, e.forms.toList()) } to
            pending.map { (id, e) -> StoredWord(id.language, e.forms.toList()) }

    /**
     * Remplace le contenu par celui d'un enregistrement.
     *
     * @throws IllegalArgumentException si une entrée est invalide ou dépasse un plafond ; rien n'est alors modifié.
     */
    internal fun restore(promoted: List<StoredWord>, waiting: List<StoredWord>) {
        require(promoted.size <= config.maxWords) { "Trop de mots (${promoted.size})." }
        require(waiting.size <= config.maxPending) { "Trop de mots en attente (${waiting.size})." }
        val newWords = rebuild(promoted)
        val newPending = rebuild(waiting)
        require(newWords.keys.none { it in newPending }) { "Un mot est à la fois promu et en attente." }
        words.clear()
        words.putAll(newWords)
        pending.clear()
        pending.putAll(newPending)
        lexiconRevision++
        revision++
    }

    private fun rebuild(stored: List<StoredWord>): LinkedHashMap<Id, Entry> {
        val result = LinkedHashMap<Id, Entry>()
        for (word in stored) {
            require(isLanguage(word.language)) { "Langue invalide : « ${word.language} »." }
            require(word.forms.size in 1..config.maxFormsPerWord) { "Nombre de formes invalide (${word.forms.size})." }
            var key: String? = null
            val entry = Entry()
            for ((form, count) in word.forms) {
                require(canonicalForm(form) == form) { "Forme invalide : « $form »." }
                require(count in 1..config.maxCount) { "Compteur invalide ($count) pour « $form »." }
                val formKey = TextNormalizer.normalize(form)
                require(key == null || key == formKey) { "Formes de clés différentes pour « $form »." }
                key = formKey
                require(entry.forms.put(form, count) == null) { "Forme en double : « $form »." }
            }
            require(result.put(Id(word.language, key!!), entry) == null) { "Mot en double : « $key »." }
        }
        return result
    }

    private fun promote(id: Id, entry: Entry) {
        words[id] = entry
        if (words.size > config.maxWords) evictOldest(words)
        lexiconRevision++
        revision++
    }

    private fun evictOldest(map: LinkedHashMap<Id, Entry>) {
        val iterator = map.keys.iterator()
        iterator.next()
        iterator.remove()
    }

    private fun addForm(entry: Entry, form: String) {
        if (entry.count() < config.maxCount) entry.forms.merge(form, 1, Int::plus)
        if (entry.forms.size > config.maxFormsPerWord) {
            entry.forms.entries.minBy { it.value }.let { entry.forms.remove(it.key) }
        }
    }

    private fun dominantForm(entry: Entry): String = entry.forms.entries.maxBy { it.value }.key

    private fun idOf(word: String, language: String): Id? {
        if (!isLanguage(language)) return null
        val form = canonicalForm(word) ?: return null
        return Id(language, TextNormalizer.normalize(form))
    }

    /**
     * Forme apprenable de [word] (apostrophes droites), ou `null` : voir la documentation de la classe.
     */
    internal fun canonicalForm(word: String): String? {
        val form = word.replace('’', '\'').replace('ʼ', '\'')
        if (form.length !in config.minWordLength..config.maxWordLength) return null
        if (!form.all { it.isLetter() || it == '\'' || it == '-' }) return null
        if (!form.first().isLetter() || !form.last().isLetter()) return null
        val key = TextNormalizer.normalize(form)
        return if (LexiconAlphabet.isValidKey(key)) form else null
    }

    private fun hasOnlyInitialCapital(form: String): Boolean =
        form[0].isUpperCase() && form.drop(1).none { it.isUpperCase() }

    private fun isLanguage(language: String): Boolean = language.length in 2..3 && language.all { it in 'a'..'z' }
}

/** Mot du dictionnaire personnel tel qu'enregistré : langue et formes avec leur compteur. */
internal class StoredWord(val language: String, val forms: List<Pair<String, Int>>)
