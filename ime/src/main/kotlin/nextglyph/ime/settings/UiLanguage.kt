package nextglyph.ime.settings

import nextglyph.ime.settings.KeyboardSettings.Companion.storedName
import java.util.Locale

/**
 * Langue de l'interface (réglage « Langue de l'interface », §14) : celle du système, ou le français ou l'anglais
 * imposé à l'application seule. [tag] est l'étiquette BCP 47 de la langue imposée, `null` pour [SYSTEM].
 *
 * La valeur est enregistrée sous [KEY_UI_LANGUAGE] sous la forme [storedName] (`system`, `french`, `english`).
 * Ce réglage n'est pas dans [KeyboardSettings] : il ne concerne pas le moteur et se lit avant la création des
 * services (`AppLocale`).
 */
enum class UiLanguage(val tag: String?) {
    SYSTEM(null),
    FRENCH("fr"),
    ENGLISH("en"),
    ;

    companion object {
        /** Clé des préférences de la langue de l'interface. */
        const val KEY_UI_LANGUAGE = "ui_language"

        /** Lit la valeur enregistrée ; toute valeur absente ou inconnue donne [SYSTEM]. */
        fun fromStored(value: String?): UiLanguage = entries.firstOrNull { storedName(it) == value } ?: SYSTEM

        /**
         * Langue correspondant à une étiquette BCP 47 (`fr-CA` → [FRENCH], `en` → [ENGLISH]) ; absente, vide,
         * illisible ou d'une autre langue : [SYSTEM].
         */
        fun fromTag(languageTag: String?): UiLanguage {
            if (languageTag.isNullOrBlank()) return SYSTEM
            val language = Locale.forLanguageTag(languageTag.trim()).language
            return entries.firstOrNull { it.tag != null && it.tag == language } ?: SYSTEM
        }
    }
}
