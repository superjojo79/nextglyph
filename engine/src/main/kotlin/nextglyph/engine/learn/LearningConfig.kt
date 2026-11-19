package nextglyph.engine.learn

/**
 * Constantes du modèle spatial personnalisé (spec §5.3), immuables. Les décalages sont des fractions
 * de la largeur de la touche (horizontal) ou de la hauteur de rangée (vertical), comme
 * [nextglyph.engine.adapt.TapSpatialModel].
 *
 * Estimation : moyenne et variance à pondération exponentielle (poids `1 / min(n, window)`), puis
 * retour vers les valeurs par défaut tant que l'échantillon est petit :
 * `valeur = défaut + (estimation − défaut) · n / (n + priorWeight)`.
 *
 * @property window nombre de frappes au-delà duquel l'estimation oublie les plus anciennes (300) : le
 *   modèle suit l'évolution de la prise en main (téléphone changé, une main au lieu de deux).
 * @property priorWeight poids, en frappes, des valeurs par défaut dans l'estimation (20) : avec 20
 *   frappes, l'estimation compte pour moitié ; avec 200, pour 91 %. Un modèle vide vaut exactement le défaut.
 * @property maxOffset borne du décalage moyen estimé, en fraction de la touche (0,25 : le centre ne
 *   quitte jamais le quart central d'une touche).
 * @property maxSampleOffset décalage au-delà duquel une frappe est ignorée (0,5 : la frappe doit tomber
 *   dans le rectangle au repos de sa touche) ; une frappe plus éloignée a probablement été attribuée à
 *   cette lettre par le modèle de langue, et fausserait la moyenne.
 * @property expectedSpread dispersion (écart type, en fraction de la touche) d'une frappe « typique » :
 *   un facteur `σ` de 1 correspond à cette dispersion mesurée (0,23, valeur choisie : `σ` par défaut
 *   du décodeur, 0,35, est cette dispersion élargie de moitié pour tolérer les erreurs).
 * @property minSigmaScale borne basse du facteur `σ` (0,7) ; les frappes validées sont censurées (elles
 *   sont tombées sur la touche), ce qui sous-estime la dispersion réelle.
 * @property maxSigmaScale borne haute du facteur `σ` (1,6).
 * @property maxStaged nombre maximal de frappes en attente de validation (64 : un mot long).
 */
data class SpatialConfig(
    val window: Int = 300,
    val priorWeight: Double = 20.0,
    val maxOffset: Double = 0.25,
    val maxSampleOffset: Double = 0.5,
    val expectedSpread: Double = 0.23,
    val minSigmaScale: Double = 0.7,
    val maxSigmaScale: Double = 1.6,
    val maxStaged: Int = 64,
) {
    init {
        require(window >= 1) { "La fenêtre doit contenir au moins une frappe ($window)." }
        require(priorWeight >= 0.0 && priorWeight.isFinite()) { "Le poids des valeurs par défaut doit être positif ou nul ($priorWeight)." }
        require(maxOffset in 0.0..MAX_FRACTION) { "maxOffset doit être dans [0, $MAX_FRACTION] ($maxOffset)." }
        require(maxSampleOffset in 0.0..MAX_FRACTION) { "maxSampleOffset doit être dans [0, $MAX_FRACTION] ($maxSampleOffset)." }
        require(expectedSpread > 0.0 && expectedSpread.isFinite()) { "expectedSpread doit être strictement positif ($expectedSpread)." }
        require(minSigmaScale > 0.0 && minSigmaScale <= 1.0) { "minSigmaScale doit être dans ]0, 1] ($minSigmaScale)." }
        require(maxSigmaScale >= 1.0 && maxSigmaScale.isFinite()) { "maxSigmaScale doit valoir au moins 1 ($maxSigmaScale)." }
        require(maxStaged >= 1) { "Au moins une frappe en attente ($maxStaged)." }
    }

    private companion object {
        const val MAX_FRACTION = 0.5
    }
}

