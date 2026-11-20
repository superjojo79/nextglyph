package nextglyph.ime.settings

import nextglyph.engine.adapt.AdaptationMode
import nextglyph.engine.edit.EditConfig
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.predict.Prudence

/** Intensité du retour haptique (§14). */
enum class HapticLevel {
    OFF,
    LIGHT,
    MEDIUM,
    STRONG,
}

/**
 * Réglages de l'utilisateur (spec §14), lus de façon tolérante : une valeur absente, d'un autre type ou
 * inconnue prend la valeur par défaut. Les valeurs « selon la locale » sont `null` ([layout],
 * [preferredLanguage]) et résolues par l'IME.
 *
 * Le réglage « Variante anglaise » n'existe pas : un seul lexique anglais est embarqué (§10.2), le
 * réglage serait sans effet. « Aperçu agrandi des touches » non plus : le clavier n'a pas d'aperçu.
 *
 * @property languages langues actives, dans l'ordre (le français d'abord quand les deux sont actives).
 * @property rowHeightDp hauteur des rangées, de [MIN_ROW_HEIGHT_DP] à [MAX_ROW_HEIGHT_DP] (§13).
 * @property contacts réglage « Noms des contacts » : effectif seulement avec la permission (§6.1).
 * @property bottomMarginDp marge ajoutée sous la dernière rangée, en plus des encarts système, de 0 à
 *   [MAX_BOTTOM_MARGIN_DP] dp (§13, §14) : remonte le clavier au-dessus des boutons système.
 * @property wideSpace réglage « Espace large » (§5.1, §14) : effectif sur les écrans d'au moins
 *   `KeyboardLayouts.WIDE_SPACE_MIN_SCREEN_DP` dp.
 */
