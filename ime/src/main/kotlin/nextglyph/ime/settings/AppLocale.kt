package nextglyph.ime.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import nextglyph.ime.AppServices
import java.util.Locale

/**
 * Langue de l'interface propre à l'application (§14).
 *
 * - **Android 13 et plus** : `LocaleManager.applicationLocales`, la langue par application du système (elle
 *   apparaît aussi dans les réglages Android et recrée les activités d'elle-même).
 * - **En dessous** : la préférence [UiLanguage.KEY_UI_LANGUAGE] et l'enrobage du contexte par [wrap]
 *   (`attachBaseContext` de l'activité et du service) ; l'appelant recrée l'activité.
 */
object AppLocale {

    /** Langue de l'interface en vigueur. */
    fun current(context: Context): UiLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return UiLanguage.fromTag(if (locales.isEmpty()) null else locales.get(0).toLanguageTag())
        }
        return stored(context)
    }

    /** Applique [language] : enregistrée dans tous les cas, imposée au système dès Android 13. */
    fun apply(context: Context, language: UiLanguage) {
        preferences(context).edit().putString(UiLanguage.KEY_UI_LANGUAGE, KeyboardSettings.storedName(language)).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        }
    }

    /**
     * Contexte de ressources dans la langue choisie, pour `attachBaseContext`. Dès Android 13 le système s'en
     * charge : [base] est rendu tel quel, comme sans langue choisie.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = stored(base).tag ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(configuration)
    }

    private fun stored(context: Context): UiLanguage =
        UiLanguage.fromStored(preferences(context).getString(UiLanguage.KEY_UI_LANGUAGE, null))

    // Lecture directe : `wrap` s'exécute dans `attachBaseContext`, avant que `AppServices` puisse exister.
    private fun preferences(context: Context) = context.getSharedPreferences(AppServices.PREFERENCES, Context.MODE_PRIVATE)
}
