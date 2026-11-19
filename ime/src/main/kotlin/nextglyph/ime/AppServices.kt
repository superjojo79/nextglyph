package nextglyph.ime

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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
        private const val CONTACTS_DEBOUNCE_MS = 2000L

        @Volatile
        private var instance: AppServices? = null

        fun get(context: Context): AppServices =
            instance ?: synchronized(this) { instance ?: AppServices(context).also { instance = it } }
    }
}
