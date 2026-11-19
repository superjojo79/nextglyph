package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout

/** Origine des poids des touches. */
enum class WeightSource {
    /** État neutre : toutes les touches au repos. */
    NEUTRAL,

    /** Distribution du préfixe courant. */
    PREFIX,

    /** Début de mot : distribution des initiales, adaptation atténuée (§5.2). */
    WORD_START,

    /** Préfixe inconnu corrigé à distance d'édition 1 (§5.2, §10.4). */
    CORRECTION,
}

/**
 * Poids des touches d'une disposition pour la frappe suivante (spec §3, §5.2) : `r(c)` pour chaque
 * lettre, `P(fin | p)` pour l'espace, et l'intensité à laquelle appliquer l'adaptation.
 *
 * Tampon **mutable et réutilisable** : [KeyWeightCalculator.compute] le remplit en place, sans
 * allocation. Il peut aussi être rempli à la main ([setLetterWeight], [setEndProbability],
 * [setIntensity]), par exemple pour des tests ou une autre source de prédiction.
 *
 * Une touche « au repos » (touche de fonction, lettre hors de l'alphabet du lexique, ou toute
 * touche en état neutre) n'est pas adaptée : largeur, opacité et libellé de repos, et a priori
 * neutre pour le décodeur.
 */
class KeyWeights(val layout: KeyboardLayout) {

    internal val keys = LayoutKeys(layout)

    /** `r(c)` par touche à plat ; 1 pour une touche au repos. */
    internal val relative = FloatArray(keys.size)

    /** Vrai pour une touche au repos. */
    internal val atRest = BooleanArray(keys.size)

    /** `P(fin | p)`, dans [0, 1]. Pilote la largeur de l'espace. */
    var endProbability: Float = 0f
        private set

    /**
     * Intensité de l'adaptation, dans [0, 1] : 1 pour un préfixe connu,
     * [AdaptationConfig.wordStartIntensity] en début de mot, [AdaptationConfig.correctionIntensity]
     * après correction, 0 en état neutre.
     */
    var intensity: Float = 0f
        private set

    /** Origine des poids. */
    var source: WeightSource = WeightSource.NEUTRAL
        private set

    /** Vrai en état neutre : la disposition résolue est exactement celle du repos. */
    val isNeutral: Boolean get() = source == WeightSource.NEUTRAL

    init {
        setNeutral()
    }

    /** `r(c)` de la touche [key] de la rangée [row], dans [0, 1] ; 1 pour une touche au repos. */
    fun relativeWeight(row: Int, key: Int): Float = relative[keys.index(row, key)]

    /** Vrai si la touche [key] de la rangée [row] n'est pas adaptée. */
    fun isAtRest(row: Int, key: Int): Boolean = atRest[keys.index(row, key)]

    /** Remet toutes les touches au repos (état neutre). */
    fun setNeutral() {
        relative.fill(1f)
        atRest.fill(true)
        endProbability = 0f
        intensity = 0f
        source = WeightSource.NEUTRAL
    }

    /**
     * Donne le poids [r] à la touche de la lettre [letter] (sans effet si la disposition n'a pas
     * cette lettre). La touche cesse d'être au repos ; si les poids étaient neutres, ils passent en
     * [WeightSource.PREFIX] avec une intensité de 1.
     *
     * @throws IllegalArgumentException si [r] n'est pas dans [0, 1].
     */
    fun setLetterWeight(letter: Char, r: Float) {
        require(r >= 0f && r <= 1f) { "Le poids relatif doit être dans [0, 1] : $r." }
        if (source == WeightSource.NEUTRAL) begin(WeightSource.PREFIX, 1f)
        val label = letter.toString()
        for (i in 0 until keys.size) {
            if (keys.keys[i].kind == KeyKind.LETTER && keys.keys[i].label == label) {
                relative[i] = r
                atRest[i] = false
            }
        }
    }

    /**
     * Fixe `P(fin | p)`.
     * @throws IllegalArgumentException si [p] n'est pas dans [0, 1].
     */
    fun setEndProbability(p: Float) {
        require(p >= 0f && p <= 1f) { "P(fin) doit être dans [0, 1] : $p." }
        endProbability = p
    }

    /**
     * Fixe l'intensité de l'adaptation (sans effet sur des poids neutres).
     * @throws IllegalArgumentException si [k] n'est pas dans [0, 1].
     */
    fun setIntensity(k: Float) {
        require(k >= 0f && k <= 1f) { "L'intensité doit être dans [0, 1] : $k." }
        if (source != WeightSource.NEUTRAL) intensity = k
    }

    /** Copie les poids de [other], de la même disposition. */
    fun copyFrom(other: KeyWeights) {
        require(other.layout === layout) { "Les poids viennent d'une autre disposition." }
        other.relative.copyInto(relative)
        other.atRest.copyInto(atRest)
        endProbability = other.endProbability
        intensity = other.intensity
        source = other.source
    }

    /** Début d'un remplissage : toutes les touches au repos, origine et intensité données. */
    internal fun begin(source: WeightSource, intensity: Float) {
        relative.fill(1f)
        atRest.fill(true)
        endProbability = 0f
        this.intensity = intensity
        this.source = source
    }

    internal fun setEnd(p: Double) {
        endProbability = p.coerceIn(0.0, 1.0).toFloat()
    }

    override fun toString(): String = buildString {
        append("KeyWeights(").append(source).append(", k=").append(intensity).append(", fin=").append(endProbability)
        for (i in 0 until keys.size) {
            if (!atRest[i]) append(", ").append(keys.keys[i].label).append('=').append(relative[i])
        }
        append(')')
    }
}
