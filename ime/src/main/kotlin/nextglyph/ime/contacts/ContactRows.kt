package nextglyph.ime.contacts

import nextglyph.engine.contacts.ContactName

/**
 * Une ligne de `ContactsContract.Data` (spec §6.2) : nom structuré ou surnom d'un contact.
 *
 * @property contactId `Data.CONTACT_ID` ; plusieurs lignes d'un même contact (comptes différents) sont réunies.
 */
class ContactRow(
    val contactId: Long,
    val givenName: String? = null,
    val familyName: String? = null,
    val middleName: String? = null,
    val nickname: String? = null,
)

/** Regroupement des lignes de `ContactsContract.Data` en contacts (spec §6.2). */
object ContactRows {

    /** Nombre maximal de contacts lus (au-delà, le carnet est tronqué). */
    const val MAX_CONTACTS: Int = 20_000

    /**
     * Réunit les [rows] par contact : le premier prénom, nom et deuxième prénom non vides rencontrés, et le
     * premier surnom. Les contacts sans aucun nom sont ignorés ; au plus [MAX_CONTACTS] contacts.
     */
    fun group(rows: Sequence<ContactRow>): List<ContactName> {
        val byContact = LinkedHashMap<Long, ContactName>()
        for (row in rows) {
            val current = byContact[row.contactId]
            if (current == null && byContact.size >= MAX_CONTACTS) continue
            val base = current ?: ContactName()
            byContact[row.contactId] = ContactName(
                givenName = base.givenName ?: row.givenName.clean(),
                familyName = base.familyName ?: row.familyName.clean(),
                middleName = base.middleName ?: row.middleName.clean(),
                nickname = base.nickname ?: row.nickname.clean(),
            )
        }
        return byContact.values.filter { it.givenName != null || it.familyName != null || it.middleName != null || it.nickname != null }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
