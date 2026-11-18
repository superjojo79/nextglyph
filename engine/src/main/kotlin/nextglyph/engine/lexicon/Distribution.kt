package nextglyph.engine.lexicon

/**
 * Distribution de la lettre suivante pour un préfixe `p` (spec §3, §5.2).
 *
 * Les probabilités des continuations et [endProbability] somment à 1 (aux arrondis
 * flottants près), sauf pour un préfixe inconnu où toutes valent 0.
 *
 * @property isUnknownPrefix vrai si aucun mot du lexique ne commence par `p`.
 * @property totalMass somme des fréquences des mots commençant par `p` (0 si inconnu).
 *   Les masses des sous-arbres sont quantifiées à 0,07 % près (voir `docs/DECISIONS.md`) :
 *   c'est une grandeur relative, utile pour mélanger deux lexiques (§10.6), pas un compte exact.
 * @property endProbability `P(fin | p)` : probabilité que `p` soit un mot complet.
 */
class Distribution internal constructor(
    val isUnknownPrefix: Boolean,
    val totalMass: Double,
    val endProbability: Double,
    private val probabilities: DoubleArray,
) {

    /** `P(c | p)` ; 0 si [c] n'est pas une continuation de `p` ou n'appartient pas à l'alphabet. */
    fun probability(c: Char): Double {
        val index = LexiconAlphabet.indexOf(c)
        return if (index < 0) 0.0 else probabilities[index]
    }

    /** Les continuations de `p` (probabilité non nulle), dans l'ordre de [LexiconAlphabet.CHARS]. */
    val continuations: List<Char>
        get() = LexiconAlphabet.CHARS.filterIndexed { index, _ -> probabilities[index] > 0.0 }.toList()

    internal companion object {
        val UNKNOWN = Distribution(true, 0.0, 0.0, DoubleArray(LexiconAlphabet.SIZE))
    }
}
