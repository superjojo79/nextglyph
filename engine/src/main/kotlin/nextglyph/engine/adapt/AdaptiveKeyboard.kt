package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.lexicon.Lexicon

/**
 * Cœur adaptatif d'un clavier (spec §5.2, §5.3) : relie le préfixe du mot en cours au calcul des
 * poids, au solveur, à l'état gelé/animé de la disposition affichée et au décodeur de tap. La vue
 * n'a plus qu'à transmettre les événements tactiles et le temps, et à dessiner [frame].
 *
 * Cycle d'une frappe : l'application appelle [onPrefix] avec le préfixe du mot en cours (voir
 * `WordPrefix`) ; les poids sont recalculés aussitôt (le décodeur s'en sert dès le tap suivant), la
 * disposition cible est proposée à l'état, qui l'applique après le dernier `pointerUp` (ou après une
 * pause en saisie rapide) puis l'anime. La vue appelle [advance] à chaque image.
 *
 * Sans lexique, avec un préfixe inconnu de l'application (`null`) ou en mode [restOnly], le clavier
 * est neutre : disposition au repos et décodage purement spatial (`λ = 0`).
 *
 * Sans allocation après la construction (hors lexique et correction d'un préfixe inconnu) ; non
 * thread-safe : à utiliser depuis le fil de l'interface.
 */
class AdaptiveKeyboard(
    val layout: KeyboardLayout,
    val config: AdaptationConfig = AdaptationConfig(),
    spatial: TapSpatialModel = TapSpatialModel.NONE,
) {

    private val calculator = KeyWeightCalculator(layout, config)
    private val solver = LayoutSolver(layout, config)
    private val state = AdaptiveLayoutState(layout, config)
    private val decoder = TapDecoder(layout, config.decoder, spatial)
    private val weights = KeyWeights(layout)
    private val target = AdaptedLayout(layout)
    private val decoding = TapDecoding(layout)
    private var prefix: String? = null
    private var computed = false

    /** Lexique de la langue active, ou `null` tant qu'il n'est pas chargé (ou si une autre source est installée). */
    var lexicon: Lexicon? = null
        private set

    /**
     * Source des distributions (lexique, ou mélange bilingue `Predictor.distribution`, §10.6), ou
     * `null` : clavier neutre.
     */
    var source: DistributionSource? = null
        private set

    /**
     * Vrai pour la disposition au repos uniquement (TalkBack, §13 : l'adaptation visuelle est
     * désactivée, et le décodage ne s'écarte pas de ce que la personne entend).
     */
    var restOnly: Boolean = false
        private set

    /** Image à dessiner, et sur laquelle le décodage s'appuie. */
    val frame: AdaptedLayout get() = state.frame

    /** Nombre de pointeurs posés (la disposition est gelée s'il est positif). */
    val pointersDown: Int get() = state.pointersDown

    /** Vrai pendant une animation. */
    val isAnimating: Boolean get() = state.isAnimating

    /** Vrai si une disposition attend d'être appliquée. */
    val hasPendingUpdate: Boolean get() = state.hasPendingUpdate

    /** Date à partir de laquelle la disposition en attente peut être appliquée ([Long.MIN_VALUE] sans attente). */
    val nextUpdateTimeMs: Long get() = state.nextUpdateTimeMs

    /** Largeur du clavier en dp, pour la largeur minimale des touches (§13) ; 0 : inconnue. */
    var keyboardWidthDp: Float
        get() = solver.keyboardWidthDp
        set(value) {
            if (value == solver.keyboardWidthDp) return
            solver.keyboardWidthDp = value
            computed = false
        }

    /** Installe (ou retire) le lexique de la langue active et recalcule la disposition à [nowMs]. */
    fun setLexicon(lexicon: Lexicon?, nowMs: Long) {
        setSource(lexicon?.let { DistributionSource(it::distribution) }, nowMs)
        this.lexicon = lexicon
    }

    /**
     * Installe (ou retire) la source des distributions et recalcule la disposition à [nowMs] ;
     * à rappeler quand la source change de contenu sans que le préfixe change (nouvel état de
     * langue, par exemple).
     */
    fun setSource(source: DistributionSource?, nowMs: Long) {
        this.source = source
        lexicon = null
        computed = false
        refresh(nowMs)
    }

    /** Active ou désactive le mode « disposition au repos » ; la disposition revient au repos aussitôt. */
    fun setRestOnly(restOnly: Boolean, nowMs: Long) {
        if (this.restOnly == restOnly) return
        this.restOnly = restOnly
        computed = false
        if (restOnly) state.reset()
        refresh(nowMs)
    }

    /**
     * Le mot en cours a un nouveau préfixe (`""` au début d'un mot, `null` si ce n'est pas un mot).
     * Sans effet si ni le préfixe ni le contexte n'ont changé depuis le dernier appel, si bien que
     * l'appeler après chaque frappe *et* à chaque `onUpdateSelection` ne compte qu'une frappe.
     */
    fun onPrefix(prefix: String?, nowMs: Long) {
        if (computed && prefix == this.prefix) return
        this.prefix = prefix
        computed = false
        refresh(nowMs)
    }

    /** Nouveau champ de saisie : disposition au repos immédiate, préfixe oublié. */
    fun reset() {
        prefix = null
        weights.setNeutral()
        state.reset()
        computed = true
    }

    fun pointerDown(nowMs: Long) = state.pointerDown(nowMs)

    fun pointerUp(nowMs: Long) = state.pointerUp(nowMs)

    fun cancelPointers(nowMs: Long) = state.cancelPointers(nowMs)

    /**
     * Fait avancer l'état à [nowMs] ; vrai s'il faut le rappeler plus tard (animation en cours ou
     * disposition en attente). [animatorDurationScale] : échelle de durée des animations système (§13).
     */
    fun advance(nowMs: Long, animatorDurationScale: Float): Boolean = state.advance(nowMs, animatorDurationScale)

    /**
     * Décode le tap en `(x, y)` : lettre la plus probable d'après le modèle spatial (positions
     * affichée [displayed] et au repos [rest]) et le modèle de langue (§5.3). Le modèle de langue
     * n'agit que si le lexique est chargé et que le clavier n'est pas en [restOnly] ; sinon `λ = 0`.
     *
     * @return le résultat (tampon réutilisé par l'appel suivant), ou `null` si le tap est hors du clavier.
     */
    fun decode(x: Float, y: Float, displayed: KeyboardGeometry, rest: KeyboardGeometry): TapDecoding? {
        // Sans lexique ni préfixe, ou en mode restOnly, les poids sont neutres : l'a priori vaut 1
        // pour chaque lettre, ce qui est un décodage purement spatial (λ = 0).
        return if (decoder.decode(x, y, displayed, rest, weights, decoding)) decoding else null
    }

    private fun refresh(nowMs: Long) {
        if (computed) return
        computed = true
        val source = source
        val prefix = prefix
        if (restOnly || source == null || prefix == null) {
            weights.setNeutral()
        } else {
            calculator.compute(prefix, source.distribution(prefix), source, weights)
        }
        if (restOnly) return
        solver.solve(weights, target)
        state.submit(target, nowMs)
    }
}
