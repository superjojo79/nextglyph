package nextglyph.engine.predict

import nextglyph.engine.lexicon.LexiconSet

/**
 * Un mot candidat (clé normalisée) et son score.
 *
 * @property restStart pour une élision française reconstituée (`l'ecole`), index du début du mot
 *   élidé dans [key] (après l'apostrophe) ; 0 sinon.
 */
data class ScoredKey(val key: String, val score: Double, val restStart: Int = 0)

/**
 * Candidats d'une langue pour un texte tapé.
 *
 * @property isWord le texte tapé est un mot du lexique (cas 1 du §5.5).
 * @property knownPrefix le texte tapé est un préfixe connu.
 * @property total masse de tous les préfixes candidats pondérés, divisée par la masse totale du
 *   lexique (comparable d'une langue à l'autre) : dénominateur de `P(w)`.
 * @property top meilleurs candidats, par score décroissant (même échelle que [total]).
 */
class LanguageCandidates(val isWord: Boolean, val knownPrefix: Boolean, val total: Double, val top: List<ScoredKey>)

/**
 * Candidats de la validation par espace et de la barre (spec §5.5, §10.4) pour une langue, portés
 * de l'analyse M0 (`CandidateSource` de `nextglyph.tools.analysis`) :
 *
 * - **complétions** du texte tapé `t` s'il est un préfixe connu, de score `fréquence(w)` ;
 * - **corrections** : complétions des préfixes connus à distance d'édition 1 de `t` (substitution
 *   pondérée par l'adjacence des touches, omission, caractère en trop, inversion, apostrophe ou
 *   trait d'union omis), de score `poids(édition) × fréquence(w)`, **même si `t` est un préfixe
 *   connu**, dès qu'il n'est pas un mot (§5.5 révisé) ;
 * - **élisions** (langues de [PredictionConfig.elisions]) : si `t = e + r` où `e` est une élision
 *   sans apostrophe (`l`, `qu`...) et `r` un préfixe connu commençant par une voyelle ou un `h`, les
 *   complétions `w` de `r` donnent le candidat `e'w` (`lecole` → `l'ecole`), de score
 *   `separator × f(e') / masse(voyelles et h) × fréquence(w)` : la probabilité de l'élision devant
 *   un mot qui l'admet, multipliée par le coût d'une apostrophe omise. Si `r` est lui-même un mot,
 *   seul `e'r` est candidat (`dautre` → `d'autre`, pas `d'autres`), comme au cas 1 du §5.5.
 *
 * `P(w) = score(w) / total`, où `total` additionne les masses pondérées de tous les préfixes
 * candidats ; un mot atteint par deux éditions garde son meilleur score (approximation de M0).
 * Le score d'une clé additionne ses formes parmi les [PredictionConfig.formsPerPrefix] meilleures
 * formes de chaque préfixe.
 *
 * Sans état ni cache : un appel par frappe.
 */
