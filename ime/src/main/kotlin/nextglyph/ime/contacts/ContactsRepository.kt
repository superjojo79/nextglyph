package nextglyph.ime.contacts

import nextglyph.engine.contacts.ContactName
import java.util.concurrent.Executor

/**
 * Noms des contacts en mémoire (spec §6) : lus en arrière-plan par [reader] quand la fonction est active,
 * oubliés aussitôt quand elle est coupée. Rien n'est écrit sur le disque : le sous-lexique est reconstruit
 * en mémoire à chaque démarrage du clavier (et à chaque modification du carnet).
 *
 * [contacts] et les auditeurs ne sont utilisés que sur le fil de l'interface ([main]).
 *
 * @param reader lit le carnet d'adresses ; appelé sur [io], peut lever `SecurityException` (permission retirée).
 */
class ContactsRepository(
    private val reader: () -> List<ContactName>,
    private val io: Executor,
    private val main: Executor,
) {
    /** Prévenu sur le fil de l'interface quand la liste des contacts change. */
    fun interface Listener {
        fun onContactsChanged(contacts: List<ContactName>)
    }

    /** Contacts lus (vide si la fonction est coupée ou la permission absente). */
    var contacts: List<ContactName> = emptyList()
        private set

    /** Vrai si la fonction est active (réglage et permission). */
    var enabled: Boolean = false
        private set

    private val listeners = LinkedHashSet<Listener>()
    private var generation = 0

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Active ou coupe la fonction ; coupée, la liste est effacée immédiatement (§6.1). */
    fun setEnabled(enabled: Boolean) {
        if (enabled == this.enabled) return
        this.enabled = enabled
        if (enabled) {
            reload()
        } else {
            generation++
            update(emptyList())
        }
    }

    /** Relit le carnet en arrière-plan (fonction active seulement). Une lecture plus récente l'emporte. */
    fun reload() {
        if (!enabled) return
        val requested = ++generation
        io.execute {
            val read = try {
                reader()
            } catch (e: SecurityException) {
                emptyList()
            }
            main.execute { if (requested == generation && enabled) update(read) }
        }
    }

    private fun update(next: List<ContactName>) {
        if (next == contacts) return
        contacts = next
        for (listener in listeners.toList()) listener.onContactsChanged(next)
    }
}
