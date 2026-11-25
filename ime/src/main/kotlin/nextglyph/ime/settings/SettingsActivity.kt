package nextglyph.ime.settings

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import nextglyph.engine.adapt.AdaptationMode
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.learn.ImportReport
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.pack.PackException
import nextglyph.engine.pack.PackManifest
import nextglyph.engine.predict.PredictionConfig
import nextglyph.engine.predict.Prudence
import nextglyph.ime.AppServices
import nextglyph.ime.R
import nextglyph.ime.contacts.ContactsSource
import nextglyph.ime.dictionary.DictionaryDownloader
import nextglyph.ime.dictionary.DictionaryMessages
import nextglyph.ime.dictionary.DictionaryUrls
import nextglyph.ime.dictionary.Distribution
import nextglyph.ime.settings.KeyboardSettings.Companion.AUTO
import nextglyph.ime.settings.KeyboardSettings.Companion.BOTH
import nextglyph.ime.settings.KeyboardSettings.Companion.storedName
import java.io.InputStream
import java.text.NumberFormat

/**
 * Réglages (spec §14) : activation du clavier, puis tous les réglages de l'utilisateur, écrits dans les
 * `SharedPreferences` partagées avec le service, qui les applique aussitôt. Vues classiques du cadre Android,
 * sans dépendance.
 *
 * - **Contacts** (§6.1) : la permission `READ_CONTACTS` n'est demandée qu'ici, après une explication, quand
 *   l'utilisateur active la fonction ; la couper efface immédiatement les noms de la mémoire du clavier.
 * - **Données apprises** (§10.5, §12) : export et import du dictionnaire personnel en TSV par le Storage Access
 *   Framework (aucune permission), effacement de tout ce qui a été appris, après confirmation.
 * - **Dictionnaires** (§10.7, issue #62) : paquet signé téléchargé dans le navigateur puis importé par le Storage
 *   Access Framework, retour aux dictionnaires d'origine ; dans la variante `play` seulement, recherche d'une mise à
 *   jour à la demande, après l'opt-in « Mises à jour en ligne ». Installations et recherches sur un fil d'arrière-plan.
 */
class SettingsActivity : Activity() {

