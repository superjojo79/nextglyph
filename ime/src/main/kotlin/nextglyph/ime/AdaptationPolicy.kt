package nextglyph.ime

import android.text.InputType

/**
 * Champs où l'adaptation visuelle et la lecture du texte avant le curseur sont permises.
 *
 * Le champ commande (spec §2.8) : pas d'adaptation, et surtout aucune lecture du texte, dans les
 * champs de mot de passe, d'e-mail et d'adresse web (le texte n'y est pas un mot du lexique) ni dans
 * les champs numériques, téléphone et date/heure. `TYPE_TEXT_FLAG_NO_SUGGESTIONS` garde l'adaptation (§4).
 */
object AdaptationPolicy {

    private val NON_TEXT_CLASSES = setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)

    private val EXCLUDED_TEXT_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_URI,
    )

    fun allowsAdaptation(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass in NON_TEXT_CLASSES) return false
        if (inputClass != InputType.TYPE_CLASS_TEXT) return true
        return inputType and InputType.TYPE_MASK_VARIATION !in EXCLUDED_TEXT_VARIATIONS
    }
}
