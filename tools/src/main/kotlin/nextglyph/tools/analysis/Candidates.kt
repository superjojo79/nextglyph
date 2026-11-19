package nextglyph.tools.analysis

import nextglyph.engine.lexicon.Lexicon

/**
 * Coûts (multiplicatifs) de la correction à distance d'édition 1 sur le préfixe (§10.4).
 *
 * Le score d'un candidat `w` atteint par une édition `e` du texte tapé est
 * `poids(e) × fréquence(w)`. Une complétion exacte a le poids 1. Les valeurs ne sont pas
 * optimisées : elles traduisent seulement « une faute est rare, une faute entre voisines l'est
 * moins », à l'ordre de grandeur du taux de fautes simulé (quelques % par lettre).
 *
 * @property adjacentSubstitution lettre tapée remplacée par une voisine (`bonjoir` → `bonjour` : `i`/`u`).
 * @property otherSubstitution lettre tapée remplacée par une lettre non voisine.
 * @property omission caractère manquant (on l'insère dans le texte tapé).
 * @property extra caractère en trop (on le retire du texte tapé).
 * @property transposition deux caractères consécutifs inversés.
 * @property separator apostrophe ou trait d'union manquant (`jai` → `j'ai`, `peutetre` → `peut-etre`) :
 *   omission volontaire et fréquente, presque gratuite (§5.5).
 */
data class CorrectionWeights(
    val adjacentSubstitution: Double = 0.01,
    val otherSubstitution: Double = 0.0005,
    val omission: Double = 0.002,
    val extra: Double = 0.002,
    val transposition: Double = 0.002,
    val separator: Double = 0.5,
)

/** Un candidat (clé de lexique) et son score non normalisé. */
data class ScoredKey(val key: String, val score: Double)

/**
 * Candidats d'une langue pour un texte tapé.
 *
 * @property isWord le texte tapé est une clé du lexique (cas 1 du §5.5).
 * @property knownPrefix le texte tapé est un préfixe connu (ses complétions sont candidates, avec le poids 1).
 * @property total masse de tous les candidats, divisée par la masse totale du lexique (comparable
 *   d'une langue à l'autre).
 * @property top les meilleurs candidats par score décroissant (même échelle que [total]).
 */
class LanguageCandidates(val isWord: Boolean, val knownPrefix: Boolean, val total: Double, val top: List<ScoredKey>)

/**
 * Candidats de la validation par espace (§5.5) pour une langue : complétions du texte tapé
 * s'il est un préfixe connu, et complétions des préfixes à distance d'édition 1 (§5.2, §10.4),
 * pondérées par [CorrectionWeights] et par l'adjacence de [layout]. Les préfixes édités sont
 * explorés si le texte tapé est un préfixe inconnu, ou, avec [correctKnownPrefixes], si c'est un
 * préfixe connu qui n'est pas un mot (une faute peut former un autre préfixe valide : `ke` pour `le`).
 *
 * Simplifications assumées (voir `docs/analysis/M0-corpus.md`) :
 * - distance 1 seulement (la spec prévoit 2 sur le mot complet à la validation) ;
 * - la masse totale additionne les masses des préfixes édités : un mot atteint par deux éditions
 *   compte deux fois dans le dénominateur, une fois (le meilleur score) comme candidat ;
 * - le score d'une clé est la somme des fréquences de ses formes parmi les [topLimit] meilleures
 *   formes de chaque préfixe.
 *
 * Les résultats sont mis en cache par texte tapé (le lexique est immuable).
 */
