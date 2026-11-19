package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.lexicon.Distribution
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconAlphabet
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Source de distributions de la lettre suivante, typiquement `lexicon::distribution` ; une autre
 * implémentation peut mélanger deux lexiques (§10.6) ou ajouter un contexte (§10.3).
 */
fun interface DistributionSource {
    /** Distribution de la lettre suivant le préfixe normalisé [prefix]. */
    fun distribution(prefix: String): Distribution
}

/**
 * Calcule les poids des touches ([KeyWeights]) à partir de la distribution du préfixe courant
 * (spec §5.2) :
 *
 * - **préfixe connu** : `r(c) = P(c | p) / max_x P(x | p)`, le maximum étant pris sur les lettres
 *   de la disposition ; `P(fin | p)` pour l'espace ; intensité 1 ;
 * - **début de mot** (`p` vide) : distribution des initiales, intensité
 *   [AdaptationConfig.wordStartIntensity] (atténuée de moitié par défaut) ;
 * - **préfixe inconnu** : correction à distance d'édition 1 sur le préfixe (spec §10.4) :
 *   substitution par une touche voisine (pondérée par une gaussienne de la distance entre centres
 *   au repos), lettre en trop, lettre manquante. Les positions sont explorées de l'endroit où le
 *   préfixe devient inconnu vers le début, dans la limite de
 *   [AdaptationConfig.correctionMaxCandidates] candidats. La distribution retenue est le mélange
 *   des distributions des candidats connus, chacune pondérée par son poids d'édition et sa masse.
 *   Intensité [AdaptationConfig.correctionIntensity]. Sans candidat connu, état neutre.
 *
 * Les lettres de la disposition absentes de l'alphabet du lexique restent au repos.
 *
 * Coût : pour un préfixe connu, aucune allocation en dehors de la source. La correction (cas rare)
 * interroge au plus `|p| + correctionMaxCandidates` préfixes, chacun alloué en `String` (l'API du
 * lexique prend des `String`).
 *
 * Non thread-safe : une instance par fil d'exécution (elle porte des tampons de travail).
 */
