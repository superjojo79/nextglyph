package nextglyph.engine.learn

/**
 * Tout ce que le clavier apprend, au même endroit (spec §10.5, §5.5, §5.3, §12) : dictionnaire
 * personnel, mémoire des refus et modèle spatial. Un seul fichier ([LearnedDataFile]), un seul
 * geste pour tout effacer ([clear]).
 *
 * Aucune API Android : l'IME fournit les flux de lecture et d'écriture. Pas thread-safe.
 */
class LearnedData(
    val config: LearningConfig = LearningConfig(),
    val personal: PersonalDictionary = PersonalDictionary(config),
    val rejections: PersistentRejectionStore = PersistentRejectionStore(config),
    val spatial: SpatialModel = SpatialModel(config.spatial),
) {

    /** Somme des révisions des trois parties : change dès que quelque chose est à enregistrer. */
    val revision: Long get() = personal.revision + rejections.revision + spatial.revision

    /** Vrai si rien n'a été appris : ni mot (promu ou en attente), ni refus, ni frappe. */
    val isEmpty: Boolean get() = personal.size == 0 && personal.pendingSize == 0 && rejections.size == 0 && spatial.isEmpty

    /**
     * Efface tout l'état appris (§12, « Effacer les données apprises ») : mots promus et en attente,
     * refus, modèle spatial et frappes en attente. Le fichier enregistré est à réécrire ensuite
     * ([LearnedDataFile.write]) ou à supprimer par l'IME.
     */
    fun clear() {
        personal.clear()
        rejections.clear()
        spatial.reset()
    }
}