class CandidateSource(
    val lexicons: LexiconSet,
    private val adjacency: KeyAdjacency,
    private val config: PredictionConfig = PredictionConfig(),
    private val substitution: SpatialSubstitution? = null,
) {
    private val rootMass = lexicons.totalMass
    private val weights = config.correction
    private val elisions: List<Pair<String, Double>> = elisionWeights()

    /**
     * Candidats pour le texte tapé [typed] (clé normalisée non vide). Si [taps] s'aligne sur [typed]
     * ([WordTaps.alignedTo]) et que la source a un [SpatialSubstitution], le poids d'une substitution
     * dépend de la position réelle de la frappe (issue #53) ; sinon il ne dépend que de l'adjacence.
     *
     * @throws IllegalArgumentException si [typed] est vide.
     */
    fun lookup(typed: String, taps: WordTaps? = null): LanguageCandidates {
        require(typed.isNotEmpty()) { "Le texte tapé ne doit pas être vide." }
        val distribution = lexicons.distribution(typed)
        val known = !distribution.isUnknownPrefix
        val isWord = distribution.endProbability > 0.0
        val sources = ArrayList<Source>()
        if (!isWord) {
            for ((prefix, weightAndMass) in weightedEdits(typed, taps)) sources += Source(prefix, weightAndMass.first, weightAndMass.second)
        }
        if (known) sources += Source(typed, 1.0, distribution.totalMass)
        if (!isWord) addElisions(typed, sources)
        var total = 0.0
        for (source in sources) total += source.bound
        // Préfixes par borne décroissante : aucun mot d'un préfixe ne dépasse poids × masse, si bien
        // qu'on s'arrête dès que la borne passe sous le k-ième meilleur score (résultat exact).
        sources.sortByDescending { it.bound }
        val best = HashMap<String, ScoredKey>()
        val k = config.formsPerPrefix
        for (source in sources) {
            if (best.size >= k && source.bound * BOUND_MARGIN < kthScore(best, k)) break
            if (source.exactWord) {
                offer(best, ScoredKey(source.head + source.prefix, source.bound, source.head.length))
            } else {
                for ((key, frequency) in keyFrequencies(source.prefix)) {
                    offer(best, ScoredKey(source.head + key, source.weight * frequency, source.head.length))
                }
            }
        }
        val top = best.values.map { it.copy(score = it.score / rootMass) }.sortedWith(SCORE_ORDER).take(k)
        return LanguageCandidates(isWord, known, total / rootMass, top)
    }

    /**
     * Préfixe candidat : ses mots ont pour score `weight × fréquence` ; [head] (élision) est ajouté
     * devant leurs clés. [exactWord] : seul le mot [prefix] lui-même est candidat, de fréquence [mass].
     */
    private class Source(
        val prefix: String,
        val weight: Double,
        mass: Double,
        val head: String = "",
        val exactWord: Boolean = false,
    ) {
        /** Masse pondérée du préfixe : contribution au dénominateur et borne du score de ses mots. */
        val bound: Double = weight * mass
    }

    private fun addElisions(typed: String, sources: MutableList<Source>) {
        for ((elision, weight) in elisions) {
            if (typed.length <= elision.length || !typed.startsWith(elision)) continue
            val rest = typed.substring(elision.length)
            if (rest[0] !in config.elisionInitials) continue
            val d = lexicons.distribution(rest)
            if (d.isUnknownPrefix) continue
            val head = "$elision'"
            if (d.endProbability > 0.0) {
                // Le mot élidé est complet : seul ce mot est candidat (comme le cas 1 du §5.5).
                val frequency = lexicons.displayForms(rest).sumOf { it.frequency.toDouble() }
                sources += Source(rest, weight, frequency, head, exactWord = true)
            } else {
                sources += Source(rest, weight, d.totalMass, head)
            }
        }
    }

    private fun offer(best: MutableMap<String, ScoredKey>, candidate: ScoredKey) {
        if (candidate.score > (best[candidate.key]?.score ?: 0.0)) best[candidate.key] = candidate
    }

    private fun kthScore(best: Map<String, ScoredKey>, k: Int): Double =
        best.values.map { it.score }.sortedDescending()[k - 1]

    /** Fréquence de chaque clé parmi les meilleures formes de [prefix] (formes d'une clé additionnées). */
    private fun keyFrequencies(prefix: String): Map<String, Double> {
        val byKey = LinkedHashMap<String, Double>()
        for (entry in lexicons.completions(prefix, config.formsPerPrefix)) {
            byKey.merge(entry.key, entry.frequency.toDouble(), Double::plus)
        }
        return byKey
    }

    /**
     * Préfixes connus à distance d'édition 1 de [typed], avec leur poids (le meilleur si plusieurs
     * éditions donnent le même préfixe). Seules les positions dont le début `typed[0, i)` est un
     * préfixe connu sont explorées : une édition en position `i` le laisse inchangé.
     */
    fun edits(typed: String, taps: WordTaps? = null): Map<String, Double> =
        weightedEdits(typed, taps).mapValues { it.value.first }

    /** Comme [edits], avec la masse de chaque préfixe. */
    private fun weightedEdits(typed: String, taps: WordTaps?): Map<String, Pair<Double, Double>> {
        val aligned = if (substitution != null) taps?.alignedTo(typed) else null
        val result = HashMap<String, Pair<Double, Double>>()
        fun offer(prefix: String, weight: Double) {
            // Le préfixe vide (seul caractère tapé retiré) n'apporte rien : tout le lexique.
            if (prefix.isEmpty() || weight <= (result[prefix]?.first ?: 0.0)) return
            val mass = lexicons.prefixMass(prefix)
            if (mass > 0.0) result[prefix] = weight to mass
        }
        for (i in typed.indices) {
            val head = typed.substring(0, i)
            val before = lexicons.distribution(head)
            if (before.isUnknownPrefix) break
            val typedChar = typed[i]
            val tail = typed.substring(i + 1)
            for (c in before.continuations) {
                if (c != typedChar) offer(head + c + tail, substitutionWeight(typedChar, c, aligned?.get(i), taps))
                offer(head + c + typed.substring(i), if (c == APOSTROPHE || c == '-') weights.separator else weights.omission)
            }
            offer(head + tail, weights.extra)
            if (i + 1 < typed.length && typed[i + 1] != typedChar) {
                offer(head + typed[i + 1] + typedChar + typed.substring(i + 2), weights.transposition)
            }
        }
        return result
    }

    private fun substitutionWeight(typedChar: Char, intended: Char, tap: TypedTap?, taps: WordTaps?): Double {
        if (tap != null && substitution != null && taps != null) {
            val spatial = substitution.weight(tap, intended, taps.spatial)
            if (spatial >= 0.0) return spatial
        }
        return if (adjacency.areNeighbors(typedChar, intended)) weights.adjacentSubstitution else weights.otherSubstitution
    }

    /** Poids de chaque élision connue du lexique : `separator × f(e') / masse des initiales admises`. */
    private fun elisionWeights(): List<Pair<String, Double>> {
        val list = config.elisions[lexicons.language] ?: return emptyList()
        val initials = lexicons.distribution("")
        val initialMass = config.elisionInitials.sumOf { initials.probability(it) } * initials.totalMass
        if (initialMass <= 0.0) return emptyList()
        return list.mapNotNull { elision ->
            val frequency = lexicons.displayForms("$elision'").sumOf { it.frequency.toDouble() }
            if (frequency > 0.0) elision to minOf(1.0, weights.separator * frequency / initialMass) else null
        }
    }

    companion object {
        /** Marge sur la borne d'un préfixe : les masses sont quantifiées à 0,07 % près. */
        private const val BOUND_MARGIN = 1.01
        private const val APOSTROPHE = '\''

        /** Score décroissant, puis clé croissante. */
        val SCORE_ORDER: Comparator<ScoredKey> = compareByDescending<ScoredKey> { it.score }.thenBy { it.key }
    }
}
