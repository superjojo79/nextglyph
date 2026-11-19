package nextglyph.engine.contacts

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.text.TextNormalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Un contact tel que fourni par l'IME (spec §6.2) : les champs `StructuredName` et `Nickname` de
 * `ContactsContract.Data`. Chaque champ peut contenir plusieurs mots (« Marie Claire ») ; les noms
 * composés avec trait d'union (« Jean-Pierre ») sont gardés entiers.
 */
data class ContactName(
    val givenName: String? = null,
    val familyName: String? = null,
    val middleName: String? = null,
    val nickname: String? = null,
)

/**
 * Un nom du sous-lexique.
 *
 * @property form forme d'affichage (`Hélène`, `Jean-Pierre`).
 * @property key clé normalisée (`helene`, `jean-pierre`).
 * @property contacts nombre de contacts qui portent cette forme.
 */
data class ContactEntry(val form: String, val key: String, val contacts: Int)

/**
 * Noms des contacts dans la prédiction (spec §6.3) : sous-lexique, bonus de contexte et garde-fous.
 *
 * **Collecte.** Chaque mot de chaque champ (prénom, nom, deuxième prénom, surnom si
 * [ContactsConfig.includeNicknames]) est indexé sous sa clé normalisée (`helene`) avec sa forme
 * d'affichage (`Hélène`) ; un nom tout en minuscules ou tout en majuscules (`DUPONT`) prend la
 * casse d'un nom propre, un nom à casse mixte (`McDonald`) est gardé tel quel. Un nom composé
 * (`Jean-Pierre`, `d'Artagnan`) est indexé en entier. Sont ignorés : les mots trop courts ou trop
 * longs, ceux qui contiennent autre chose que des lettres, apostrophes et traits d'union, ceux hors
 * de l'alphabet des lexiques, les particules (`de`, `van`... mots courants déjà connus), et tout
 * au-delà de [ContactsConfig.maxNames] noms.
 *
 * **Poids.** De base, celui d'un mot de fréquence moyenne : [ContactsConfig.baseShare] fois la masse
 * du lexique de chaque langue, croissant avec le nombre de contacts qui portent le nom
 * (`1 + ln n`, plafonné). Un même sous-lexique est ajouté à chaque langue (les noms ne sont ni
 * français ni anglais).
 *
 * **Bonus de contexte.** [levelOf] compte les indices du [ContactContext] (jusqu'à
 * [ContactsConfig.maxBoostLevel]) ; le poids des noms est multiplié par
 * [ContactsConfig.contextBoost]`^niveau`. Les lexiques étant immuables, un jeu de lexiques est construit
 * par niveau, une fois pour toutes, à la construction : [sets] donne celui d'un niveau, et l'IME
 * utilise le `Predictor` du niveau courant (trois instances au plus, partageant disposition et
 * mémoire des refus).
 *
 * **Jamais un nom à la place d'un mot courant.**
 * - Au niveau 0 (aucun indice), la fréquence d'un nom qui est aussi un mot du lexique principal est
 *   plafonnée à [ContactsConfig.commonWordCap] fois celle du mot : `rose` reste `rose`, `pierre`
 *   reste `pierre` (la restitution de la casse suit la forme la plus fréquente).
 * - Un mot tapé qui est un mot connu n'est jamais remplacé par un autre (§5.5, cas 1), nom ou non.
 * - Au niveau 0, un nom propre à un contact ne peut pas non plus être inséré par complétion ou
 *   correction ([autocommitGuard]) ; il reste proposé dans la barre. Dès qu'un indice le justifie
 *   (niveau ≥ 1), il peut l'être.
 *
 * Immuable après construction ; utilisable depuis plusieurs fils d'exécution.
 *
 * @param bases lexiques principaux par langue, auxquels s'ajoutent les noms.
 */
