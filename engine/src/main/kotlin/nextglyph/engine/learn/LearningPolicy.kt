package nextglyph.engine.learn

import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.PredictionSettings

/**
 * Où le clavier a le droit d'apprendre (spec §4, §9.2, §12) : un seul endroit pour la règle, que
 * respectent le dictionnaire personnel, le modèle spatial et la mémoire des refus.
 *
 * Rien n'est appris en incognito ni dans les profils mot de passe et code PIN
 * ([PredictionSettings.learningEnabled]).
 */
object LearningPolicy {

    /**
     * Dictionnaire personnel : en plus, seulement là où des mots sont proposés (profils Texte et Nom de
     * personne, sans `NO_SUGGESTIONS`). Jamais en e-mail ni en URL (adresses, identifiants), ni dans les
     * champs numériques.
     */
    fun allowsWords(settings: PredictionSettings): Boolean = settings.learningEnabled && settings.suggestionsEnabled

    /**
     * Modèle spatial : les profils où l'on tape des lettres (texte, nom, e-mail, URL). Les statistiques
     * sont par touche, sans aucun texte.
     */
    fun allowsTaps(settings: PredictionSettings): Boolean =
        settings.learningEnabled && settings.profile != FieldProfile.NUMERIC
}