class KeyWeightCalculator(
    val layout: KeyboardLayout,
    val config: AdaptationConfig = AdaptationConfig(),
) {

    private val keys = LayoutKeys(layout)
    private val probabilities = DoubleArray(LexiconAlphabet.SIZE)
    private val builder = StringBuilder()
    private var prefixDistributions = arrayOfNulls<Distribution>(INITIAL_PREFIX_CAPACITY)
    private var remainingCandidates = 0
    private var totalWeight = 0.0
    private var endWeight = 0.0
    private val lexiconSource = LexiconSource()

    /** Poids de substitution entre deux caractères de l'alphabet (index `a · SIZE + b`). */
    private val adjacency = FloatArray(LexiconAlphabet.SIZE * LexiconAlphabet.SIZE).also { matrix ->
        val radius = config.correctionNeighborRadius.toDouble()
        val sigma = config.correctionAdjacencySigma.toDouble()
        for (i in 0 until keys.size) {
            val a = keys.alphabetIndex[i]
            for (j in 0 until keys.size) {
                val b = keys.alphabetIndex[j]
                if (a == NOT_A_LETTER || b == NOT_A_LETTER || i == j) continue
                val dx = keys.restCenter[i] - keys.restCenter[j]
                val dy = (keys.rowOf[i] - keys.rowOf[j]).toDouble()
                val d2 = dx * dx + dy * dy
                if (sqrt(d2) <= radius) matrix[a * LexiconAlphabet.SIZE + b] = exp(-d2 / (2 * sigma * sigma)).toFloat()
            }
        }
    }

    /** Raccourci : interroge [lexicon] pour [prefix] puis calcule les poids. */
    fun compute(prefix: String, lexicon: Lexicon, out: KeyWeights) {
        lexiconSource.lexicon = lexicon
        compute(prefix, lexicon.distribution(prefix), lexiconSource, out)
    }

    /**
     * Remplit [out] pour le préfixe normalisé [prefix], dont la distribution [distribution] a déjà
     * été calculée (par exemple pour la barre de suggestions). [source] n'est interrogée que si le
     * préfixe est inconnu, pour la correction ; elle ne l'est jamais sur [prefix] lui-même.
     *
     * @throws IllegalArgumentException si [out] ne correspond pas à [layout].
     */
    fun compute(prefix: String, distribution: Distribution, source: DistributionSource, out: KeyWeights) {
        require(out.layout === layout) { "Les poids doivent porter sur la disposition du calculateur." }
        if (distribution.isUnknownPrefix) {
            correct(prefix, source, out)
            return
        }
        for (c in 0 until LexiconAlphabet.SIZE) probabilities[c] = distribution.probability(LexiconAlphabet.CHARS[c])
        if (prefix.isEmpty()) {
            fill(out, WeightSource.WORD_START, config.wordStartIntensity, distribution.endProbability)
        } else {
            fill(out, WeightSource.PREFIX, 1f, distribution.endProbability)
        }
    }

    private fun fill(out: KeyWeights, source: WeightSource, intensity: Float, end: Double) {
        var top = 0.0
        for (i in 0 until keys.size) {
            val a = keys.alphabetIndex[i]
            if (a != NOT_A_LETTER) top = max(top, probabilities[a])
        }
        out.begin(source, intensity)
        for (i in 0 until keys.size) {
            val a = keys.alphabetIndex[i]
            if (a == NOT_A_LETTER) continue
            out.atRest[i] = false
            out.relative[i] = if (top > 0.0) (probabilities[a] / top).toFloat().coerceIn(0f, 1f) else 0f
        }
        out.setEnd(end)
    }

    private fun correct(prefix: String, source: DistributionSource, out: KeyWeights) {
        // Distributions des préfixes propres connus : p[0, i) pour i = 0..known.
        var known = -1
        while (known + 1 < prefix.length) {
            val d = source.distribution(prefix.substring(0, known + 1))
            if (d.isUnknownPrefix) break
            known++
            if (known == prefixDistributions.size) prefixDistributions = prefixDistributions.copyOf(known * 2)
            prefixDistributions[known] = d
        }
        probabilities.fill(0.0)
        totalWeight = 0.0
        endWeight = 0.0
        remainingCandidates = config.correctionMaxCandidates
        // Les erreurs les plus probables sont près de l'endroit où le préfixe devient inconnu.
        for (i in known downTo 0) editsAt(prefix, i, prefixDistributions[i]!!, source)
        if (totalWeight > 0.0) {
            fill(out, WeightSource.CORRECTION, config.correctionIntensity, endWeight / totalWeight)
        } else {
            out.setNeutral()
        }
    }

    /** Éditions à la position [i], sachant que `p[0, i)` est connu et a pour distribution [before]. */
    private fun editsAt(prefix: String, i: Int, before: Distribution, source: DistributionSource) {
        val original = LexiconAlphabet.indexOf(prefix[i])
        // Substitution par une touche voisine (rien pour un caractère hors alphabet).
        if (original != NOT_A_LETTER) {
            for (x in 0 until LexiconAlphabet.SIZE) {
                val w = adjacency[original * LexiconAlphabet.SIZE + x]
                if (w > 0f && before.probability(LexiconAlphabet.CHARS[x]) > 0.0) {
                    candidate(prefix, i, LexiconAlphabet.CHARS[x], i + 1, w.toDouble(), source)
                }
            }
        }
        // Lettre en trop (une seule fois pour une lettre doublée).
        if (config.correctionDeletionWeight > 0f && (i == 0 || prefix[i] != prefix[i - 1])) {
            candidate(prefix, i, NO_CHAR, i + 1, config.correctionDeletionWeight.toDouble(), source)
        }
        // Lettre manquante (une seule fois pour une lettre qui serait doublée).
        if (config.correctionInsertionWeight > 0f) {
            for (x in 0 until LexiconAlphabet.SIZE) {
                val c = LexiconAlphabet.CHARS[x]
                if ((i == 0 || prefix[i - 1] != c) && before.probability(c) > 0.0) {
                    candidate(prefix, i, c, i, config.correctionInsertionWeight.toDouble(), source)
                }
            }
        }
    }

    /**
     * Interroge `p[0, i) + inserted + p[resume, |p|)` et l'ajoute au mélange. Un candidat inconnu a
     * une masse nulle : il n'ajoute rien.
     */
    private fun candidate(prefix: String, i: Int, inserted: Char, resume: Int, weight: Double, source: DistributionSource) {
        if (remainingCandidates == 0) return
        remainingCandidates--
        val d = if (inserted == NO_CHAR && resume == prefix.length) {
            // Suppression de la dernière lettre : p[0, i) est un préfixe propre, déjà interrogé.
            prefixDistributions[i]!!
        } else {
            builder.setLength(0)
            builder.append(prefix, 0, i)
            if (inserted != NO_CHAR) builder.append(inserted)
            builder.append(prefix, resume, prefix.length)
            source.distribution(builder.toString())
        }
        val w = weight * d.totalMass
        for (c in 0 until LexiconAlphabet.SIZE) probabilities[c] += w * d.probability(LexiconAlphabet.CHARS[c])
        endWeight += w * d.endProbability
        totalWeight += w
    }

    /** Source réutilisée par [compute] avec un [Lexicon], pour ne rien allouer à chaque frappe. */
    private class LexiconSource : DistributionSource {
        @JvmField
        var lexicon: Lexicon? = null

        override fun distribution(prefix: String): Distribution = lexicon!!.distribution(prefix)
    }

    private companion object {
        const val INITIAL_PREFIX_CAPACITY = 32
        const val NOT_A_LETTER = -1
        const val NO_CHAR = '\u0000'
    }
}
