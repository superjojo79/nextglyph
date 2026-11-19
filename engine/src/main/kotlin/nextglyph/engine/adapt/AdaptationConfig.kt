package nextglyph.engine.adapt

/** Mode d'adaptation visuelle choisi par l'utilisateur (spec §5.2, §14). */
enum class AdaptationMode {
    /** Aucune adaptation : disposition au repos (aussi utilisé avec TalkBack, §13). */
    OFF,

    /** Opacité légèrement modulée ; ni libellé ni largeur. */
    CALM,

    /** Mode par défaut : opacité, libellé légèrement réduit, largeur faiblement plafonnée. */
    CALM_REINFORCED,

    /** Opacité forte, libellé, largeur plafonnée à `adapt.maxDisplacement`. */
    ELASTIC,
}

/**
 * Intensité des effets d'opacité et de libellé pour un mode (spec §5.2, tableau des modes).
 *
 * Chaque intensité multiplie l'écart à l'état de repos que donnent les formules :
 * 0 = effet absent, 1 = formule appliquée telle quelle. Le plafond de largeur de chaque mode
 * est donné par [AdaptationConfig.displacement].
 *
 * @property opacityStrength part de l'atténuation d'opacité appliquée, dans [0, 1].
 * @property labelStrength part de la réduction du libellé appliquée, dans [0, 1].
 */
data class ModeProfile(
    val opacityStrength: Float,
    val labelStrength: Float,
) {
    init {
        requireUnit(opacityStrength, "opacityStrength")
        requireUnit(labelStrength, "labelStrength")
    }

    companion object {
        /** Profil sans aucun effet. */
        val NONE: ModeProfile = ModeProfile(0f, 0f)
    }
}

/**
 * Paramètres du décodeur de tap (spec §5.3).
 *
 * @property alpha poids du centre affiché dans le mélange de gaussiennes (le reste va au centre
 *   de repos), dans [0, 1]. Spec : 0,5.
 * @property sigma écart type de chaque gaussienne, en fraction de la largeur au repos de la touche
 *   (horizontalement) et de la hauteur de rangée (verticalement). **Valeur choisie : 0,35**
 *   (non fixée par la spec, en attendant le modèle spatial personnalisé) : un tap à une demi-touche
 *   du centre garde une vraisemblance de 36 % du maximum.
 * @property lambda exposant du modèle de langue. Spec : 0,6 ; 0 = décodage purement spatial
 *   (mot de passe, pavés numériques).
 * @property minPrior plancher de `r(c)` utilisé comme a priori, pour qu'une lettre de poids nul
 *   reste décodable hors du noyau. **Valeur choisie : 0,05.**
 * @property coreFraction fraction centrale (en largeur et en hauteur) de la touche affichée dans
 *   laquelle un tap produit toujours cette touche, quel que soit le modèle de langue.
 *   **Valeur choisie : 0,5** (voir `docs/DECISIONS.md`). 0 désactive le noyau.
 */
data class TapDecoderConfig(
    val alpha: Float = 0.5f,
    val sigma: Float = 0.35f,
    val lambda: Float = 0.6f,
    val minPrior: Float = 0.05f,
    val coreFraction: Float = 0.5f,
) {
    init {
        requireUnit(alpha, "alpha")
        require(sigma > 0f && sigma < Float.POSITIVE_INFINITY) { "sigma doit être strictement positif : $sigma." }
        require(lambda >= 0f && lambda < Float.POSITIVE_INFINITY) { "lambda doit être positif ou nul : $lambda." }
        require(minPrior > 0f && minPrior <= 1f) { "minPrior doit être dans ]0, 1] : $minPrior." }
        requireUnit(coreFraction, "coreFraction")
    }

    companion object {
        /** Décodage purement spatial (`λ = 0`), pour le mot de passe et les pavés numériques. */
        val SPATIAL: TapDecoderConfig = TapDecoderConfig(lambda = 0f)
    }
}