    private lateinit var services: AppServices
    private lateinit var status: TextView
    private lateinit var learnedSummary: TextView
    private lateinit var refusalsSummary: TextView
    private lateinit var zonesSummary: TextView
    private lateinit var statsSummary: TextView
    private lateinit var contactsSwitch: Switch
    private lateinit var dictionariesStatus: TextView
    private lateinit var dictionariesReset: Button
    private var dictionariesCheck: Button? = null
    private var dictionariesBusy = false
    private val preferences: SharedPreferences get() = services.preferences

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        services = AppServices.get(this)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.settings_status)

        // Bord à bord imposé dès Android 15 : on laisse la place aux barres système (API 30+ pour les insets typés).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findViewById<View>(R.id.settings_root).setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        findViewById<View>(R.id.settings_open).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.settings_choose).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        buildPreferences(findViewById(R.id.settings_preferences))
        services.learned.load { updateLearnedSummary() }
    }

    override fun onResume() {
        super.onResume()
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        status.setText(
            if (KeyboardActivation.isEnabled(enabled, packageName)) {
                R.string.settings_status_enabled
            } else {
                R.string.settings_status_disabled
            },
        )
        // Permission retirée depuis les réglages du système : la fonction est coupée.
        if (preferences.getBoolean(KeyboardSettings.KEY_CONTACTS, false) && !ContactsSource.isPermitted(this)) setContacts(false)
        contactsSwitch.isChecked = preferences.getBoolean(KeyboardSettings.KEY_CONTACTS, false)
        services.syncContacts()
        updateLearnedSummary()
        updateStatsSummary()
        refreshDictionaries()
    }

    /** Mesures locales de saisie (issue #36) : vitesse et taux, « — » tant qu'il n'y a rien à mesurer. */
    private fun updateStatsSummary() {
        if (!::statsSummary.isInitialized) return
        val c = services.stats.counters
        if (c.keys == 0L) {
            statsSummary.setText(R.string.pref_stats_empty)
            return
        }
        val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
        val integers = NumberFormat.getIntegerInstance()
        fun rate(value: Double?) = value?.let(percent::format) ?: DASH
        statsSummary.text = listOf(
            getString(R.string.pref_stats_speed, c.wordsPerMinute?.let { integers.format(it) } ?: DASH),
            getString(R.string.pref_stats_corrections, rate(c.correctionRate)),
            getString(R.string.pref_stats_acceptance, rate(c.acceptanceRate)),
            getString(R.string.pref_stats_undo, rate(c.undoRate)),
            getString(
                R.string.pref_stats_auto_corrections,
                integers.format(c.correctionsTotal - c.correctionsUndoneTotal),
                integers.format(c.correctionsUndoneTotal),
                rate(c.correctionUndoRate),
            ),
            getString(R.string.pref_stats_restored, integers.format(c.restored), integers.format(c.restoredUndone), rate(c.restoredUndoRate)),
            getString(
                R.string.pref_stats_by_prudence,
                rate(c.correctionUndoRate(Prudence.CAUTIOUS)),
                rate(c.correctionUndoRate(Prudence.NORMAL)),
                rate(c.correctionUndoRate(Prudence.BOLD)),
            ),
            getString(R.string.pref_stats_privacy),
        ).joinToString("\n")
    }

    private fun buildPreferences(root: LinearLayout) {
        val current = services.settings

        section(root, R.string.pref_section_interface)
        choice(
            root, R.string.pref_ui_language, R.string.pref_ui_language_summary, R.string.pref_ui_language_info,
            null, storedName(AppLocale.current(this)),
            listOf(
                storedName(UiLanguage.SYSTEM) to R.string.pref_ui_language_system,
                storedName(UiLanguage.FRENCH) to R.string.language_name_fr,
                storedName(UiLanguage.ENGLISH) to R.string.language_name_en,
            ),
        ) { value ->
            AppLocale.apply(this, UiLanguage.fromStored(value))
            // Dès Android 13, le système recrée l'activité lui-même ; en dessous, c'est à nous.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate()
        }

        section(root, R.string.pref_section_keyboard)
        choice(
            root, R.string.pref_layout, R.string.pref_layout_summary, R.string.pref_layout_info,
            KeyboardSettings.KEY_LAYOUT, current.layout?.let(::storedName) ?: AUTO,
            listOf(AUTO to R.string.pref_layout_auto, storedName(LayoutKind.AZERTY) to R.string.pref_layout_azerty, storedName(LayoutKind.QWERTY) to R.string.pref_layout_qwerty),
        )
        choice(
            root, R.string.pref_adaptation, R.string.pref_adaptation_summary, R.string.pref_adaptation_info,
            KeyboardSettings.KEY_ADAPTATION, storedName(current.adaptationMode),
            listOf(
                storedName(AdaptationMode.OFF) to R.string.pref_adaptation_off,
                storedName(AdaptationMode.CALM) to R.string.pref_adaptation_calm,
                storedName(AdaptationMode.CALM_REINFORCED) to R.string.pref_adaptation_calm_reinforced,
                storedName(AdaptationMode.ELASTIC) to R.string.pref_adaptation_elastic,
            ),
        )
        slider(
            root, R.string.pref_row_height, R.string.pref_row_height_summary, R.string.pref_row_height_info, KeyboardSettings.KEY_ROW_HEIGHT,
            KeyboardSettings.MIN_ROW_HEIGHT_DP..KeyboardSettings.MAX_ROW_HEIGHT_DP, current.rowHeightDp,
        )
        slider(
            root, R.string.pref_bottom_margin, R.string.pref_bottom_margin_summary, R.string.pref_bottom_margin_info, KeyboardSettings.KEY_BOTTOM_MARGIN,
            0..KeyboardSettings.MAX_BOTTOM_MARGIN_DP, current.bottomMarginDp,
        )
        toggle(
            root, R.string.pref_wide_space, R.string.pref_wide_space_summary, R.string.pref_wide_space_info,
            KeyboardSettings.KEY_WIDE_SPACE, current.wideSpace,
        )
        choice(
            root, R.string.pref_haptics, R.string.pref_haptics_summary, R.string.pref_haptics_info,
            KeyboardSettings.KEY_HAPTICS, storedName(current.haptics),
            listOf(
                storedName(HapticLevel.OFF) to R.string.pref_haptics_off,
                storedName(HapticLevel.LIGHT) to R.string.pref_haptics_light,
                storedName(HapticLevel.MEDIUM) to R.string.pref_haptics_medium,
                storedName(HapticLevel.STRONG) to R.string.pref_haptics_strong,
            ),
        )

        section(root, R.string.pref_section_languages)
        val languages = if (current.languages.size == 2) BOTH else current.languages[0]
        choice(
            root, R.string.pref_languages, R.string.pref_languages_summary, R.string.pref_languages_info,
            KeyboardSettings.KEY_LANGUAGES, languages,
            listOf(BOTH to R.string.pref_languages_both, LexiconLanguage.FRENCH to R.string.pref_language_fr, LexiconLanguage.ENGLISH to R.string.pref_language_en),
        )
        choice(
            root, R.string.pref_preferred_language, R.string.pref_preferred_language_summary, R.string.pref_preferred_language_info,
            KeyboardSettings.KEY_PREFERRED_LANGUAGE, current.preferredLanguage ?: AUTO,
            listOf(AUTO to R.string.pref_preferred_auto, LexiconLanguage.FRENCH to R.string.pref_language_fr, LexiconLanguage.ENGLISH to R.string.pref_language_en),
        )

        section(root, R.string.pref_section_dictionaries, R.string.pref_section_dictionaries_summary, R.string.pref_section_dictionaries_info)
        dictionariesStatus = text(root, getString(R.string.dictionaries_status_original))
        button(root, R.string.dictionaries_browser) { openPackInBrowser() }
        button(root, R.string.dictionaries_import) { startPackImport() }
        dictionariesReset = button(root, R.string.dictionaries_reset) { confirmResetDictionaries() }.also { it.visibility = View.GONE }
        if (Distribution.downloader != null) {
            val online = preferences.getBoolean(KeyboardSettings.KEY_ONLINE_UPDATES, false)
            toggle(
                root, R.string.pref_dictionaries_online, R.string.pref_dictionaries_online_summary, R.string.pref_dictionaries_online_info,
                KeyboardSettings.KEY_ONLINE_UPDATES, online,
            ) { checked -> dictionariesCheck?.visibility = if (checked) View.VISIBLE else View.GONE }
            dictionariesCheck = button(root, R.string.dictionaries_check) { checkForUpdate() }
                .also { it.visibility = if (online) View.VISIBLE else View.GONE }
        }

        section(root, R.string.pref_section_typing)
        toggle(
            root, R.string.pref_autocommit, R.string.pref_autocommit_summary, R.string.pref_autocommit_info,
            KeyboardSettings.KEY_AUTOCOMMIT, current.autocommit,
        )
        toggle(
            root, R.string.pref_next_word, R.string.pref_next_word_summary, R.string.pref_next_word_info,
            KeyboardSettings.KEY_NEXT_WORD, current.nextWord,
        )
        choice(
            root, R.string.pref_prudence, R.string.pref_prudence_summary, R.string.pref_prudence_info,
            KeyboardSettings.KEY_PRUDENCE, storedName(current.prudence),
            listOf(
                storedName(Prudence.CAUTIOUS) to R.string.pref_prudence_cautious,
                storedName(Prudence.NORMAL) to R.string.pref_prudence_normal,
                storedName(Prudence.BOLD) to R.string.pref_prudence_bold,
            ),
        )
        toggle(
            root, R.string.pref_french_typography, R.string.pref_french_typography_summary, R.string.pref_french_typography_info,
            KeyboardSettings.KEY_FRENCH_TYPOGRAPHY, current.frenchTypography,
        )
        toggle(
            root, R.string.pref_double_space, R.string.pref_double_space_summary, R.string.pref_double_space_info,
            KeyboardSettings.KEY_DOUBLE_SPACE, current.doubleSpacePeriod,
        )

        section(root, R.string.pref_section_contacts)
        contactsSwitch = toggle(
            root, R.string.pref_contacts, R.string.pref_contacts_summary, R.string.pref_contacts_info, null, current.contacts,
        ) { checked ->
            if (checked) enableContacts() else setContacts(false)
        }

        section(root, R.string.pref_section_learned, R.string.pref_section_learned_summary, R.string.pref_section_learned_info)
        learnedSummary = text(root, "")
        button(root, R.string.pref_export) { startExport() }
        button(root, R.string.pref_import) { startImport() }
        label(root, R.string.pref_refusals, R.string.pref_refusals_summary, R.string.pref_refusals_info)
        refusalsSummary = text(root, "")
        button(root, R.string.pref_refusals_open) { showRefusals() }
        button(root, R.string.pref_clear) { confirmClear() }
        label(root, R.string.pref_touch_zones, R.string.pref_touch_zones_summary, R.string.pref_touch_zones_info)
        zonesSummary = text(root, "")
        button(root, R.string.pref_heatmap_open) { startActivity(Intent(this, HeatmapActivity::class.java)) }

        section(root, R.string.pref_section_stats, R.string.pref_section_stats_summary, R.string.pref_section_stats_info)
        statsSummary = text(root, "")
        button(root, R.string.pref_stats_reset) {
            services.stats.reset()
            services.saveStats()
            // Les erreurs de la carte de frappe font partie des mesures ; la densité suit les données apprises.
            services.heatmap.resetErrors()
            services.saveHeatmap()
            updateStatsSummary()
        }

        section(root, R.string.pref_section_gestures)
        text(root, getString(R.string.pref_gestures_help))
    }

    /** Active les contacts : explication, puis demande de la permission si elle manque (§6.1). */
    private fun enableContacts() {
        if (ContactsSource.isPermitted(this)) {
            setContacts(true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_contacts)
            .setMessage(R.string.contacts_rationale)
            .setPositiveButton(R.string.contacts_continue) { _, _ -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> setContacts(false) }
            .setOnCancelListener { setContacts(false) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CONTACTS) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        setContacts(granted)
        if (!granted) Toast.makeText(this, R.string.contacts_denied, Toast.LENGTH_LONG).show()
    }

    private fun setContacts(enabled: Boolean) {
        preferences.edit().putBoolean(KeyboardSettings.KEY_CONTACTS, enabled).apply()
        if (::contactsSwitch.isInitialized && contactsSwitch.isChecked != enabled) contactsSwitch.isChecked = enabled
        services.syncContacts()
    }

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(TSV_TYPE)
            .putExtra(Intent.EXTRA_TITLE, EXPORT_NAME)
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    // Dictionnaires (§10.7).

    /** Met à jour la ligne d'état et le bouton de retour à l'origine d'après le paquet installé (lu hors du fil principal). */
    private fun refreshDictionaries() {
        if (!::dictionariesStatus.isInitialized) return
        Thread({
            val version = services.packs.current()?.version
            runOnUiThread { if (!isDestroyed) showDictionaries(version) }
        }, DICTIONARIES_THREAD).start()
    }

    private fun showDictionaries(version: Long?) {
        dictionariesStatus.text = if (version == null) {
            getString(R.string.dictionaries_status_original)
        } else {
            getString(R.string.dictionaries_status_updated, DictionaryMessages.formatVersion(version, resources.configuration.locales[0]))
        }
        dictionariesReset.visibility = if (version == null) View.GONE else View.VISIBLE
    }

    /**
     * Exécute [work] sur un fil d'arrière-plan (une seule opération à la fois), puis [done] sur le fil principal si
     * l'écran existe encore. L'état d'avancement [progress] remplace la ligne d'état en attendant.
     */
    private fun <T> inBackground(progress: Int, work: () -> T, done: (Result<T>) -> Unit) {
        if (dictionariesBusy) return
        dictionariesBusy = true
        dictionariesStatus.setText(progress)
        Thread({
            val outcome: Result<T> = try {
                Result.success(work())
            } catch (error: Exception) {
                Result.failure(error)
            }
            runOnUiThread {
                dictionariesBusy = false
                if (!isDestroyed) done(outcome)
            }
        }, DICTIONARIES_THREAD).start()
    }

    /** Installe le paquet lu dans [open] ; les lexiques du clavier sont rechargés ensuite (compteur de génération). */
    private fun installPack(open: () -> InputStream) {
        open().use { services.packs.install(it) }
        services.notifyDictionariesChanged()
    }

    private fun showInstallResult(outcome: Result<*>) {
        val message = outcome.exceptionOrNull()?.let(DictionaryMessages::forError) ?: R.string.dictionaries_installed
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshDictionaries()
    }

    private fun openPackInBrowser() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DictionaryUrls.PACK)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.dictionaries_browser_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun startPackImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, REQUEST_PACK)
    }

    private fun importPack(uri: Uri) {
        inBackground(
            R.string.dictionaries_installing,
            {
                installPack {
                    val stream = try {
                        contentResolver.openInputStream(uri)
                    } catch (_: Exception) {
                        null
                    }
                    stream ?: throw PackException(PackException.Reason.IO, "Fichier illisible : impossible de l'ouvrir.")
                }
            },
            ::showInstallResult,
        )
    }

    private fun confirmResetDictionaries() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionaries_reset)
            .setMessage(R.string.dictionaries_reset_confirm)
            .setPositiveButton(R.string.dictionaries_reset_action) { _, _ ->
                inBackground(
                    R.string.dictionaries_installing,
                    {
                        services.packs.reset()
                        services.notifyDictionariesChanged()
                    },
                ) { outcome ->
                    val message = outcome.exceptionOrNull()?.let(DictionaryMessages::forError) ?: R.string.dictionaries_reset_done
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    refreshDictionaries()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Variante `play` : lit le manifeste publié, vérifié par le magasin de paquets ; propose le téléchargement s'il est plus récent. */
    private fun checkForUpdate() {
        val downloader = Distribution.downloader ?: return
        inBackground(
            R.string.dictionaries_checking,
            {
                val (manifest, signature) = downloader.fetchManifest()
                services.packs.checkUpdate(manifest, signature)
            },
        ) { outcome ->
            refreshDictionaries()
            val error = outcome.exceptionOrNull()
            val newer = outcome.getOrNull()
            when {
                error != null -> Toast.makeText(this, DictionaryMessages.forError(error), Toast.LENGTH_LONG).show()
                newer == null -> Toast.makeText(this, R.string.dictionaries_up_to_date, Toast.LENGTH_LONG).show()
                else -> confirmDownload(downloader, newer)
            }
        }
    }

    private fun confirmDownload(downloader: DictionaryDownloader, manifest: PackManifest) {
        val date = DictionaryMessages.formatVersion(manifest.version, resources.configuration.locales[0])
        val megabytes = DictionaryMessages.megabytes(manifest.files.sumOf { it.size })
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionaries_update_title)
            .setMessage(getString(R.string.dictionaries_update_available, date, megabytes))
            .setPositiveButton(R.string.dictionaries_update_download) { _, _ ->
                inBackground(R.string.dictionaries_downloading, { installPack(downloader::openPack) }, ::showInstallResult)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(TSV_TYPE, "text/plain", "text/*", "application/octet-stream"))
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("API du cadre Android, seule disponible sans dépendance AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_EXPORT -> services.learned.exportTsv({ contentResolver.openOutputStream(uri, "wt") }) { ok ->
                Toast.makeText(this, if (ok) R.string.export_done else R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
            REQUEST_PACK -> importPack(uri)
            REQUEST_IMPORT -> services.learned.importTsv({ contentResolver.openInputStream(uri) }) { report ->
                Toast.makeText(this, importMessage(report), Toast.LENGTH_LONG).show()
                updateLearnedSummary()
            }
        }
    }

    private fun importMessage(report: ImportReport?): String = when {
        report == null || report.unreadable -> getString(R.string.import_failed)
        report.unsupportedVersion -> getString(R.string.import_unsupported)
        else -> resources.getQuantityString(R.plurals.import_done, report.imported, report.imported, report.skippedCount)
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_clear)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.clear_action) { _, _ ->
                services.learned.clear()
                services.heatmap.resetDensity()
                services.saveHeatmap()
                updateLearnedSummary()
                Toast.makeText(this, R.string.clear_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Corrections refusées (§10.5) : liste à cocher ; les cases cochées sont retirées, la correction peut revenir. */
    private fun showRefusals() {
        val refusals = services.learned.data?.rejections?.learned(REJECT_LIMIT).orEmpty()
        if (refusals.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_refusals)
                .setMessage(R.string.learned_refusals_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = refusals.map { getString(R.string.refusal_item, it.typed, it.inserted) }.toTypedArray()
        val checked = BooleanArray(refusals.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_refusals)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.refusal_forget) { _, _ ->
                for ((index, refusal) in refusals.withIndex()) {
                    if (checked[index]) services.learned.forgetRefusal(refusal.typed, refusal.inserted)
                }
                updateLearnedSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateLearnedSummary() {
        if (!::learnedSummary.isInitialized || !::refusalsSummary.isInitialized || !::zonesSummary.isInitialized) return
        val data = services.learned.data
        if (data == null) {
            learnedSummary.setText(R.string.learned_loading)
            refusalsSummary.setText(R.string.learned_loading)
            zonesSummary.setText(R.string.learned_loading)
            return
        }
        learnedSummary.text = resources.getQuantityString(R.plurals.learned_summary, data.personal.size, data.personal.size)
        val refusals = data.rejections.learned(REJECT_LIMIT).size
        refusalsSummary.text = if (refusals == 0) {
            getString(R.string.learned_refusals_none)
        } else {
            resources.getQuantityString(R.plurals.learned_refusals, refusals, refusals)
        }
        val taps = data.spatial.totalSamples
        zonesSummary.text = if (taps == 0) {
            getString(R.string.learned_zones_none)
        } else {
            resources.getQuantityString(R.plurals.learned_zones, taps, NumberFormat.getIntegerInstance().format(taps))
        }
    }

    // Construction des vues.

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Titre d'un réglage ou d'une section : [titleView] (poids 1) suivi, si [info] est donné, du bouton (i) qui ouvre
     * l'explication détaillée. [title] nomme le réglage dans la boîte de dialogue et pour TalkBack.
     */
    private fun titleRow(root: LinearLayout, titleView: View, title: Int, info: Int?, topPaddingDp: Int, bottomPaddingDp: Int = 0) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(topPaddingDp), 0, dp(bottomPaddingDp))
        }
        row.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (info != null) row.addView(infoButton(title, info), LinearLayout.LayoutParams(dp(MIN_TOUCH_DP), dp(MIN_TOUCH_DP)))
        root.addView(row)
    }

    /** Bouton (i) de 48 dp : explication détaillée du réglage [title] dans une boîte de dialogue. */
    private fun infoButton(title: Int, info: Int): ImageButton = ImageButton(this).apply {
        setImageResource(R.drawable.ic_info)
        scaleType = ImageView.ScaleType.CENTER
        val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attributes.getDrawable(0)
        attributes.recycle()
        contentDescription = getString(R.string.pref_info_description, getString(title))
        setOnClickListener { showInfo(title, info) }
    }

    private fun showInfo(title: Int, info: Int) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(info)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** En-tête de section ; avec [summary] et [info], une ligne de résumé et le bouton (i) comme pour un réglage. */
    private fun section(root: LinearLayout, title: Int, summary: Int? = null, info: Int? = null) {
        val heading = TextView(this).apply {
            setText(title)
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            isFocusable = false
            accessibilityHeading()
        }
        titleRow(root, heading, title, info, topPaddingDp = 24, bottomPaddingDp = 4)
        if (summary != null) text(root, getString(summary))
    }

    private fun TextView.accessibilityHeading() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
    }

    private fun text(root: LinearLayout, value: String): TextView = TextView(this).apply {
        text = value
        setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        setPadding(0, dp(4), 0, dp(4))
    }.also(root::addView)

    /** Titre d'un réglage à choix ou à curseur, avec son bouton (i) puis sa ligne de résumé. */
    private fun label(root: LinearLayout, title: Int, summary: Int, info: Int) {
        val titleView = TextView(this).apply {
            setText(title)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        }
        titleRow(root, titleView, title, info, topPaddingDp = 12)
        text(root, getString(summary))
    }

    /**
     * Choix exclusif. La valeur choisie est enregistrée sous [key] si elle est donnée ; [onChange] la reçoit ensuite
     * (langue de l'interface, qui s'enregistre elle-même).
     */
    private fun choice(
        root: LinearLayout,
        title: Int,
        summary: Int,
        info: Int,
        key: String?,
        selected: String,
        options: List<Pair<String, Int>>,
        onChange: ((String) -> Unit)? = null,
    ) {
        label(root, title, summary, info)
        val group = RadioGroup(this)
        for ((value, text) in options) {
            group.addView(
                RadioButton(this).apply {
                    id = View.generateViewId()
                    setText(text)
                    tag = value
                    minHeight = dp(MIN_TOUCH_DP)
                    isChecked = value == selected
                },
            )
        }
        group.setOnCheckedChangeListener { radio, id ->
            val value = radio.findViewById<View>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            if (key != null) preferences.edit().putString(key, value).apply()
            onChange?.invoke(value)
        }
        root.addView(group)
    }

    private fun toggle(
        root: LinearLayout,
        title: Int,
        summary: Int,
        info: Int,
        key: String?,
        checked: Boolean,
        onChange: ((Boolean) -> Unit)? = null,
    ): Switch {
        val switch = Switch(this).apply {
            setText(title)
            isChecked = checked
            minHeight = dp(MIN_TOUCH_DP)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        }
        switch.setOnCheckedChangeListener { _: CompoundButton, value: Boolean ->
            if (key != null) preferences.edit().putBoolean(key, value).apply()
            onChange?.invoke(value)
        }
        titleRow(root, switch, title, info, topPaddingDp = 12)
        text(root, getString(summary))
        return switch
    }

    /** Curseur d'un réglage en dp ([range]), enregistré sous [key] (hauteur des rangées, marge basse). */
    private fun slider(root: LinearLayout, title: Int, summary: Int, info: Int, key: String, range: IntRange, current: Int) {
        label(root, title, summary, info)
        val value = text(root, getString(R.string.pref_row_height_value, current))
        root.addView(
            SeekBar(this).apply {
                max = range.last - range.first
                progress = current - range.first
                minimumHeight = dp(MIN_TOUCH_DP)
                contentDescription = getString(title)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            val dp = progress + range.first
                            value.text = getString(R.string.pref_row_height_value, dp)
                            if (fromUser) preferences.edit().putInt(key, dp).apply()
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) = Unit

                        override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    },
                )
            },
        )
    }

    private fun button(root: LinearLayout, title: Int, action: () -> Unit): Button =
        Button(this).apply {
            setText(title)
            minHeight = dp(MIN_TOUCH_DP)
            setOnClickListener { action() }
        }.also(root::addView)

    private companion object {
        const val DASH = "—"

        /** Seuil d'un refus appris : celui du prédicteur (§5.5), pour lister exactement les corrections désactivées. */
        val REJECT_LIMIT = PredictionConfig().rejectLimit
        const val REQUEST_CONTACTS = 1
        const val REQUEST_EXPORT = 2
        const val REQUEST_IMPORT = 3
        const val REQUEST_PACK = 4
        const val DICTIONARIES_THREAD = "nextglyph-dictionaries"
        const val TSV_TYPE = "text/tab-separated-values"
        const val EXPORT_NAME = "nextglyph-dictionnaire.tsv"
        const val MIN_TOUCH_DP = 48
    }
}