class CandidateSource(
    private val lexicon: Lexicon,
    private val layout: KeyboardLayout,
    private val weights: CorrectionWeights = CorrectionWeights(),
    private val topLimit: Int = DEFAULT_TOP_LIMIT,
    private val correctKnownPrefixes: Boolean = false,
) {
    private val rootMass = lexicon.distribution("").totalMass
    private val cache = HashMap<String, LanguageCandidates>()

    /** Candidats pour le texte tapé [typed] (clé normalisée non vide). */
    fun lookup(typed: String): LanguageCandidates = cache.getOrPut(typed) { compute(typed) }

    /** Vide le cache (libère la mémoire entre deux analyses). */
    fun clearCache() = cache.clear()

    private fun compute(typed: String): LanguageCandidates {
        require(typed.isNotEmpty()) { "Le texte tapé ne doit pas être vide." }
        val distribution = lexicon.distribution(typed)
        val known = !distribution.isUnknownPrefix
        val isWord = distribution.endProbability > 0.0
        val prefixes = HashMap<String, Double>()
        if (known) prefixes[typed] = 1.0
        if (!known || (correctKnownPrefixes && !isWord)) prefixes.putAll(edits(typed))
        var total = 0.0
        val best = HashMap<String, Double>()
        for ((prefix, weight) in prefixes) {
            total += weight * lexicon.distribution(prefix).totalMass
            for ((key, score) in keyScores(prefix, weight)) {
                if (score > (best[key] ?: 0.0)) best[key] = score
            }
        }
        val top = best.map { ScoredKey(it.key, it.value / rootMass) }.sortedWith(SCORE_ORDER).take(topLimit)
        return LanguageCandidates(isWord, known, total / rootMass, top)
    }

    private fun keyScores(prefix: String, weight: Double): List<ScoredKey> {
        val byKey = LinkedHashMap<String, Double>()
        for (entry in lexicon.completions(prefix, topLimit)) {
            byKey.merge(entry.key, entry.frequency.toDouble(), Double::plus)
        }
        return byKey.map { ScoredKey(it.key, it.value * weight) }.sortedWith(SCORE_ORDER)
    }

    /**
     * Préfixes connus à distance d'édition 1 de [typed], avec leur poids (le meilleur si plusieurs
     * éditions donnent le même préfixe). Seules les positions dont le préfixe amont est connu sont
     * explorées : une édition en position `i` laisse `typed[0, i)` inchangé.
     */
    fun edits(typed: String): Map<String, Double> {
        val result = HashMap<String, Double>()
        fun offer(prefix: String, weight: Double) {
            // Le préfixe vide (seul caractère tapé retiré) n'apporte rien : tout le lexique.
            if (prefix.isNotEmpty() && weight > (result[prefix] ?: 0.0) && !lexicon.distribution(prefix).isUnknownPrefix) {
                result[prefix] = weight
            }
        }
        for (i in typed.indices) {
            val head = typed.substring(0, i)
            val head0 = lexicon.distribution(head)
            if (head0.isUnknownPrefix) break
            val typedChar = typed[i]
            val tail = typed.substring(i + 1)
            for (c in head0.continuations) {
                if (c != typedChar) offer(head + c + tail, substitutionWeight(typedChar, c))
                offer(head + c + typed.substring(i), if (c == '\'' || c == '-') weights.separator else weights.omission)
            }
            offer(head + tail, weights.extra)
            if (i + 1 < typed.length && typed[i + 1] != typedChar) {
                offer(head + typed[i + 1] + typedChar + typed.substring(i + 2), weights.transposition)
            }
        }
        return result
    }

    private fun substitutionWeight(typedChar: Char, intended: Char): Double =
        if (layout.areNeighbors(typedChar, intended)) weights.adjacentSubstitution else weights.otherSubstitution

    companion object {
        /** Nombre de formes lues par préfixe (et de candidats conservés). */
        const val DEFAULT_TOP_LIMIT: Int = 6

        internal val SCORE_ORDER: Comparator<ScoredKey> =
            compareByDescending<ScoredKey> { it.score }.thenBy { it.key }
    }
}

/**
 * Décision de la validation par espace pour un texte tapé, toutes langues mélangées.
 *
 * @property isWord le texte tapé est un mot d'au moins un lexique actif (cas 1 du §5.5).
 * @property top le meilleur candidat, ou `null` s'il n'y en a aucun.
 * @property p1 `P(top)` ; [p2] : `P(2e candidat)` (0 s'il n'y en a pas).
 */
data class Decision(val isWord: Boolean, val top: String?, val p1: Double, val p2: Double) {

    /** Vrai si [top] domine au sens du §5.5 pour [thresholds] (sans la condition de longueur). */
    fun dominates(thresholds: Thresholds): Boolean =
        top != null && p1 >= thresholds.minProb && p1 >= thresholds.minRatio * p2
}

/** Mélange des candidats de plusieurs langues (§10.6). */
object CandidateMixer {

    /**
     * Combine les candidats de chaque langue, pondérés par `π_L` :
     * `P(w) = Σ_L π_L · score_L(w) / Σ_L π_L · total_L`. Les scores étant normalisés par la masse
     * totale de chaque lexique, c'est un mélange « bayésien » : une langue où le texte tapé est un
     * préfixe courant pèse plus qu'une langue où il faut une correction (voir l'analyse M0).
     * Avec une seule langue (`π = 1`), `P(w) = score(w) / total`.
     */
    fun combine(parts: List<Pair<Double, LanguageCandidates>>): Decision {
        val isWord = parts.any { it.first > 0.0 && it.second.isWord }
        var total = 0.0
        val scores = LinkedHashMap<String, Double>()
        for ((pi, candidates) in parts) {
            if (pi <= 0.0) continue
            total += pi * candidates.total
            for (scored in candidates.top) scores.merge(scored.key, pi * scored.score, Double::plus)
        }
        if (total <= 0.0 || scores.isEmpty()) return Decision(isWord, null, 0.0, 0.0)
        val ranked = scores.map { ScoredKey(it.key, it.value) }.sortedWith(CandidateSource.SCORE_ORDER)
        return Decision(isWord, ranked[0].key, ranked[0].score / total, ranked.getOrNull(1)?.let { it.score / total } ?: 0.0)
    }
}
