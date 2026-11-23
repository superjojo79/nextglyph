package nextglyph.engine.predict

/**
 * Réglage « Prudence de la validation » (spec §5.5, §14) : seuils `autocommit.minProb` (probabilité
 * minimale du premier candidat) et `autocommit.minRatio` (rapport minimal entre le premier et le
 * deuxième candidat). Valeurs provisoires, à recalibrer ici et nulle part ailleurs.
 *
 * @property minProb probabilité minimale du premier candidat.
 * @property minRatio rapport minimal `p1 / p2` (au moins 1).
 */
enum class Prudence(val minProb: Double, val minRatio: Double) {
    /** Prudente : 0,80 et 3,0. */
    CAUTIOUS(0.80, 3.0),

    /** Normale (défaut) : 0,70 et 2,0. */
    NORMAL(0.70, 2.0),

    /** Audacieuse : 0,60 et 1,5. */
    BOLD(0.60, 1.5),
}

/**
 * Coûts multiplicatifs de la correction à distance d'édition 1 (spec §10.4), repris de l'analyse M0
 * (`docs/analysis/M0-corpus.md`, partie 2). Le score d'un mot `w` atteint par une édition `e` du
 * texte tapé est `poids(e) × fréquence(w)` ; une complétion exacte a le poids 1.
 *
 * @property adjacentSubstitution lettre remplacée par une touche voisine (`bonjoir` → `bonjour`).
 * @property otherSubstitution lettre remplacée par une lettre non voisine.
 * @property omission caractère manquant.
 * @property extra caractère en trop.
 * @property transposition deux caractères consécutifs inversés.
 * @property separator apostrophe ou trait d'union omis (`dont` → `don't`, `peutetre` →
 *   `peut-être`) ; aussi le poids de base d'une élision française omise (`jai` → `j'ai`).
 */
data class CorrectionWeights(
    val adjacentSubstitution: Double = 0.01,
    val otherSubstitution: Double = 0.0005,
    val omission: Double = 0.002,
    val extra: Double = 0.002,
    val transposition: Double = 0.002,
    val separator: Double = 0.5,
) {
    init {
        for ((name, value) in listOf(
            "adjacentSubstitution" to adjacentSubstitution,
            "otherSubstitution" to otherSubstitution,
            "omission" to omission,
            "extra" to extra,
            "transposition" to transposition,
            "separator" to separator,
        )) {
            require(value in 0.0..1.0) { "Le poids $name doit être dans [0, 1] ($value)." }
        }
    }
}

/**
 * Constantes de la prédiction, de la validation par espace et du bilinguisme. Immuable : une
 * recalibration ne touche que cette classe.
 *
 * @property correction poids des éditions (§10.4).
 * @property neighborDistance distance maximale entre les centres au repos de deux touches voisines,
 *   en unités de touche (1,5 u, comme l'analyse M0).
 * @property formsPerPrefix nombre de formes lues par préfixe candidat et de candidats conservés par
 *   langue (6, comme l'analyse M0).
 * @property spatialSigma écart type (en largeurs de touche, hauteurs de rangée) de la frappe autour du
 *   centre d'une touche, pour le score spatial des substitutions : 0,35 (= `TapDecoderConfig.sigma`).
 * @property spatialScale facteur du rapport de vraisemblances d'une substitution (]0, 1]) : 0,6, de sorte
 *   qu'une frappe au centre de la touche tapée donne à une voisine horizontale le poids d'environ 0,01
 *   (`adjacentSubstitution`).
 * @property rejectLimit `autocommit.rejectLimit` (§5.5) : nombre d'annulations d'une même
 *   correction après lequel elle n'est plus validée automatiquement (2).
 * @property languageWindow fenêtre du score de langue, en mots validés (§10.6) : 4.
 * @property languagePrior poids de la langue préférée de départ dans le score (1 mot).
 * @property preferredLanguageShare part `π` de départ de la langue préférée (0,75, comme l'analyse M0).
 * @property elisions élisions par code de langue (§5.8), sans l'apostrophe : candidates de la
 *   validation quand l'apostrophe est omise (`lecole` → `l'école`).
 * @property elisionInitials initiales (clés normalisées) devant lesquelles une élision est possible.
 * @property accentDominance part minimale des occurrences d'une clé que doit représenter une
 *   orthographe pour être restituée sans ambiguïté (§5.8) : 0,95.
 */
data class PredictionConfig(
    val correction: CorrectionWeights = CorrectionWeights(),
    val neighborDistance: Double = 1.5,
    val formsPerPrefix: Int = 6,
    val spatialSigma: Double = 0.35,
    val spatialScale: Double = 0.6,
    val rejectLimit: Int = 2,
    val languageWindow: Int = 4,
    val languagePrior: Double = 1.0,
    val preferredLanguageShare: Double = 0.75,
    val elisions: Map<String, List<String>> = mapOf("fr" to FRENCH_ELISIONS),
    val elisionInitials: String = "aeiouyh",
    val accentDominance: Double = 0.95,
) {
    init {
        require(neighborDistance > 0.0) { "La distance de voisinage doit être strictement positive ($neighborDistance)." }
        require(formsPerPrefix >= 1) { "Au moins une forme par préfixe ($formsPerPrefix)." }
        require(spatialSigma > 0.0) { "spatialSigma doit être strictement positif ($spatialSigma)." }
        require(spatialScale > 0.0 && spatialScale <= 1.0) { "spatialScale doit être dans ]0 ; 1] ($spatialScale)." }
        require(rejectLimit >= 1) { "rejectLimit doit être au moins 1 ($rejectLimit)." }
        require(languageWindow >= 1) { "La fenêtre de langue doit contenir au moins un mot ($languageWindow)." }
        require(languagePrior > 0.0) { "Le poids de la langue de départ doit être strictement positif ($languagePrior)." }
        require(accentDominance > 0.5 && accentDominance <= 1.0) { "accentDominance doit être dans ]0,5 ; 1] ($accentDominance)." }
        require(preferredLanguageShare in 0.0..1.0) { "La part de la langue préférée doit être dans [0, 1] ($preferredLanguageShare)." }
    }

    companion object {
        /** Élisions françaises (§5.8), comme `WordPrefix`. */
        val FRENCH_ELISIONS: List<String> =
            listOf("c", "d", "j", "l", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu")
    }
}