/**
 * Paramètres de l'adaptation visuelle (spec §5.2), immuables. Toutes les constantes réglables
 * du moteur adaptatif sont ici, avec les valeurs par défaut de la spec ; les valeurs que la spec
 * ne fixe pas sont signalées « valeur choisie » et consignées dans `docs/DECISIONS.md`.
 *
 * Unités : les largeurs sont relatives à la largeur au repos de chaque touche (1 = repos),
 * sauf [spaceGain], en unités de clavier (1 u = une lettre des rangées 1 et 2).
 *
 * Formules (avec `s = sqrt(r)` et `k` l'intensité des poids, cf. [KeyWeights.intensity]) :
 *
 * ```
 * opacité    = 1 − profil.opacityStrength · k · (1 − o),  o = r == 0 ? minOpacity : opacityBase + opacitySlope · s
 * libellé    = 1 − profil.labelStrength · k · (1 − l),    l = minLabelScale + (1 − minLabelScale) · s
 * largeur    = repos · (r == 0 ? minWidth : widthBase + gain · s), normalisée sur la rangée
 * espace     = repos + spaceGain · P(fin)
 * ```
 *
 * Les largeurs cibles sont ensuite mélangées avec le repos selon `k`, puis le solveur
 * ([LayoutSolver]) les ajuste pour respecter le plafond de déplacement et la largeur minimale.
 *
 * @property mode mode d'adaptation ; défaut : [AdaptationMode.CALM_REINFORCED].
 * @property maxDisplacement `adapt.maxDisplacement` : plafond du déplacement du centre d'une touche
 *   en mode Élastique, en fraction de sa largeur au repos. Spec : 0,5.
 * @property calmReinforcedDisplacement plafond « faible » du mode Calme renforcé.
 *   **Valeur choisie : 0,15.**
 * @property minOpacity `adapt.minOpacity` : opacité d'une lettre de poids nul. Spec : 0,30.
 * @property opacityBase constante de `0,55 + 0,45 · s` (spec).
 * @property opacitySlope pente de `0,55 + 0,45 · s` (spec).
 * @property minLabelScale échelle du libellé d'une lettre de poids nul, à intensité 1.
 *   **Valeur choisie : 0,7.**
 * @property minWidth `adapt.minWidth` : largeur d'une lettre de poids nul avant normalisation, et
 *   largeur minimale garantie après résolution. Spec : 0,6.
 * @property widthBase constante de `0,8 + adapt.gain · s` (spec).
 * @property gain `adapt.gain`. **Valeur choisie : 0,4** (la lettre la plus probable vise 1,2,
 *   soit deux fois la largeur d'une lettre de poids nul).
 * @property spaceGain `adapt.spaceGain`, en unités. **Valeur choisie : 0,6 u.**
 * @property calm intensités du mode Calme. **Valeurs choisies** : opacité 0,5 (« légère »), libellé 0.
 * @property calmReinforced intensités du mode Calme renforcé. **Valeurs choisies** : opacité 0,8,
 *   libellé 0,4 (« légère »).
 * @property elastic intensités du mode Élastique. **Valeurs choisies** : opacité 1 (« forte »), libellé 1.
 * @property wordStartIntensity atténuation en début de mot. Spec : « de moitié », 0,5.
 * @property correctionIntensity atténuation quand les poids viennent d'une correction du préfixe.
 *   **Valeur choisie : 0,5** (la correction n'est qu'une hypothèse).
 * @property correctionNeighborRadius distance maximale, en unités (une rangée = 1 u), entre les
 *   centres au repos de deux touches pour qu'une substitution soit envisagée. **Valeur choisie : 1,5.**
 * @property correctionAdjacencySigma écart type de la pondération gaussienne d'une substitution
 *   selon cette distance. **Valeur choisie : 1,0** (voisine sur la rangée : 0,61).
 * @property correctionDeletionWeight poids d'une lettre en trop. **Valeur choisie : 0,3.**
 * @property correctionInsertionWeight poids d'une lettre manquante. **Valeur choisie : 0,3.**
 * @property correctionMaxCandidates nombre maximal de préfixes candidats interrogés, pour
 *   borner le coût d'une correction. **Valeur choisie : 96.**
 * @property animationMs durée de l'animation. Spec : 100 ms.
 * @property easeOutPower exposant de la courbe `1 − (1 − t)^n`. **Valeur choisie : 2**
 *   (comme le `DecelerateInterpolator` d'Android).
 * @property fastTypingMs `adapt.fastTypingMs`. Spec : 120 ms.
 * @property decoder paramètres du décodeur de tap (§5.3).
 */
