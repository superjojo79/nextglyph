package nextglyph.ime.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import nextglyph.engine.contacts.ContactName

/** Lecture des noms dans `ContactsContract.Data` (spec §6.2). Aucun nom n'est journalisé. */
object ContactsSource {

    /** Vrai si la permission `READ_CONTACTS` est accordée. */
    fun isPermitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Prénoms, noms, deuxièmes prénoms et surnoms des contacts. Lève `SecurityException` sans la permission. */
    fun read(resolver: ContentResolver): List<ContactName> {
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            StructuredName.GIVEN_NAME,
            StructuredName.FAMILY_NAME,
            StructuredName.MIDDLE_NAME,
            Nickname.NAME,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val args = arrayOf(StructuredName.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE)
        val cursor = resolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null) ?: return emptyList()
        return cursor.use { c ->
            val rows = ArrayList<ContactRow>()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                rows += if (c.getString(1) == Nickname.CONTENT_ITEM_TYPE) {
                    ContactRow(id, nickname = c.getString(5))
                } else {
                    ContactRow(id, givenName = c.getString(2), familyName = c.getString(3), middleName = c.getString(4))
                }
            }
            ContactRows.group(rows.asSequence())
        }
    }
}