/**
 * Constantes de l'apprentissage local (spec §10.5, §5.5, §5.3, §12), immuables.
 *
 * **Dictionnaire personnel.** Un mot inconnu validé tel quel est mis en attente (au plus
 * [maxPending] mots, le moins récemment vu étant oublié le premier) et promu après
 * [minOccurrences] validations sans annulation. Le dictionnaire promu contient au plus [maxWords]
 * mots ; au-delà, le mot **le moins récemment utilisé** est évincé (une validation ou une
 * importation compte comme une utilisation). Une forme d'affichage par mot (la plus utilisée
 * l'emporte) jusqu'à [maxFormsPerWord] formes.
 *
 * @property minOccurrences `learn.minOccurrences` (§10.5) : 2.
 * @property minWordLength longueur minimale d'un mot appris (2).
 * @property maxWordLength longueur maximale (32) ; plus court que la limite de `TextNormalizer`, car un
 *   « mot » plus long est presque toujours un identifiant ou une faute.
 * @property maxWords plafond du dictionnaire personnel, toutes langues confondues (2 000). À
 *   [baseShare] chacun, ils pèsent au plus 2 % de la masse du lexique principal.
 * @property maxPending plafond de la liste d'attente (500).
 * @property maxFormsPerWord formes d'affichage conservées par mot (3).
 * @property maxCount plafond du compteur d'utilisations d'un mot (1 000 000), pour borner les fichiers
 *   importés ou corrompus.
 * @property baseShare fréquence d'un mot appris, en part de la masse du lexique de sa langue (1e-5 :
 *   un mot assez courant, comparable aux contacts, §6.3), multipliée par [usageBoost].
 * @property maxUsageBoost plafond du facteur d'usage `1 + ln(utilisations)` (4).
 * @property maxRejections plafond de la mémoire des refus (2 000 paires) ; la paire la moins
 *   récemment refusée est oubliée en premier.
 * @property maxRejectionCount plafond du compteur de refus d'une paire (1 000).
 * @property maxImportLines nombre maximal de lignes lues à l'importation (50 000).
 * @property maxImportBytes taille maximale d'un fichier d'importation (2 Mio).
 * @property maxFileBytes taille maximale du fichier d'état appris à la lecture (4 Mio).
 * @property spatial modèle spatial (§5.3).
 */
data class LearningConfig(
    val minOccurrences: Int = 2,
    val minWordLength: Int = 2,
    val maxWordLength: Int = 32,
    val maxWords: Int = 2000,
    val maxPending: Int = 500,
    val maxFormsPerWord: Int = 3,
    val maxCount: Int = 1_000_000,
    val baseShare: Double = 1e-5,
    val maxUsageBoost: Double = 4.0,
    val maxRejections: Int = 2000,
    val maxRejectionCount: Int = 1000,
    val maxImportLines: Int = 50_000,
    val maxImportBytes: Int = 2 * 1024 * 1024,
    val maxFileBytes: Int = 4 * 1024 * 1024,
    val spatial: SpatialConfig = SpatialConfig(),
) {
    init {
        require(minOccurrences >= 1) { "minOccurrences doit valoir au moins 1 ($minOccurrences)." }
        require(minWordLength >= 1) { "minWordLength doit valoir au moins 1 ($minWordLength)." }
        require(maxWordLength >= minWordLength) { "maxWordLength ($maxWordLength) doit valoir au moins minWordLength ($minWordLength)." }
        require(maxWords >= 1) { "maxWords doit valoir au moins 1 ($maxWords)." }
        require(maxPending >= 1) { "maxPending doit valoir au moins 1 ($maxPending)." }
        require(maxFormsPerWord >= 1) { "maxFormsPerWord doit valoir au moins 1 ($maxFormsPerWord)." }
        require(maxCount >= minOccurrences) { "maxCount ($maxCount) doit valoir au moins minOccurrences ($minOccurrences)." }
        require(baseShare > 0.0 && baseShare <= 1.0) { "baseShare doit être dans ]0, 1] ($baseShare)." }
        require(maxUsageBoost >= 1.0 && maxUsageBoost.isFinite()) { "maxUsageBoost doit valoir au moins 1 ($maxUsageBoost)." }
        require(maxRejections >= 1) { "maxRejections doit valoir au moins 1 ($maxRejections)." }
        require(maxRejectionCount >= 1) { "maxRejectionCount doit valoir au moins 1 ($maxRejectionCount)." }
        require(maxImportLines >= 1) { "maxImportLines doit valoir au moins 1 ($maxImportLines)." }
        require(maxImportBytes >= 1) { "maxImportBytes doit valoir au moins 1 ($maxImportBytes)." }
        require(maxFileBytes >= 1) { "maxFileBytes doit valoir au moins 1 ($maxFileBytes)." }
    }
}
