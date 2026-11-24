package nextglyph.engine.session

import nextglyph.engine.edit.CapsMode
import nextglyph.engine.edit.TextProfile
import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.Prudence
import nextglyph.engine.profile.AndroidInputConstants
import nextglyph.engine.profile.CapitalizationMode
import nextglyph.engine.profile.InputProfile
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.profile.ProfileType
import nextglyph.engine.profile.ShortcutBar

/**
 * Comportement de la session pour un champ (spec §4, §9), déduit du profil résolu par
 * [InputProfileResolver] et du comportement de la touche Entrée.
 *
 * @param autocommit réglage utilisateur « Validation par espace » (§14).
 * @param prudence réglage utilisateur « Prudence de la validation » (§5.5, §14).
 * @param nextWord réglage utilisateur « Prédiction du mot suivant » (issue #51, §14).
 */
class SessionField(
    val profile: InputProfile,
    val enter: EnterBehavior = EnterBehavior.EnterKey,
    autocommit: Boolean = true,
    prudence: Prudence = Prudence.NORMAL,
    nextWord: Boolean = true,
) {
    /** Réglages de la prédiction : profil, `NO_SUGGESTIONS`, incognito (aucun refus mémorisé, §9.2). */
    val prediction: PredictionSettings = PredictionSettings(
        profile = fieldProfile(profile.type),
        noSuggestions = !profile.suggestions,
        incognito = profile.incognito || !profile.learning,
        autocommit = autocommit && profile.autocommit,
        prudence = prudence,
        nextWord = nextWord,
    )

    /** Profil d'édition : typographie et espace automatique seulement pour la prose (§9.1). */
    val textProfile: TextProfile = when {
        !profile.readsText -> TextProfile.PASSWORD
        profile.type == ProfileType.EMAIL -> TextProfile.EMAIL
        profile.type == ProfileType.URL -> TextProfile.URL
        profile.type == ProfileType.PERSON_NAME -> TextProfile.PERSON_NAME
        profile.typography -> TextProfile.TEXT
        else -> TextProfile.PASSWORD
    }

    /** Majuscules automatiques demandées par le champ. */
    val capsMode: CapsMode = when (profile.capitalization) {
        CapitalizationMode.NONE -> CapsMode.NONE
        CapitalizationMode.SENTENCES -> CapsMode.SENTENCES
        CapitalizationMode.WORDS -> CapsMode.WORDS
        CapitalizationMode.CHARACTERS -> CapsMode.CHARACTERS
    }

    /** Le texte du champ est lu (faux : mot de passe et pavés, aucune lecture, §7.2, §8). */
    val readsText: Boolean get() = profile.readsText

    /** Mot en cours en composition, barre de suggestions et validation par espace. */
    val composes: Boolean get() = profile.readsText && prediction.suggestionsEnabled

    /** Raccourcis permanents de la barre (§9.1), vide hors e-mail et URL. */
    val shortcuts: List<String> get() = profile.shortcutBar.shortcuts

    /**
     * Adaptation visuelle : profils de prose seulement. E-mail et URL restent au repos tant qu'il
     * n'y a pas de lexique des domaines (§9.1) ; `NO_SUGGESTIONS` garde l'adaptation (§4).
     */
    val adaptation: Boolean get() = profile.adaptation && profile.readsText && profile.shortcutBar == ShortcutBar.NONE

    companion object {
        /** Champ de texte ordinaire (avant le premier `onStartInput`). */
        val DEFAULT: SessionField = SessionField(InputProfileResolver.resolve(AndroidInputConstants.TYPE_CLASS_TEXT, 0, null))

        private fun fieldProfile(type: ProfileType): FieldProfile = when (type) {
            ProfileType.TEXT -> FieldProfile.TEXT
            ProfileType.PERSON_NAME -> FieldProfile.PERSON_NAME
            ProfileType.EMAIL -> FieldProfile.EMAIL
            ProfileType.URL -> FieldProfile.URL
            ProfileType.PASSWORD, ProfileType.PIN -> FieldProfile.PASSWORD
            ProfileType.NUMBER, ProfileType.PHONE, ProfileType.DATE_TIME -> FieldProfile.NUMERIC
        }
    }
}
