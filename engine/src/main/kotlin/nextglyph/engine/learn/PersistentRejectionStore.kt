package nextglyph.engine.learn

import nextglyph.engine.predict.RejectionStore
import java.util.Locale

/**
 * Mémoire des refus persistante (spec §5.5, « Apprentissage du refus ») : implémentation de
 * [RejectionStore] dont le contenu s'enregistre avec [LearnedDataFile].
 *
 * Même sémantique que `InMemoryRejectionStore` (paires en minuscules), avec un plafond de
 * [LearningConfig.maxRejections] paires : au-delà, la paire refusée le moins récemment est oubliée.
 * Un compteur est plafonné à [LearningConfig.maxRejectionCount]. Le prédicteur n'écrit jamais en
 * incognito ni en mot de passe ; ce magasin ne le sait pas et écrit ce qu'on lui demande.
 *
 * Non thread-safe.
 */
class PersistentRejectionStore(val config: LearningConfig = LearningConfig()) : RejectionStore {

    // Ordre d'insertion = ordre de refus : le premier est le moins récent.
    private val counts = LinkedHashMap<Pair<String, String>, Int>()

    /** Change à chaque modification. */
    var revision: Long = 0L
        private set

    /** Nombre de paires mémorisées. */
    val size: Int get() = counts.size

    override fun rejections(typed: String, inserted: String): Int = counts[pair(typed, inserted)] ?: 0

    override fun recordRejection(typed: String, inserted: String) {
        val key = pair(typed, inserted)
        val count = counts.remove(key) ?: 0
        counts[key] = minOf(config.maxRejectionCount, count + 1)
        if (counts.size > config.maxRejections) {
            val iterator = counts.keys.iterator()
            iterator.next()
            iterator.remove()
        }
        revision++
    }

    /** Un refus mémorisé : [count] annulations de la correction [typed] → [inserted] (en minuscules). */
    data class Refusal(val typed: String, val inserted: String, val count: Int)

    /** Les paires dont le compteur atteint [limit] (corrections qui ne s'appliquent plus), la plus récemment refusée d'abord. */
    fun learned(limit: Int): List<Refusal> =
        counts.entries.filter { it.value >= limit }.map { (key, count) -> Refusal(key.first, key.second, count) }.asReversed()

    /** Oublie la paire ([typed], [inserted]) (insensible à la casse). Faux si elle n'était pas mémorisée. */
    fun forget(typed: String, inserted: String): Boolean {
        if (counts.remove(pair(typed, inserted)) == null) return false
        revision++
        return true
    }

    /** Oublie tous les refus (§12). */
    fun clear() {
        counts.clear()
        revision++
    }

    /** Contenu pour l'enregistrement, du moins récent au plus récent : (texte tapé, texte inséré, compteur). */
    internal fun snapshot(): List<Triple<String, String, Int>> = counts.map { (key, count) -> Triple(key.first, key.second, count) }

    /**
     * Remplace le contenu par celui d'un enregistrement.
     *
     * @throws IllegalArgumentException si une entrée est invalide ou dépasse un plafond ; rien n'est alors modifié.
     */
    internal fun restore(entries: List<Triple<String, String, Int>>) {
        require(entries.size <= config.maxRejections) { "Trop de refus (${entries.size})." }
        val rebuilt = LinkedHashMap<Pair<String, String>, Int>()
        for ((typed, inserted, count) in entries) {
            require(typed.isNotEmpty() && typed.length <= MAX_TEXT_LENGTH && inserted.isNotEmpty() && inserted.length <= MAX_TEXT_LENGTH) {
                "Texte de refus invalide."
            }
            require(count in 1..config.maxRejectionCount) { "Compteur de refus invalide ($count)." }
            require(rebuilt.put(pair(typed, inserted), count) == null) { "Refus en double : « $typed » → « $inserted »." }
        }
        counts.clear()
        counts.putAll(rebuilt)
        revision++
    }

    private fun pair(typed: String, inserted: String) = typed.lowercase(Locale.ROOT) to inserted.lowercase(Locale.ROOT)

    internal companion object {
        /** Longueur maximale d'un texte mémorisé : `TextNormalizer.MAX_INPUT_LENGTH` doublé par les ligatures. */
        const val MAX_TEXT_LENGTH = 130
    }
}