data class AdaptationConfig(
    val mode: AdaptationMode = AdaptationMode.CALM_REINFORCED,
    val maxDisplacement: Float = 0.5f,
    val calmReinforcedDisplacement: Float = 0.15f,
    val minOpacity: Float = 0.30f,
    val opacityBase: Float = 0.55f,
    val opacitySlope: Float = 0.45f,
    val minLabelScale: Float = 0.7f,
    val minWidth: Float = 0.6f,
    val widthBase: Float = 0.8f,
    val gain: Float = 0.4f,
    val spaceGain: Float = 0.6f,
    val calm: ModeProfile = ModeProfile(opacityStrength = 0.5f, labelStrength = 0f),
    val calmReinforced: ModeProfile = ModeProfile(opacityStrength = 0.8f, labelStrength = 0.4f),
    val elastic: ModeProfile = ModeProfile(opacityStrength = 1f, labelStrength = 1f),
    val wordStartIntensity: Float = 0.5f,
    val correctionIntensity: Float = 0.5f,
    val correctionNeighborRadius: Float = 1.5f,
    val correctionAdjacencySigma: Float = 1f,
    val correctionDeletionWeight: Float = 0.3f,
    val correctionInsertionWeight: Float = 0.3f,
    val correctionMaxCandidates: Int = 96,
    val animationMs: Long = 100L,
    val easeOutPower: Float = 2f,
    val fastTypingMs: Long = 120L,
    val decoder: TapDecoderConfig = TapDecoderConfig(),
) {
    init {
        requireUnit(maxDisplacement, "maxDisplacement")
        requireUnit(calmReinforcedDisplacement, "calmReinforcedDisplacement")
        requireUnit(minOpacity, "minOpacity")
        requireUnit(opacityBase, "opacityBase")
        requireUnit(opacitySlope, "opacitySlope")
        require(opacityBase + opacitySlope <= 1f) { "opacityBase + opacitySlope doit valoir au plus 1." }
        require(minLabelScale > 0f && minLabelScale <= 1f) {
            "minLabelScale doit être dans ]0, 1] : $minLabelScale."
        }
        require(minWidth > 0f && minWidth <= 1f) { "minWidth doit être dans ]0, 1] : $minWidth." }
        require(widthBase > 0f && widthBase < Float.POSITIVE_INFINITY) { "widthBase doit être strictement positif : $widthBase." }
        require(gain >= 0f && gain < Float.POSITIVE_INFINITY) { "gain doit être positif ou nul : $gain." }
        require(spaceGain >= 0f && spaceGain <= MAX_SPACE_GAIN) {
            "spaceGain doit être dans [0, $MAX_SPACE_GAIN] : $spaceGain."
        }
        requireUnit(wordStartIntensity, "wordStartIntensity")
        requireUnit(correctionIntensity, "correctionIntensity")
        require(correctionNeighborRadius >= 0f && correctionNeighborRadius < Float.POSITIVE_INFINITY) {
            "correctionNeighborRadius doit être positif ou nul : $correctionNeighborRadius."
        }
        require(correctionAdjacencySigma > 0f && correctionAdjacencySigma < Float.POSITIVE_INFINITY) {
            "correctionAdjacencySigma doit être strictement positif : $correctionAdjacencySigma."
        }
        requireUnit(correctionDeletionWeight, "correctionDeletionWeight")
        requireUnit(correctionInsertionWeight, "correctionInsertionWeight")
        require(correctionMaxCandidates >= 0) { "correctionMaxCandidates doit être positif ou nul : $correctionMaxCandidates." }
        require(animationMs >= 0L) { "animationMs doit être positif ou nul : $animationMs." }
        require(easeOutPower >= 1f && easeOutPower < Float.POSITIVE_INFINITY) { "easeOutPower doit valoir au moins 1 : $easeOutPower." }
        require(fastTypingMs >= 0L) { "fastTypingMs doit être positif ou nul : $fastTypingMs." }
    }

    /** Intensités d'opacité et de libellé du [mode] donné ([ModeProfile.NONE] pour Désactivé). */
    fun profile(mode: AdaptationMode = this.mode): ModeProfile = when (mode) {
        AdaptationMode.OFF -> ModeProfile.NONE
        AdaptationMode.CALM -> calm
        AdaptationMode.CALM_REINFORCED -> calmReinforced
        AdaptationMode.ELASTIC -> elastic
    }

    /**
     * Plafond du déplacement du centre d'une touche dans le [mode] donné, en fraction de sa largeur
     * au repos : 0 (largeurs au repos) pour Désactivé et Calme, [calmReinforcedDisplacement] pour
     * Calme renforcé, [maxDisplacement] pour Élastique.
     */
    fun displacement(mode: AdaptationMode = this.mode): Float = when (mode) {
        AdaptationMode.OFF, AdaptationMode.CALM -> 0f
        AdaptationMode.CALM_REINFORCED -> calmReinforcedDisplacement
        AdaptationMode.ELASTIC -> maxDisplacement
    }

    companion object {
        /** Croissance maximale admise pour l'espace, en unités. */
        const val MAX_SPACE_GAIN: Float = 2f
    }
}

private fun requireUnit(value: Float, name: String) {
    require(value >= 0f && value <= 1f) { "$name doit être dans [0, 1] : $value." }
}
