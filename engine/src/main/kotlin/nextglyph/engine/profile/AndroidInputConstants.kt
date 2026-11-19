package nextglyph.engine.profile

/**
 * Constantes Android recopiées pour garder le moteur sans dépendance Android. Les valeurs sont celles de la
 * documentation officielle ; `AndroidInputConstantsTest` les vérifie une à une.
 *
 * Sources : https://developer.android.com/reference/android/text/InputType
 * et https://developer.android.com/reference/android/view/inputmethod/EditorInfo
 */
object AndroidInputConstants {
    /** `InputType.TYPE_MASK_CLASS`. */
    const val TYPE_MASK_CLASS: Int = 0x0000000f

    /** `InputType.TYPE_MASK_VARIATION`. */
    const val TYPE_MASK_VARIATION: Int = 0x00000ff0

    /** `InputType.TYPE_MASK_FLAGS`. */
    const val TYPE_MASK_FLAGS: Int = 0x00fff000

    /** `InputType.TYPE_NULL`. */
    const val TYPE_NULL: Int = 0

    /** `InputType.TYPE_CLASS_TEXT`. */
    const val TYPE_CLASS_TEXT: Int = 0x00000001

    /** `InputType.TYPE_CLASS_NUMBER`. */
    const val TYPE_CLASS_NUMBER: Int = 0x00000002

    /** `InputType.TYPE_CLASS_PHONE`. */
    const val TYPE_CLASS_PHONE: Int = 0x00000003

    /** `InputType.TYPE_CLASS_DATETIME`. */
    const val TYPE_CLASS_DATETIME: Int = 0x00000004

    /** `InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS`. */
    const val TYPE_TEXT_FLAG_CAP_CHARACTERS: Int = 0x00001000

    /** `InputType.TYPE_TEXT_FLAG_CAP_WORDS`. */
    const val TYPE_TEXT_FLAG_CAP_WORDS: Int = 0x00002000

    /** `InputType.TYPE_TEXT_FLAG_CAP_SENTENCES`. */
    const val TYPE_TEXT_FLAG_CAP_SENTENCES: Int = 0x00004000

    /** `InputType.TYPE_TEXT_FLAG_AUTO_CORRECT`. */
    const val TYPE_TEXT_FLAG_AUTO_CORRECT: Int = 0x00008000

    /** `InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE`. */
    const val TYPE_TEXT_FLAG_AUTO_COMPLETE: Int = 0x00010000

    /** `InputType.TYPE_TEXT_FLAG_MULTI_LINE`. */
    const val TYPE_TEXT_FLAG_MULTI_LINE: Int = 0x00020000

    /** `InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE`. */
    const val TYPE_TEXT_FLAG_IME_MULTI_LINE: Int = 0x00040000

    /** `InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS`. */
    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS: Int = 0x00080000

    /** `InputType.TYPE_TEXT_VARIATION_NORMAL`. */
    const val TYPE_TEXT_VARIATION_NORMAL: Int = 0x00000000

    /** `InputType.TYPE_TEXT_VARIATION_URI`. */
    const val TYPE_TEXT_VARIATION_URI: Int = 0x00000010

    /** `InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS`. */
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS: Int = 0x00000020

    /** `InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT`. */
    const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT: Int = 0x00000030

    /** `InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE`. */
    const val TYPE_TEXT_VARIATION_SHORT_MESSAGE: Int = 0x00000040

    /** `InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE`. */
    const val TYPE_TEXT_VARIATION_LONG_MESSAGE: Int = 0x00000050

    /** `InputType.TYPE_TEXT_VARIATION_PERSON_NAME`. */
    const val TYPE_TEXT_VARIATION_PERSON_NAME: Int = 0x00000060

    /** `InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS`. */
    const val TYPE_TEXT_VARIATION_POSTAL_ADDRESS: Int = 0x00000070

    /** `InputType.TYPE_TEXT_VARIATION_PASSWORD`. */
    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x00000080

    /** `InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`. */
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x00000090

    /** `InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT`. */
    const val TYPE_TEXT_VARIATION_WEB_EDIT_TEXT: Int = 0x000000a0

    /** `InputType.TYPE_TEXT_VARIATION_FILTER`. */
    const val TYPE_TEXT_VARIATION_FILTER: Int = 0x000000b0

    /** `InputType.TYPE_TEXT_VARIATION_PHONETIC`. */
    const val TYPE_TEXT_VARIATION_PHONETIC: Int = 0x000000c0

    /** `InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS`. */
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS: Int = 0x000000d0

    /** `InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD`. */
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x000000e0

    /** `InputType.TYPE_NUMBER_FLAG_SIGNED`. */
    const val TYPE_NUMBER_FLAG_SIGNED: Int = 0x00001000

    /** `InputType.TYPE_NUMBER_FLAG_DECIMAL`. */
    const val TYPE_NUMBER_FLAG_DECIMAL: Int = 0x00002000

    /** `InputType.TYPE_NUMBER_VARIATION_NORMAL`. */
    const val TYPE_NUMBER_VARIATION_NORMAL: Int = 0x00000000

    /** `InputType.TYPE_NUMBER_VARIATION_PASSWORD`. */
    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x00000010

    /** `InputType.TYPE_DATETIME_VARIATION_NORMAL`. */
    const val TYPE_DATETIME_VARIATION_NORMAL: Int = 0x00000000

    /** `InputType.TYPE_DATETIME_VARIATION_DATE`. */
    const val TYPE_DATETIME_VARIATION_DATE: Int = 0x00000010

    /** `InputType.TYPE_DATETIME_VARIATION_TIME`. */
    const val TYPE_DATETIME_VARIATION_TIME: Int = 0x00000020

    /** `EditorInfo.IME_MASK_ACTION`. */
    const val IME_MASK_ACTION: Int = 0x000000ff

    /** `EditorInfo.IME_ACTION_GO`. */
    const val IME_ACTION_GO: Int = 2

    /** `EditorInfo.IME_ACTION_SEARCH`. */
    const val IME_ACTION_SEARCH: Int = 3

    /** `EditorInfo.IME_ACTION_SEND`. */
    const val IME_ACTION_SEND: Int = 4

    /** `EditorInfo.IME_ACTION_NEXT`. */
    const val IME_ACTION_NEXT: Int = 5

    /** `EditorInfo.IME_ACTION_DONE`. */
    const val IME_ACTION_DONE: Int = 6

    /** `EditorInfo.IME_ACTION_PREVIOUS`. */
    const val IME_ACTION_PREVIOUS: Int = 7

    /** `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`. */
    const val IME_FLAG_NO_PERSONALIZED_LEARNING: Int = 0x01000000
}