data class KeyboardSettings(
    val layout: LayoutKind? = null,
    val languages: List<String> = LexiconLanguage.SUPPORTED,
    val preferredLanguage: String? = null,
    val adaptationMode: AdaptationMode = AdaptationMode.CALM_REINFORCED,
    val rowHeightDp: Int = DEFAULT_ROW_HEIGHT_DP,
    val autocommit: Boolean = true,
    val prudence: Prudence = Prudence.NORMAL,
    val frenchTypography: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val contacts: Boolean = false,
    val haptics: HapticLevel = HapticLevel.MEDIUM,
    val bottomMarginDp: Int = 0,
    val wideSpace: Boolean = false,
) {
    init {
        require(rowHeightDp in MIN_ROW_HEIGHT_DP..MAX_ROW_HEIGHT_DP) { "Hauteur de rangée hors de $MIN_ROW_HEIGHT_DP..$MAX_ROW_HEIGHT_DP : $rowHeightDp." }
        require(bottomMarginDp in 0..MAX_BOTTOM_MARGIN_DP) { "Marge basse hors de 0..$MAX_BOTTOM_MARGIN_DP : $bottomMarginDp." }
        require(languages.isNotEmpty() && languages.all { it in LexiconLanguage.SUPPORTED }) { "Langues invalides : $languages." }
        require(preferredLanguage == null || preferredLanguage in LexiconLanguage.SUPPORTED) { "Langue préférée invalide : $preferredLanguage." }
    }

    /** Disposition effective : celle choisie, sinon celle de la langue système [systemLanguage]. */
    fun layoutFor(systemLanguage: String?): LayoutKind = layout ?: LayoutKind.defaultForLanguage(systemLanguage)

    /**
     * Langue préférée de départ : celle choisie si elle est active, sinon celle des [localeTags] (sous-type, locale
     * système) si elle est active, sinon la première langue active.
     */
    fun preferredFor(vararg localeTags: String?): String {
        val chosen = preferredLanguage ?: LexiconLanguage.choose(*localeTags)
        return if (chosen in languages) chosen else languages[0]
    }

    /** Réglages de l'édition (§5.6). */
    val editConfig: EditConfig get() = EditConfig(frenchTypography = frenchTypography, doubleSpacePeriod = doubleSpacePeriod)

    companion object {
        const val MIN_ROW_HEIGHT_DP: Int = 34
        const val MAX_ROW_HEIGHT_DP: Int = 46
        const val DEFAULT_ROW_HEIGHT_DP: Int = 38
        const val MAX_BOTTOM_MARGIN_DP: Int = 48

        const val KEY_LAYOUT = "layout"
        const val KEY_LANGUAGES = "languages"
        const val KEY_PREFERRED_LANGUAGE = "preferred_language"
        const val KEY_ADAPTATION = "adaptation_mode"
        const val KEY_ROW_HEIGHT = "row_height_dp"
        const val KEY_BOTTOM_MARGIN = "bottom_margin_dp"
        const val KEY_WIDE_SPACE = "wide_space"
        const val KEY_AUTOCOMMIT = "autocommit"
        const val KEY_PRUDENCE = "prudence"
        const val KEY_FRENCH_TYPOGRAPHY = "french_typography"
        const val KEY_DOUBLE_SPACE = "double_space_period"
        const val KEY_CONTACTS = "contacts"
        const val KEY_HAPTICS = "haptics"

        /** Valeur « selon la locale » de [KEY_LAYOUT] et [KEY_PREFERRED_LANGUAGE]. */
        const val AUTO = "auto"

        /** Valeur de [KEY_LANGUAGES] pour les deux langues. */
        const val BOTH = "both"

        /** Tous les réglages, dans l'ordre de l'écran. */
        val KEYS: List<String> = listOf(
            KEY_LAYOUT, KEY_LANGUAGES, KEY_PREFERRED_LANGUAGE, KEY_ADAPTATION, KEY_ROW_HEIGHT, KEY_BOTTOM_MARGIN, KEY_WIDE_SPACE, KEY_AUTOCOMMIT,
            KEY_PRUDENCE, KEY_FRENCH_TYPOGRAPHY, KEY_DOUBLE_SPACE, KEY_CONTACTS, KEY_HAPTICS,
        )

        /**
         * Lit les réglages depuis [values] (valeur stockée sous chaque clé, `null` si absente). Ne lève jamais
         * d'exception : toute valeur invalide prend la valeur par défaut.
         */
        fun read(values: (String) -> Any?): KeyboardSettings {
            val defaults = KeyboardSettings()
            fun string(key: String): String? = values(key) as? String
            fun boolean(key: String, default: Boolean): Boolean = values(key) as? Boolean ?: default
            val languages = when (string(KEY_LANGUAGES)) {
                LexiconLanguage.FRENCH -> listOf(LexiconLanguage.FRENCH)
                LexiconLanguage.ENGLISH -> listOf(LexiconLanguage.ENGLISH)
                else -> defaults.languages
            }
            return KeyboardSettings(
                layout = enumOrNull<LayoutKind>(string(KEY_LAYOUT)),
                languages = languages,
                preferredLanguage = string(KEY_PREFERRED_LANGUAGE)?.takeIf { it in LexiconLanguage.SUPPORTED },
                adaptationMode = enumOrNull<AdaptationMode>(string(KEY_ADAPTATION)) ?: defaults.adaptationMode,
                rowHeightDp = (values(KEY_ROW_HEIGHT) as? Int)?.takeIf { it in MIN_ROW_HEIGHT_DP..MAX_ROW_HEIGHT_DP } ?: defaults.rowHeightDp,
                autocommit = boolean(KEY_AUTOCOMMIT, defaults.autocommit),
                prudence = enumOrNull<Prudence>(string(KEY_PRUDENCE)) ?: defaults.prudence,
                frenchTypography = boolean(KEY_FRENCH_TYPOGRAPHY, defaults.frenchTypography),
                doubleSpacePeriod = boolean(KEY_DOUBLE_SPACE, defaults.doubleSpacePeriod),
                contacts = boolean(KEY_CONTACTS, defaults.contacts),
                haptics = enumOrNull<HapticLevel>(string(KEY_HAPTICS)) ?: defaults.haptics,
                bottomMarginDp = (values(KEY_BOTTOM_MARGIN) as? Int)?.takeIf { it in 0..MAX_BOTTOM_MARGIN_DP } ?: defaults.bottomMarginDp,
                wideSpace = boolean(KEY_WIDE_SPACE, defaults.wideSpace),
            )
        }

        /** Valeur stockée d'une constante d'énumération : son nom en minuscules (`calm_reinforced`). */
        fun storedName(value: Enum<*>): String = value.name.lowercase()

        private inline fun <reified E : Enum<E>> enumOrNull(stored: String?): E? =
            enumValues<E>().firstOrNull { storedName(it) == stored }
    }
}
