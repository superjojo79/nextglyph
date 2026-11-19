package nextglyph.tools.wordlists

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/** Résultat de construction : entrées, exclusions par motif, et compteurs descriptifs. */
data class WordListResult(
    val entries: List<WordEntry>,
    val exclusions: Map<String, Int>,
    val counters: Map<String, Int>,
)

/** Plus grande fréquence écrite : tient dans un `Int` côté moteur. */
const val MAX_FREQUENCY: Long = Int.MAX_VALUE.toLong()

/** Accumule les exclusions par motif, dans l'ordre d'apparition. */
internal class ExclusionLog {
    private val counts = LinkedHashMap<String, Int>()
    fun add(label: String) {
        counts.merge(label, 1, Int::plus)
    }
    fun asMap(): Map<String, Int> = counts
}

/**
 * Applique [WordFilter] à des (forme, fréquence) ; les fréquences sont ramenées dans [1, MAX_FREQUENCY].
 */
internal fun filterEntries(
    candidates: List<Pair<String, Long>>,
    log: ExclusionLog,
    extraCheck: (String) -> String? = { null },
): List<WordEntry> {
    val entries = ArrayList<WordEntry>(candidates.size)
    for ((display, frequency) in candidates) {
        val verdict = WordFilter.check(display)
        if (verdict is WordFilter.Verdict.Rejected) {
            log.add(verdict.reason.label)
            continue
        }
        val extra = extraCheck(display)
        if (extra != null) {
            log.add(extra)
            continue
        }
        entries += WordEntry(display, frequency.coerceIn(1L, MAX_FREQUENCY))
    }
    return entries
}

/** Tri de sortie : fréquence décroissante, puis forme (ordre des unités de code UTF-16). */
fun sortedForOutput(entries: List<WordEntry>): List<WordEntry> =
    entries.sortedWith(compareByDescending<WordEntry> { it.frequency }.thenBy { it.display })

object FrenchWordList {

    /** Fréquence par million -> entier : ×100, arrondi, minimum 1 (soit 0,01 par million). */
    const val SCALE: Double = 100.0

    /** Fréquence d'un mot absent de Lexique 4.10 (ou de fréquence nulle). */
    const val FLOOR: Long = 1L

    const val EXCLUSION_DUPLICATE = "forme en double fusionnée"

    fun scaled(perMillion: Double): Long =
        if (perMillion.isNaN() || perMillion <= 0.0) FLOOR else (perMillion * SCALE).roundToLong().coerceIn(FLOOR, MAX_FREQUENCY)

    /**
     * Vocabulaire = formes de Dicollecte + formes de Lexique absentes de Dicollecte (à la casse près).
     * Fréquence = Lexique si la forme y est, sinon [FLOOR]. Pour un groupe de variantes de casse
     * (`paris`, `Paris`), la forme tout en minuscules porte la fréquence de Lexique (qui ne
     * distingue pas la casse) ; s'il n'y a pas de variante minuscule, toutes la portent.
     */
    fun build(dicollecte: Collection<String>, lexique: Map<String, Double>): WordListResult {
        val log = ExclusionLog()
        val dicoForms = LinkedHashSet<String>()
        for (form in dicollecte) {
            if (!dicoForms.add(canonicalApostrophes(form))) log.add(EXCLUSION_DUPLICATE)
        }
        val dicoLower = dicoForms.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val lexiqueOnly = lexique.keys.filter { it !in dicoLower }
        val groups = (dicoForms + lexiqueOnly).groupBy { it.lowercase(Locale.ROOT) }

        var floored = 0
        val candidates = ArrayList<Pair<String, Long>>()
        for ((lower, forms) in groups) {
            val perMillion = lexique[lower]
            val owner = if (lower in forms) lower else null
            for (form in forms) {
                val fromLexique = perMillion != null && (owner == null || form == owner)
                if (!fromLexique) floored++
                candidates += form to (if (perMillion != null && fromLexique) scaled(perMillion) else FLOOR)
            }
        }
        val entries = filterEntries(candidates, log)
        return WordListResult(
            entries,
            log.asMap(),
            mapOf(
                "formes Dicollecte" to dicoForms.size,
                "formes Lexique seul" to lexiqueOnly.size,
                "formes à fréquence plancher" to floored,
            ),
        )
    }
}

/** Comptages de mots d'un corpus, séparant le premier mot de phrase (majuscule d'initiale). */
class TokenCounter {
    private val inside = HashMap<String, Long>()
    private val initial = HashMap<String, Long>()
    var totalTokens: Long = 0
        private set

    fun addSentence(tokens: List<String>) {
        tokens.forEachIndexed { index, token ->
            totalTokens++
            val map = if (index == 0) initial else inside
            map.merge(token, 1L, Long::plus)
        }
    }

    fun occurrences(form: String): Long = (inside[form] ?: 0L) + (initial[form] ?: 0L)

    fun initialOccurrences(form: String): Long = initial[form] ?: 0L
}

object EnglishWordList {

    /** Niveau SCOWL maximal retenu : 70 = vocabulaire courant (au-delà : sources à attribution spéciale). */
    const val MAX_LEVEL: Int = 70

    const val EXCLUSION_DUPLICATE = "forme en double fusionnée"
    const val EXCLUSION_POSSESSIVE = "possessif 's jamais vu dans le corpus"

    /**
     * A priori de niveau : 1000 au niveau 10, divisé par deux tous les 10 niveaux, minimum 1
     * (10 -> 1000, 20 -> 500, 35 -> 176, 50 -> 62, 60 -> 31, 70 -> 15, 95 -> 2).
     */
    fun prior(level: Int): Long = (1000.0 * 0.5.pow((level - 10) / 10.0)).toLong().coerceAtLeast(1L)

    /**
     * Fréquence = occurrences dans la partie entraînement + a priori du niveau (le plus courant
     * des niveaux de la forme). Un mot sans occurrence reçoit donc exactement le plancher de son niveau.
     *
     * Un premier mot de phrase capitalisé (`The`, `Tom`) est compté pour la forme d'affichage
     * correspondante : `the` si le lexique n'a pas `The`, sinon `The`.
     * Un possessif en `'s` (hors contractions) jamais vu dans le corpus est exclu.
     */
    fun build(scowl: List<ScowlEntry>, counter: TokenCounter): WordListResult {
        val log = ExclusionLog()
        val levels = LinkedHashMap<String, Int>()
        val contractions = HashSet<String>()
        for (entry in scowl) {
            val previous = levels[entry.form]
            if (previous != null) log.add(EXCLUSION_DUPLICATE)
            levels[entry.form] = if (previous == null) entry.level else minOf(previous, entry.level)
            if (entry.category == ScowlCategory.CONTRACTION) contractions += entry.form
        }
        val counts = levels.keys.associateWith { form ->
            val capitalized = form.replaceFirstChar { it.uppercase(Locale.ROOT) }
            val initialOfCapitalized =
                if (capitalized != form && capitalized !in levels) counter.initialOccurrences(capitalized) else 0L
            counter.occurrences(form) + initialOfCapitalized
        }
        val candidates = levels.map { (form, level) -> form to counts.getValue(form) + prior(level) }
        val entries = filterEntries(candidates, log) { form ->
            val possessive = form.endsWith("'s") && form !in contractions
            if (possessive && counts.getValue(form) == 0L) EXCLUSION_POSSESSIVE else null
        }
        val unseen = entries.count { counts.getValue(it.display) == 0L }
        return WordListResult(
            entries,
            log.asMap(),
            mapOf("formes SCOWL" to levels.size, "formes sans occurrence (plancher de niveau)" to unseen),
        )
    }
}
