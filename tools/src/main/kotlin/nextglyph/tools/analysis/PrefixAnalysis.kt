package nextglyph.tools.analysis

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconAlphabet
import kotlin.math.ln

/**
 * Statistiques de la lettre suivante pour une position dans le mot (partie 1 du §15.1), pondérées
 * par les occurrences du corpus. La position `i` est celle de la lettre tapée : on lit la
 * distribution du préfixe formé des `i − 1` lettres précédentes du mot réellement tapé.
 *
 * @property weight occurrences (mot × position) observées à cette position.
 * @property unknownWeight dont le préfixe est inconnu du lexique (mot hors lexique) ; exclues des autres moyennes.
 * @property absentWeight préfixe connu, mais la lettre tapée n'en est pas une continuation (probabilité 0).
 */
class PositionStats(val position: Int) {
    var weight: Long = 0; private set
    var unknownWeight: Long = 0; private set
    var absentWeight: Long = 0; private set
    private var knownWeight = 0L
    private var continuations = 0.0
    private var entropy = 0.0
    private var probability = 0.0
    private var relative = 0.0
    private var rank = 0.0
    private var top3 = 0L
    private var top5 = 0L
    private var faded = 0L

    /** Moyenne du nombre de continuations (lettres de probabilité non nulle, `'` et `-` compris). */
    val meanContinuations: Double get() = continuations / knownWeight
    /** Entropie moyenne de `P(· | p)` en bits, la fin de mot comptée comme une issue. */
    val meanEntropy: Double get() = entropy / knownWeight
    /** Probabilité moyenne de la lettre tapée. */
    val meanProbability: Double get() = probability / knownWeight
    /** Poids relatif moyen `r(c)` de la lettre tapée (§3). */
    val meanRelative: Double get() = relative / knownWeight
    /** Rang moyen de la lettre tapée parmi les continuations, quand elle en est une (1 = la plus probable). */
    val meanRank: Double get() = rank / (knownWeight - absentWeight)
    /** Part des cas (préfixe connu) où la lettre tapée est dans les 3 plus probables. */
    val top3Share: Double get() = top3.toDouble() / knownWeight
    /** Part des cas (préfixe connu) où la lettre tapée est dans les 5 plus probables. */
    val top5Share: Double get() = top5.toDouble() / knownWeight
    /** Part des cas (préfixe connu) où la lettre tapée est fortement atténuée : `r(c) <` [FADED_RELATIVE]. */
    val fadedShare: Double get() = faded.toDouble() / knownWeight
    /** Part des cas où le préfixe est inconnu. */
    val unknownShare: Double get() = unknownWeight.toDouble() / weight

    internal fun addUnknown(count: Long) {
        weight += count
        unknownWeight += count
    }

    internal fun add(count: Long, continuationCount: Int, entropyBits: Double, p: Double, r: Double, letterRank: Int) {
        weight += count
        knownWeight += count
        continuations += continuationCount.toDouble() * count
        entropy += entropyBits * count
        probability += p * count
        relative += r * count
        if (letterRank == 0) {
            absentWeight += count
        } else {
            rank += letterRank.toDouble() * count
        }
        if (letterRank in 1..3) top3 += count
        if (letterRank in 1..5) top5 += count
        if (r < FADED_RELATIVE) faded += count
    }

    companion object {
        /** Seuil de « forte atténuation » : `r < 0,1` donne une opacité ≤ 0,69 avec les formules du §5.2. */
        const val FADED_RELATIVE: Double = 0.1
    }
}

/** Résultat de la partie 1 pour une langue. */
class PrefixReport(val positions: List<PositionStats>, val endWeight: Long, val meanEndProbability: Double)

/** Partie 1 du §15.1 : ce que le clavier peut mettre en avant, position par position. */
object PrefixAnalysis {

    /** Dernière position distinguée ; les positions suivantes sont regroupées (« 8+ »). */
    const val MAX_POSITION: Int = 8

    private val LN2 = ln(2.0)

    /**
     * Parcourt chaque mot de [words] (clés, avec leurs occurrences). Le rapport contient aussi la
     * moyenne de `P(fin | mot)` sur les mots du lexique (rendu de l'espace en fin de mot).
     */
    fun analyze(lexicon: Lexicon, words: Map<String, Long>): PrefixReport {
        val stats = List(MAX_POSITION) { PositionStats(it + 1) }
        var endWeight = 0L
        var endSum = 0.0
        for ((word, count) in words) {
            for (i in word.indices) {
                val bucket = stats[minOf(i, MAX_POSITION - 1)]
                val distribution = lexicon.distribution(word.substring(0, i))
                if (distribution.isUnknownPrefix) {
                    bucket.addUnknown(count)
                    continue
                }
                val probabilities = LexiconAlphabet.CHARS.map(distribution::probability)
                val p = distribution.probability(word[i])
                val max = probabilities.max()
                val rank = if (p > 0.0) 1 + probabilities.count { it > p } else 0
                val entropy = entropyBits(probabilities + distribution.endProbability)
                bucket.add(count, probabilities.count { it > 0.0 }, entropy, p, if (max > 0.0) p / max else 0.0, rank)
            }
            val end = lexicon.distribution(word)
            if (end.endProbability > 0.0) {
                endWeight += count
                endSum += end.endProbability * count
            }
        }
        return PrefixReport(stats, endWeight, if (endWeight == 0L) 0.0 else endSum / endWeight)
    }

    /** Entropie de Shannon (bits) d'une distribution ; les termes nuls sont ignorés. */
    fun entropyBits(probabilities: List<Double>): Double =
        probabilities.filter { it > 0.0 }.sumOf { -it * ln(it) / LN2 }
}
