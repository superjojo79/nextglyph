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
 * Contrôle le manifeste **fusionné** de la variante testée (fourni par le build via
 * la propriété système `nextglyph.mergedManifest`) : aucune permission réseau (spec §2.7, §12),
 * service de saisie correctement protégé.
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

    /** Permissions admises (§12) : contacts (optionnelle) et vibration. */
    private val allowed = setOf(readContacts, "android.permission.VIBRATE")

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
        fun `le manifeste fusionné ne déclare aucune permission INTERNET`() {
            assertThat(permissionsOf(mergedManifest()))
                .noneMatch { it.endsWith(".INTERNET") }
        }

        @Test
        fun `seule READ_CONTACTS est déclarée, et VIBRATE au plus (§12)`() {
            for (variant in listOf("debug", "release")) {
                val permissions = permissionsOf(mergedManifest(variant))

                assertThat(permissions).`as`(variant).contains(readContacts)
                assertThat(permissions).`as`(variant).isSubsetOf(allowed)
            }
        }

        @Test
        fun `aucune autre permission dangereuse`() {
            assertThat(permissionsOf(mergedManifest()) - readContacts).doesNotContainAnyElementsOf(dangerous)
        }

        @Test
        fun `le service de saisie est protégé par BIND_INPUT_METHOD et exporté`() {
            val services = mergedManifest().getElementsByTagName("service")

            assertThat(services.length).isEqualTo(1)
            val service = services.item(0) as Element
            assertThat(service.getAttributeNS(android, "name")).isEqualTo("nextglyph.ime.NextglyphInputMethodService")
            assertThat(service.getAttributeNS(android, "permission")).isEqualTo("android.permission.BIND_INPUT_METHOD")
            assertThat(service.getAttributeNS(android, "exported")).isEqualTo("true")
        }

        @Test
        fun `le panel des champs de test est dans la variante debug`() {
            assertThat(activitiesOf(mergedManifest("debug"))).contains(fieldTests, "nextglyph.ime.settings.SettingsActivity")
        }

        @Test
        fun `le panel des champs de test n'est jamais dans la release`() {
            val release = mergedManifest("release")

            assertThat(activitiesOf(release)).containsExactly("nextglyph.ime.settings.SettingsActivity")
            assertThat(activitiesOf(release)).noneMatch { it.contains(".debug.") }
        }

        @Test
        fun `l'application déclare les règles de sauvegarde`() {
            val application = mergedManifest().getElementsByTagName("application").item(0) as Element

            assertThat(application.getAttributeNS(android, "dataExtractionRules")).isNotEmpty()
            assertThat(application.getAttributeNS(android, "fullBackupContent")).isNotEmpty()
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
