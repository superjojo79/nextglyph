package nextglyph.engine.profile

import nextglyph.engine.layout.DateTimeVariant
import nextglyph.engine.profile.AndroidInputConstants as C

/**
 * Choix du profil de saisie d'un champ (spec §4) : premier profil applicable dans l'ordre
 * code PIN, numérique, téléphone, date/heure, mot de passe, e-mail, URL, nom de personne, texte.
 *
 * Fonction pure et totale : toute combinaison d'entiers donne un profil (classe inconnue : texte ordinaire).
 * Les drapeaux n'ont de sens que dans leur classe (`TYPE_NUMBER_FLAG_*` seulement pour les nombres,
 * `TYPE_TEXT_FLAG_*` seulement pour le texte) ; les autres bits sont ignorés.
 */
object InputProfileResolver {

    /**
     * @param inputType `EditorInfo.inputType`.
     * @param imeOptions `EditorInfo.imeOptions`.
     * @param privateImeOptions `EditorInfo.privateImeOptions`, réservé : aucune option privée n'est définie
     *   en V1 (la valeur est acceptée et ignorée).
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolve(inputType: Int, imeOptions: Int, privateImeOptions: String?): InputProfile {
        val variation = inputType and C.TYPE_MASK_VARIATION
        val incognito = imeOptions and C.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        return when (inputType and C.TYPE_MASK_CLASS) {
            C.TYPE_CLASS_NUMBER ->
                if (variation == C.TYPE_NUMBER_VARIATION_PASSWORD) {
                    locked(ProfileType.PIN, ProfileLayout.PIN_PAD, incognito)
                } else {
                    locked(
                        ProfileType.NUMBER, ProfileLayout.NUMBER_PAD, incognito,
                        signed = inputType and C.TYPE_NUMBER_FLAG_SIGNED != 0,
                        decimal = inputType and C.TYPE_NUMBER_FLAG_DECIMAL != 0,
                    )
                }
            C.TYPE_CLASS_PHONE -> locked(ProfileType.PHONE, ProfileLayout.PHONE_PAD, incognito)
            C.TYPE_CLASS_DATETIME -> locked(
                ProfileType.DATE_TIME, ProfileLayout.DATE_TIME_PAD, incognito,
                variant = when (variation) {
                    C.TYPE_DATETIME_VARIATION_DATE -> DateTimeVariant.DATE
                    C.TYPE_DATETIME_VARIATION_TIME -> DateTimeVariant.TIME
                    else -> DateTimeVariant.DATE_TIME
                },
            )
            C.TYPE_CLASS_TEXT -> text(inputType, variation, incognito)
            // TYPE_NULL ou classe inconnue : « tout le reste », texte ordinaire (variation et drapeaux sans sens).
            else -> text(0, 0, incognito)
        }
    }

    /** Profils sans texte libre : pavés et mot de passe (§7.2, §8). Aucune lecture, aucun apprentissage. */
    private fun locked(
        type: ProfileType,
        layout: ProfileLayout,
        incognito: Boolean,
        signed: Boolean = false,
        decimal: Boolean = false,
        variant: DateTimeVariant? = null,
    ) = InputProfile(
        type = type, layout = layout,
        adaptation = false, suggestions = false, readsText = false, learning = false,
        autoCapitalize = false, capitalization = CapitalizationMode.NONE, typography = false,
        zeroLambda = true, keyPreview = false, multiLine = false, incognito = incognito,
        contactsPriority = false, shortcutBar = ShortcutBar.NONE,
        numberSigned = signed, numberDecimal = decimal, dateTimeVariant = variant,
    )

    private fun text(inputType: Int, variation: Int, incognito: Boolean): InputProfile {
        val type = when (variation) {
            C.TYPE_TEXT_VARIATION_PASSWORD, C.TYPE_TEXT_VARIATION_WEB_PASSWORD, C.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ->
                return locked(ProfileType.PASSWORD, ProfileLayout.PASSWORD, incognito)
            C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, C.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> ProfileType.EMAIL
            C.TYPE_TEXT_VARIATION_URI -> ProfileType.URL
            C.TYPE_TEXT_VARIATION_PERSON_NAME -> ProfileType.PERSON_NAME
            else -> ProfileType.TEXT
        }
        // E-mail et URL : ni majuscule automatique ni typographie française ni suggestions de mots (§9.1).
        val prose = type == ProfileType.TEXT || type == ProfileType.PERSON_NAME
        val requested = when {
            inputType and C.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> CapitalizationMode.CHARACTERS
            inputType and C.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> CapitalizationMode.WORDS
            inputType and C.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 -> CapitalizationMode.SENTENCES
            else -> CapitalizationMode.NONE
        }
        val capitalization = if (prose) requested else CapitalizationMode.NONE
        return InputProfile(
            type = type, layout = ProfileLayout.COMPACT,
            adaptation = true,
            suggestions = prose && inputType and C.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0,
            readsText = true,
            learning = !incognito,
            autoCapitalize = capitalization != CapitalizationMode.NONE,
            capitalization = capitalization,
            typography = prose,
            zeroLambda = false,
            keyPreview = true,
            multiLine = inputType and C.TYPE_TEXT_FLAG_MULTI_LINE != 0,
            incognito = incognito,
            contactsPriority = type == ProfileType.PERSON_NAME,
            shortcutBar = when (type) {
                ProfileType.EMAIL -> ShortcutBar.EMAIL
                ProfileType.URL -> ShortcutBar.URL
                else -> ShortcutBar.NONE
            },
            numberSigned = false, numberDecimal = false, dateTimeVariant = null,
        )
    }
}
