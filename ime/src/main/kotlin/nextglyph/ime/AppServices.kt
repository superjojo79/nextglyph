package nextglyph.ime

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import nextglyph.engine.pack.PackStore
import nextglyph.engine.stats.KeyHeatmap
import nextglyph.engine.stats.TypingStats
import nextglyph.ime.contacts.ContactsRepository
import nextglyph.ime.contacts.ContactsSource
import nextglyph.ime.learn.LearnedStore
import nextglyph.ime.settings.KeyboardSettings
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Objets partagés par le service et l'activité de réglages, qui vivent dans le même processus : réglages
 * (`SharedPreferences`, §14), état appris ([LearnedStore], §10.5) et contacts ([ContactsRepository], §6).
 * Tout est utilisé depuis le fil de l'interface ; les entrées-sorties passent par un fil unique.
 */
class AppServices private constructor(context: Context) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val main = Executor { handler.post(it) }
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nextglyph-io").apply { isDaemon = true } }

    /** Réglages de l'utilisateur. Exclus des sauvegardes (`data_extraction_rules.xml`). */
    val preferences: SharedPreferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** État appris, dans le stockage privé non sauvegardé. */
    val learned = LearnedStore(File(app.noBackupFilesDir, LEARNED_FILE), io, main)

    /**
     * Paquet de dictionnaires installé (issue #62, §10.7), dans le stockage privé non sauvegardé. Les lectures et
     * installations sont bloquantes : jamais sur le fil principal.
     */
    val packs = PackStore(File(app.noBackupFilesDir, PACKS_DIRECTORY))

    /** Noms des contacts (en mémoire seulement). */
    val contacts = ContactsRepository({ ContactsSource.read(app.contentResolver) }, io, main)

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reloadContacts)
            handler.postDelayed(reloadContacts, CONTACTS_DEBOUNCE_MS)
        }
    }
    private val reloadContacts = Runnable { contacts.reload() }
    private var observing = false

    /** Réglages courants. */
    /**
     * Mesures locales de saisie (issue #36) : partagées par le clavier (qui les alimente) et les réglages
     * (qui les affichent et les remettent à zéro). Fichier de préférences à part, pour que leur
     * enregistrement ne réveille pas l'écouteur des réglages.
     */
    private val statsPreferences: SharedPreferences = app.getSharedPreferences(STATS_PREFERENCES, Context.MODE_PRIVATE)
    val stats = TypingStats({ SystemClock.uptimeMillis() }, TypingStats.Counters.parse(statsPreferences.getString(KEY_STATS, null)))

    /** Enregistre les mesures si elles ont changé. */
    fun saveStats() {
        if (!stats.changed) return
        statsPreferences.edit().putString(KEY_STATS, stats.counters.serialize()).apply()
        stats.markSaved()
    }

    /**
     * Carte de frappe (issue #46) : compteurs par touche (frappes, erreurs, densité), jamais de texte. Même fichier
     * de préférences que les mesures, exclu des sauvegardes.
     */
    val heatmap: KeyHeatmap = KeyHeatmap.parse(statsPreferences.getString(KEY_HEATMAP, null))

    /** Enregistre la carte de frappe si elle a changé. */
    fun saveHeatmap() {
        if (!heatmap.changed) return
        statsPreferences.edit().putString(KEY_HEATMAP, heatmap.serialize()).apply()
        heatmap.markSaved()
    }

    /**
     * À appeler après une installation ou un retour à l'origine d'un paquet : incrémente
     * [KeyboardSettings.KEY_DICTIONARIES_GENERATION], que le service écoute pour recharger ses dictionnaires.
     * Utilisable depuis n'importe quel fil.
     */
    fun notifyDictionariesChanged() {
        synchronized(this) {
            val generation = preferences.getInt(KeyboardSettings.KEY_DICTIONARIES_GENERATION, 0)
            preferences.edit().putInt(KeyboardSettings.KEY_DICTIONARIES_GENERATION, generation + 1).apply()
        }
    }

    val settings: KeyboardSettings get() = KeyboardSettings.read { preferences.all[it] }

    /**
     * Applique le réglage et la permission des contacts (§6.1) : lecture et suivi des modifications du carnet
     * si les deux sont réunis, effacement immédiat sinon.
     */
    fun syncContacts() {
        val active = settings.contacts && ContactsSource.isPermitted(app)
        if (active && !observing) {
            app.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
        } else if (!active && observing) {
            app.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        contacts.setEnabled(active)
    }

    companion object {
        const val PREFERENCES = "nextglyph_settings"
        const val LEARNED_FILE = "learned.ngls"
        const val PACKS_DIRECTORY = "packs"
        const val STATS_PREFERENCES = "nextglyph_stats"
        private const val KEY_STATS = "typing_stats"
        private const val KEY_HEATMAP = "key_heatmap"
        private const val CONTACTS_DEBOUNCE_MS = 2000L

        @Volatile
        private var instance: AppServices? = null

        fun get(context: Context): AppServices =
            instance ?: synchronized(this) { instance ?: AppServices(context).also { instance = it } }
    }
}
