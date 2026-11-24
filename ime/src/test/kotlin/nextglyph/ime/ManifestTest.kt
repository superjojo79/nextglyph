package nextglyph.ime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Contrôle les manifestes **fusionnés** de toutes les variantes (fournis par le build via les propriétés système
 * `nextglyph.mergedManifest.<variante>`, ex. `fdroidDebug`, `playRelease`) : aucune permission réseau dans `fdroid*`,
 * INTERNET seule dans `play*` (spec §2.7, §12), service de saisie correctement protégé.
 */
class ManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(xml.byteInputStream())

    private fun permissionsOf(document: Document): List<String> =
        listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }
        }

    private fun mergedManifest(variant: String? = null): Document {
        val property = if (variant == null) "nextglyph.mergedManifest" else "nextglyph.mergedManifest.$variant"
        val path = checkNotNull(System.getProperty(property)) {
            "Propriété $property absente : le test doit tourner via Gradle."
        }
        return parse(File(path).readText())
    }

    private fun activitiesOf(document: Document): List<String> {
        val nodes = document.getElementsByTagName("activity")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }
    }

    private val fieldTests = "nextglyph.ime.debug.FieldTestsActivity"

    private val readContacts = "android.permission.READ_CONTACTS"

    private val internet = "android.permission.INTERNET"

    /** Permissions admises dans `fdroid*` (§12) : contacts (optionnelle) et vibration. */
    private val allowed = setOf(readContacts, "android.permission.VIBRATE")

    /** Permissions admises dans `play*` : celles de fdroid, plus INTERNET (mise à jour des dictionnaires, §10.7). */
    private val allowedPlay = allowed + internet

    private val fdroidVariants = listOf("fdroidDebug", "fdroidRelease")
    private val playVariants = listOf("playDebug", "playRelease")
    private val allVariants = fdroidVariants + playVariants

    /** Permissions réseau : toutes interdites dans `fdroid*`, seule INTERNET est admise dans `play*`. */
    private val network = listOf(
        "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE", "CHANGE_WIFI_MULTICAST_STATE",
    ).map { "android.permission.$it" }

    /** Permissions dangereuses courantes (niveau `dangerous` d'Android), toutes interdites hormis READ_CONTACTS. */
    private val dangerous = listOf(
        "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS", "READ_CALENDAR", "WRITE_CALENDAR", "CAMERA", "RECORD_AUDIO",
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_PHONE_STATE", "CALL_PHONE",
        "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "BODY_SENSORS",
        "ACTIVITY_RECOGNITION", "POST_NOTIFICATIONS", "NEARBY_WIFI_DEVICES", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN",
        "INTERNET", "ACCESS_NETWORK_STATE",
    ).map { "android.permission.$it" }

    @Nested
    inner class HappyPath {
        @Test
        fun `les variantes fdroid ne déclarent aucune permission réseau (§2.7)`() {
            for (variant in fdroidVariants) {
                assertThat(permissionsOf(mergedManifest(variant))).`as`(variant).doesNotContainAnyElementsOf(network)
            }
        }

        @Test
        fun `les variantes play déclarent INTERNET et aucune autre permission réseau (§2.7, §10.7)`() {
            for (variant in playVariants) {
                val permissions = permissionsOf(mergedManifest(variant))

                assertThat(permissions).`as`(variant).contains(internet)
                assertThat(permissions - internet).`as`(variant).doesNotContainAnyElementsOf(network)
            }
        }

        @Test
        fun `fdroid a READ_CONTACTS et VIBRATE au plus, play y ajoute INTERNET (§12)`() {
            for (variant in fdroidVariants) {
                val permissions = permissionsOf(mergedManifest(variant))

                assertThat(permissions).`as`(variant).contains(readContacts)
                assertThat(permissions).`as`(variant).isSubsetOf(allowed)
            }
            for (variant in playVariants) {
                val permissions = permissionsOf(mergedManifest(variant))

                assertThat(permissions).`as`(variant).contains(readContacts)
                assertThat(permissions).`as`(variant).isSubsetOf(allowedPlay)
            }
        }

        @Test
        fun `aucune autre permission dangereuse`() {
            for (variant in allVariants) {
                assertThat(permissionsOf(mergedManifest(variant)) - readContacts - internet).`as`(variant).doesNotContainAnyElementsOf(dangerous)
            }
        }

        @Test
        fun `le service de saisie est protégé par BIND_INPUT_METHOD et exporté`() {
            for (variant in allVariants) {
                val services = mergedManifest(variant).getElementsByTagName("service")

                assertThat(services.length).`as`(variant).isEqualTo(1)
                val service = services.item(0) as Element
                assertThat(service.getAttributeNS(android, "name")).isEqualTo("nextglyph.ime.NextglyphInputMethodService")
                assertThat(service.getAttributeNS(android, "permission")).isEqualTo("android.permission.BIND_INPUT_METHOD")
                assertThat(service.getAttributeNS(android, "exported")).isEqualTo("true")
            }
        }

        @Test
        fun `le manifeste de la variante testée est lu par sa propriété propre`() {
            assertThat(permissionsOf(mergedManifest())).contains(readContacts)
        }

        @Test
        fun `le panel des champs de test est dans les variantes debug`() {
            for (variant in listOf("fdroidDebug", "playDebug")) {
                assertThat(activitiesOf(mergedManifest(variant))).`as`(variant)
                    .contains(fieldTests, "nextglyph.ime.settings.SettingsActivity", "nextglyph.ime.settings.HeatmapActivity")
            }
        }

        @Test
        fun `le panel des champs de test n'est jamais dans une release`() {
            for (variant in listOf("fdroidRelease", "playRelease")) {
                val release = mergedManifest(variant)

                assertThat(activitiesOf(release)).`as`(variant).containsExactlyInAnyOrder(
                    "nextglyph.ime.settings.SettingsActivity",
                    "nextglyph.ime.settings.HeatmapActivity",
                )
                assertThat(activitiesOf(release)).`as`(variant).noneMatch { it.contains(".debug.") }
            }
        }

        @Test
        fun `l'application déclare les règles de sauvegarde`() {
            for (variant in allVariants) {
                val application = mergedManifest(variant).getElementsByTagName("application").item(0) as Element

                assertThat(application.getAttributeNS(android, "dataExtractionRules")).`as`(variant).isNotEmpty()
                assertThat(application.getAttributeNS(android, "fullBackupContent")).`as`(variant).isNotEmpty()
            }
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `le détecteur repère INTERNET dans un manifeste`() {
            val document = parse(
                """<manifest xmlns:android="$android">
                     <uses-permission android:name="android.permission.INTERNET"/>
                   </manifest>""",
            )

            assertThat(permissionsOf(document)).containsExactly("android.permission.INTERNET")
        }

        @Test
        fun `le détecteur repère aussi la variante sdk-23`() {
            val document = parse(
                """<manifest xmlns:android="$android">
                     <uses-permission-sdk-23 android:name="android.permission.INTERNET"/>
                   </manifest>""",
            )

            assertThat(permissionsOf(document)).containsExactly("android.permission.INTERNET")
        }

        @Test
        fun `le détecteur d'activités repère le panel dans un manifeste`() {
            val document = parse(
                """<manifest xmlns:android="$android"><application>
                     <activity android:name="$fieldTests"/>
                   </application></manifest>""",
            )

            assertThat(activitiesOf(document)).containsExactly(fieldTests)
        }

        @Test
        fun `le contrôle repère une permission dangereuse ajoutée`() {
            val document = parse(
                """<manifest xmlns:android="$android">
                     <uses-permission android:name="$readContacts"/>
                     <uses-permission android:name="android.permission.CAMERA"/>
                   </manifest>""",
            )

            assertThat(permissionsOf(document) - readContacts).containsAnyElementsOf(dangerous)
            assertThat(permissionsOf(document)).isNotEmpty().matches { !allowed.containsAll(it) }
        }

        @Test
        fun `une variante inconnue est refusée avec un message clair`() {
            assertThatThrownBy { mergedManifest("inconnue") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("nextglyph.mergedManifest.inconnue")
        }

        @Test
        fun `le contrôle repère ACCESS_NETWORK_STATE, interdite même dans play`() {
            val document = parse(
                """<manifest xmlns:android="$android">
                     <uses-permission android:name="$internet"/>
                     <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
                   </manifest>""",
            )

            assertThat(permissionsOf(document) - internet).containsAnyElementsOf(network)
            assertThat(permissionsOf(document)).matches { !allowedPlay.containsAll(it) }
        }

        @Test
        fun `un XML invalide est rejeté`() {
            assertThatThrownBy { parse("<manifest>") }.isInstanceOf(Exception::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un manifeste sans permission donne une liste vide`() {
            assertThat(permissionsOf(parse("""<manifest xmlns:android="$android"/>"""))).isEmpty()
        }

        @Test
        fun `INTERNET seule suffit à la variante play, pas à fdroid`() {
            val permissions = listOf(internet)

            assertThat(allowedPlay.containsAll(permissions)).isTrue()
            assertThat(allowed.containsAll(permissions)).isFalse()
        }

        @Test
        fun `un manifeste sans activité donne une liste vide`() {
            assertThat(activitiesOf(parse("""<manifest xmlns:android="$android"><application/></manifest>"""))).isEmpty()
        }

        @Test
        fun `les permissions voisines ne sont pas confondues avec INTERNET`() {
            val document = parse(
                """<manifest xmlns:android="$android">
                     <uses-permission android:name="android.permission.VIBRATE"/>
                     <uses-permission android:name="android.permission.READ_CONTACTS"/>
                   </manifest>""",
            )

            assertThat(permissionsOf(document)).noneMatch { it.endsWith(".INTERNET") }.hasSize(2)
        }
    }
}
