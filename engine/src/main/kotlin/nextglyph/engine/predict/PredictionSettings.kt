package nextglyph.engine.predict

/** Profil du champ, du point de vue de la prédiction (spec §4) ; déduit d'`EditorInfo` par l'IME. */
enum class FieldProfile {
    /** Profil Texte (§5) : suggestions et validation par espace. */
    TEXT,

    /** Nom de personne (§4, priorité 8) : comme le texte (contacts prioritaires en M4). */
    PERSON_NAME,

    /** Mot de passe et code PIN (§7) : ni suggestions, ni validation, ni apprentissage. */
    PASSWORD,

    /** Adresse e-mail (§9.1) : pas de validation par espace ni de suggestions de mots. */
    EMAIL,

    /** Adresse web (§9.1) : idem. */
    URL,

    /** Pavés numérique, téléphone, date (§8). */
    NUMERIC,
}

/**
 * Réglages et contraintes du champ qui conditionnent la prédiction (§4, §5.5, §9.2, §14).
 *
 * @property noSuggestions drapeau `TYPE_TEXT_FLAG_NO_SUGGESTIONS`.
 * @property incognito `IME_FLAG_NO_PERSONALIZED_LEARNING` ou mode forcé : aucune écriture (pas de
 *   compteur de refus).
 * @property autocommit réglage utilisateur « Validation par espace ».
 */
data class PredictionSettings(
    val profile: FieldProfile = FieldProfile.TEXT,
    val noSuggestions: Boolean = false,
    val incognito: Boolean = false,
    val autocommit: Boolean = true,
    val prudence: Prudence = Prudence.NORMAL,
) {
    /** Barre de suggestions de mots active (profils Texte et Nom de personne, sans `NO_SUGGESTIONS`). */
    val suggestionsEnabled: Boolean
        get() = (profile == FieldProfile.TEXT || profile == FieldProfile.PERSON_NAME) && !noSuggestions

    /**
     * Validation par espace active (§5.5, « Désactivation ») : restitution des accents et de la
     * casse, complétion et correction. Sinon, l'espace insère une espace et rien d'autre.
     */
    val autocommitEnabled: Boolean get() = suggestionsEnabled && autocommit

    /** Apprentissage autorisé (compteurs de refus) : ni incognito, ni mot de passe. */
    val learningEnabled: Boolean get() = !incognito && profile != FieldProfile.PASSWORD
}

/**
 * Mémoire des validations automatiques annulées (§5.5, « Apprentissage du refus »), par paire
 * (texte tapé, texte inséré), en minuscules. Point d'extension : l'IME pourra fournir une
 * implémentation persistante (M4) ; le moteur ne l'appelle en écriture que si l'apprentissage est
 * autorisé ([PredictionSettings.learningEnabled]).
 */
interface RejectionStore {
    /** Nombre d'annulations enregistrées pour le remplacement de [typed] par [inserted]. */
    fun rejections(typed: String, inserted: String): Int

    /** Enregistre une annulation du remplacement de [typed] par [inserted]. */
    fun recordRejection(typed: String, inserted: String)
}

/** [RejectionStore] en mémoire, perdu à la fermeture du clavier. Non thread-safe. */
class InMemoryRejectionStore : RejectionStore {
    private val counts = HashMap<Pair<String, String>, Int>()

    override fun rejections(typed: String, inserted: String): Int = counts[pair(typed, inserted)] ?: 0

    override fun recordRejection(typed: String, inserted: String) {
        counts.merge(pair(typed, inserted), 1, Int::plus)
    }

    /** Oublie toutes les annulations (réglage « Effacer les données apprises », §12). */
    fun clear() = counts.clear()

    private fun pair(typed: String, inserted: String) = typed.lowercase() to inserted.lowercase()
}
