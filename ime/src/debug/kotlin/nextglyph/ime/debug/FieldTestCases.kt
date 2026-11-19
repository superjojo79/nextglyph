package nextglyph.ime.debug

import android.text.InputType
import android.view.inputmethod.EditorInfo
import nextglyph.ime.R

/**
 * Un champ du panel de test (spec §15.3).
 *
 * @property viewId identifiant de l'`EditText` (nom de ressource `field_<key>`, lisible par `uiautomator dump`).
 * @property key nom court, repris dans l'écho du champ.
 * @property pointOnly le champ n'accepte que le point comme séparateur décimal (repli du §8.1).
 */
data class FieldTestCase(
    val viewId: Int,
    val key: String,
    val label: String,
    val inputType: Int,
    val imeOptions: Int = EditorInfo.IME_NULL,
    val pointOnly: Boolean = false,
)

/** Les champs du panel « Champs de test » : un par profil et par drapeau du §4, plus les actions de `↵`. */
object FieldTestCases {

    private const val TEXT = InputType.TYPE_CLASS_TEXT
    private const val NUMBER = InputType.TYPE_CLASS_NUMBER
    private const val DATETIME = InputType.TYPE_CLASS_DATETIME

    val ALL: List<FieldTestCase> = listOf(
        FieldTestCase(R.id.field_text, "text", "Texte", TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
        FieldTestCase(
            R.id.field_multiline, "multiline", "Texte multiligne",
            TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        ),
        FieldTestCase(
            R.id.field_person_name, "person_name", "Nom de personne",
            TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        ),
        FieldTestCase(R.id.field_email, "email", "E-mail", TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        FieldTestCase(R.id.field_url, "url", "URL", TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        FieldTestCase(R.id.field_password, "password", "Mot de passe", TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        FieldTestCase(
            R.id.field_visible_password, "visible_password", "Mot de passe visible",
            TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        ),
        FieldTestCase(R.id.field_web_password, "web_password", "Mot de passe web", TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
        FieldTestCase(R.id.field_number, "number", "Numérique", NUMBER),
        FieldTestCase(R.id.field_number_signed, "number_signed", "Numérique signé", NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED),
        FieldTestCase(R.id.field_number_decimal, "number_decimal", "Numérique décimal", NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
        FieldTestCase(
            R.id.field_number_signed_decimal, "number_signed_decimal", "Numérique signé décimal",
            NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        ),
        FieldTestCase(
            R.id.field_number_decimal_point, "number_decimal_point", "Numérique décimal (point seul)",
            NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, pointOnly = true,
        ),
        FieldTestCase(R.id.field_pin, "pin", "Code PIN", NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        FieldTestCase(R.id.field_phone, "phone", "Téléphone", InputType.TYPE_CLASS_PHONE),
        FieldTestCase(R.id.field_date, "date", "Date", DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE),
        FieldTestCase(R.id.field_time, "time", "Heure", DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME),
        FieldTestCase(R.id.field_date_time, "date_time", "Date et heure", DATETIME or InputType.TYPE_DATETIME_VARIATION_NORMAL),
        FieldTestCase(R.id.field_no_suggestions, "no_suggestions", "Sans suggestions", TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
        FieldTestCase(R.id.field_incognito, "incognito", "Incognito", TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        FieldTestCase(R.id.field_action_send, "action_send", "Action « envoyer »", TEXT, EditorInfo.IME_ACTION_SEND),
        FieldTestCase(R.id.field_action_search, "action_search", "Action « rechercher »", TEXT, EditorInfo.IME_ACTION_SEARCH),
        FieldTestCase(R.id.field_action_go, "action_go", "Action « aller »", TEXT, EditorInfo.IME_ACTION_GO),
        FieldTestCase(R.id.field_action_next, "action_next", "Action « suivant »", TEXT, EditorInfo.IME_ACTION_NEXT),
        FieldTestCase(R.id.field_action_done, "action_done", "Action « terminé »", TEXT, EditorInfo.IME_ACTION_DONE),
    )

    /** Écho d'un champ : texte exact, sauts de ligne en `⏎`, points de code hors ASCII imprimable en clair. */
    fun echo(key: String, text: CharSequence, lastAction: String?): String {
        val visible = text.toString().replace("\n", "⏎")
        val special = text.codePoints().filter { it !in 0x20..0x7e }.toArray().distinct()
            .joinToString(" ") { "U+%04X".format(it) }
        return buildString {
            append(key).append(" = «").append(visible).append('»')
            if (special.isNotEmpty()) append(" [").append(special).append(']')
            if (lastAction != null) append(" action=").append(lastAction)
        }
    }

    /** Nom d'une action d'éditeur pour l'écho. */
    fun actionName(actionId: Int): String = when (actionId) {
        EditorInfo.IME_ACTION_GO -> "go"
        EditorInfo.IME_ACTION_SEARCH -> "search"
        EditorInfo.IME_ACTION_SEND -> "send"
        EditorInfo.IME_ACTION_NEXT -> "next"
        EditorInfo.IME_ACTION_DONE -> "done"
        EditorInfo.IME_ACTION_PREVIOUS -> "previous"
        EditorInfo.IME_NULL -> "enter"
        else -> "action$actionId"
    }
}