class ContactDirectory(
    contacts: List<ContactName>,
    private val bases: Map<String, LexiconSet>,
    val config: ContactsConfig = ContactsConfig(),
) {

    /** Les noms indexés, triés par clé puis forme. */
    val entries: List<ContactEntry> = collect(contacts)

    private val keys: Set<String> = entries.mapTo(HashSet()) { it.key }

    private val byLevel: List<Map<String, LexiconSet>> = (0..config.maxBoostLevel).map { build(it) }

    /** Vrai si aucun nom n'a été retenu. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Vrai si [key] (clé normalisée) est un nom de contact. */
    fun isName(key: String): Boolean = key in keys

    /**
     * Lexiques de la prédiction au niveau de contexte [level] : les lexiques principaux et les noms
     * pondérés. Ce sont les lexiques principaux eux-mêmes s'il n'y a aucun nom.
     *
     * @throws IllegalArgumentException si [level] n'est pas dans `0..maxBoostLevel`.
     */
    fun sets(level: Int): Map<String, LexiconSet> {
        require(level in 0..config.maxBoostLevel) { "Niveau de contexte hors de 0..${config.maxBoostLevel} : $level." }
        return byLevel[level]
    }

    /**
     * Garde-fou à passer à `Predictor(blockedAutocommit = …)` pour le niveau [level] : au niveau 0, aucun
     * nom de contact n'est inséré automatiquement ; ensuite, tout est permis.
     */
    fun autocommitGuard(level: Int): (String) -> Boolean =
        if (level == 0) ({ key -> key in keys }) else NEVER

    /** Niveau de bonus du [context], de 0 (aucun indice) à [ContactsConfig.maxBoostLevel]. */
    fun levelOf(context: ContactContext): Int = min(signals(context), config.maxBoostLevel)

    /** Nombre d'indices du §6.3 réunis par [context]. */
    fun signals(context: ContactContext): Int {
        var count = 0
        if (context.personNameField) count++
        if (context.atMessageStart) count++
        if (context.typedCapital) count++
        val previous = context.previousWord?.trim().orEmpty()
        if (previous.lowercase(Locale.ROOT) in config.triggerWords || isCapitalizedName(previous)) count++
        return count
    }

    private fun isCapitalizedName(word: String): Boolean {
        if (word.isEmpty() || word.length > TextNormalizer.MAX_INPUT_LENGTH || !word[0].isUpperCase()) return false
        val key = try {
            TextNormalizer.normalize(word)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return key in keys
    }

    private fun collect(contacts: List<ContactName>): List<ContactEntry> {
        val counts = HashMap<String, Int>()
        for (contact in contacts) {
            val fields = listOfNotNull(contact.givenName, contact.middleName, contact.familyName) +
                (if (config.includeNicknames) listOfNotNull(contact.nickname) else emptyList())
            // Un nom répété dans un même contact (« Martin Martin ») ne compte qu'une fois.
            val forms = fields.flatMap { it.split(WHITESPACE) }.mapNotNull(::displayForm).toSet()
            for (form in forms) counts.merge(form, 1, Int::plus)
        }
        return counts.map { (form, n) -> ContactEntry(form, TextNormalizer.normalize(form), n) }
            .sortedWith(compareBy({ it.key }, { it.form }))
            .take(config.maxNames)
    }

    /** Forme d'affichage de [token], ou `null` s'il n'est pas un nom indexable. */
    private fun displayForm(token: String): String? {
        val word = token.replace('’', '\'').replace('ʼ', '\'')
        if (word.length !in config.minNameLength..config.maxNameLength) return null
        if (!word.all { it.isLetter() || it == '\'' || it == '-' }) return null
        if (!word.first().isLetter() || !word.last().isLetter()) return null
        val key = TextNormalizer.normalize(word)
        if (!LexiconAlphabet.isValidKey(key) || key in PARTICLES) return null
        val letters = word.filter { it.isLetter() }
        // Deux majuscules (« JP ») sont des initiales : gardées telles quelles.
        val flat = letters == letters.lowercase(Locale.ROOT) || (letters == letters.uppercase(Locale.ROOT) && letters.length > 2)
        return if (flat) properCase(word) else word
    }

    private fun properCase(word: String): String = buildString {
        var start = true
        for (c in word) {
            append(if (start) c.titlecaseChar() else c.lowercaseChar())
            start = c == '-' || c == '\''
        }
    }

    private fun build(level: Int): Map<String, LexiconSet> {
        if (entries.isEmpty()) return bases
        val result = LinkedHashMap<String, LexiconSet>()
        for ((language, set) in bases) result[language] = set + lexicon(set, level)
        return result
    }

    private fun lexicon(set: LexiconSet, level: Int): Lexicon {
        val builder = LexiconBuilder(set.languageTag)
        val unit = set.totalMass * config.baseShare * config.contextBoost.pow(level)
        for (entry in entries) {
            val multiplicity = min(config.maxMultiplicity, 1.0 + ln(entry.contacts.toDouble()))
            var frequency = maxOf(1L, (unit * multiplicity).roundToLong())
            if (level == 0) {
                // Mot courant : le nom ne doit pas lui voler sa forme (casse) ni ses accents.
                val common = set.displayForms(entry.key).sumOf { it.frequency.toDouble() }
                if (common > 0.0) frequency = maxOf(1L, min(frequency.toDouble(), config.commonWordCap * common).roundToLong())
            }
            builder.add(entry.form, frequency)
        }
        return builder.build()
    }

    private companion object {
        val NEVER: (String) -> Boolean = { false }
        val WHITESPACE = Regex("\\s+")

        /** Particules de noms : des mots courants, jamais indexés comme noms. */
        val PARTICLES: Set<String> = setOf(
            "de", "du", "des", "la", "le", "les", "van", "von", "der", "den", "di", "da", "del", "el", "al", "bin", "ben", "of", "the",
        )
    }
}
