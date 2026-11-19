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

    @Nested
    inner class HappyPath {
        @Test
        fun `le manifeste fusionné ne déclare aucune permission INTERNET`() {
            assertThat(permissionsOf(mergedManifest()))
                .noneMatch { it.endsWith(".INTERNET") }
        }

        @Test
        fun `le manifeste fusionné ne déclare aucune permission pour l'instant`() {
            assertThat(permissionsOf(mergedManifest())).isEmpty()
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
