package nextglyph.ime.contacts

import nextglyph.engine.contacts.ContactName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/** Contacts en mémoire (§6) et regroupement des lignes de `ContactsContract.Data` (§6.2). */
class ContactsRepositoryTest {

    private val now = Executor { it.run() }
    private var book = listOf(ContactName(givenName = "Gwenaëlle", familyName = "Rose"))
    private var reads = 0
    private val repository = ContactsRepository({ reads++; book }, now, now)
    private val seen = mutableListOf<List<ContactName>>()

    init {
        repository.addListener { seen += it }
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `activé - le carnet est lu et les auditeurs prévenus`() {
            repository.setEnabled(true)

            assertThat(repository.enabled).isTrue()
            assertThat(repository.contacts).isEqualTo(book)
            assertThat(seen).containsExactly(book)
        }

        @Test
        fun `désactivé - les noms sont effacés aussitôt`() {
            repository.setEnabled(true)

            repository.setEnabled(false)

            assertThat(repository.contacts).isEmpty()
            assertThat(seen.last()).isEmpty()
        }

        @Test
        fun `rechargement après modification du carnet`() {
            repository.setEnabled(true)
            book = book + ContactName(givenName = "Ana")

            repository.reload()

            assertThat(repository.contacts).hasSize(2)
        }

        @Test
        fun `lignes regroupées par contact`() {
            val contacts = ContactRows.group(
                sequenceOf(
                    ContactRow(1, givenName = " Hélène ", familyName = "Dupont"),
                    ContactRow(2, givenName = "Ana"),
                    ContactRow(1, nickname = "Lena"),
                    ContactRow(1, givenName = "Autre", middleName = "Marie"),
                ),
            )

            assertThat(contacts).containsExactly(
                ContactName(givenName = "Hélène", familyName = "Dupont", middleName = "Marie", nickname = "Lena"),
                ContactName(givenName = "Ana"),
            )
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `permission retirée pendant la lecture - liste vide`() {
            val denied = ContactsRepository({ throw SecurityException() }, now, now)

            denied.setEnabled(true)

            assertThat(denied.contacts).isEmpty()
        }

        @Test
        fun `désactivé - reload ne lit rien`() {
            repository.reload()

            assertThat(reads).isZero()
            assertThat(seen).isEmpty()
        }

        @Test
        fun `contacts sans nom ignorés`() {
            assertThat(ContactRows.group(sequenceOf(ContactRow(1), ContactRow(2, givenName = "  ")))).isEmpty()
        }

        @Test
        fun `un auditeur retiré n'est plus prévenu`() {
            val other = mutableListOf<List<ContactName>>()
            val listener = ContactsRepository.Listener { other += it }
            repository.addListener(listener)
            repository.removeListener(listener)

            repository.setEnabled(true)

            assertThat(other).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une lecture terminée après la désactivation est ignorée`() {
            val pending = ArrayDeque<Runnable>()
            val deferred = ContactsRepository({ book }, { pending.addLast(it) }, now)
            deferred.setEnabled(true)
            deferred.setEnabled(false)

            while (pending.isNotEmpty()) pending.removeFirst().run()

            assertThat(deferred.contacts).isEmpty()
        }

        @Test
        fun `même état demandé deux fois - une seule lecture`() {
            repository.setEnabled(true)
            repository.setEnabled(true)
            repository.reload()

            assertThat(reads).isEqualTo(2)
            assertThat(seen).hasSize(1)
        }

        @Test
        fun `au plus MAX_CONTACTS contacts`() {
            val rows = (0..ContactRows.MAX_CONTACTS).asSequence().map { ContactRow(it.toLong(), givenName = "Nom") } +
                sequenceOf(ContactRow(0, familyName = "Dupont"))

            val contacts = ContactRows.group(rows)

            assertThat(contacts).hasSize(ContactRows.MAX_CONTACTS)
            assertThat(contacts[0].familyName).isEqualTo("Dupont")
        }
    }
}
