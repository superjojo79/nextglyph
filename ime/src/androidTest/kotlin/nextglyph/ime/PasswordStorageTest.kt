package nextglyph.ime

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.SpecialLayouts
import nextglyph.ime.debug.FieldTestsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * Critère du §15.2 : une saisie dans un champ mot de passe, faite avec le clavier NextGlyph, n'écrit rien dans le
 * stockage de l'application (`dataDir` : fichiers, préférences, bases, cache).
 *
 * Le test ouvre le panel « Champs de test » (variante debug), active le clavier NextGlyph (`ime set`), touche les
 * touches de la disposition du mot de passe (géométrie calculée par le moteur) et compare l'état du stockage avant
 * et après. JUnit 4 (AndroidJUnitRunner) n'a pas de `@Nested` : la convention HappyPath / ErrorCases /
 * BoundaryValues est tenue par des classes imbriquées et le lanceur `Enclosed`. Noms de tests sans espaces :
 * le format DEX des API < 30 les refuse.
 *
 * Lancement local (émulateur ou appareil, voir docs/FIELD-TESTS.md) : `./gradlew :ime:connectedDebugAndroidTest`.
 */
@RunWith(Enclosed::class)
class PasswordStorageTest {

    class HappyPath : PasswordFixture() {
        @Test
        fun saisie_au_clavier_dans_un_mot_de_passe_sans_aucune_ecriture() {
            val before = snapshot()

            typeOnPassword("⇧", "s", "3", "c", "r", "e", "t", "!")

            assertEquals("S3cret!", passwordText())
            settle()
            assertEquals("Fichiers ajoutés ou modifiés : ${diff(before, snapshot())}", before, snapshot())
        }
    }

    class ErrorCases : PasswordFixture() {
        @Test
        fun le_controle_du_stockage_repere_un_fichier_ecrit() {
            val before = snapshot()
            val probe = File(context.filesDir, "sonde-du-test")

            try {
                probe.writeText("x")
                assertNotEquals(before, snapshot())
            } finally {
                probe.delete()
            }
            assertEquals(before, snapshot())
        }

        @Test
        fun effacer_dans_un_mot_de_passe_sans_aucune_ecriture() {
            val before = snapshot()

            typeOnPassword("a", "b", "⌫", "⌫", "⌫")

            assertEquals("", passwordText())
            settle()
            assertEquals("Fichiers ajoutés ou modifiés : ${diff(before, snapshot())}", before, snapshot())
        }
    }

    class BoundaryValues : PasswordFixture() {
        @Test
        fun mot_de_passe_vide_puis_entree_sans_aucune_ecriture() {
            val before = snapshot()

            typeOnPassword("↵")

            assertEquals("", passwordText())
            settle()
            assertEquals("Fichiers ajoutés ou modifiés : ${diff(before, snapshot())}", before, snapshot())
        }
    }

    /** Ouverture du panel, activation du clavier, frappe par coordonnées, instantané du stockage. */
    abstract class PasswordFixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        protected val context: Context = instrumentation.targetContext
        private val device: UiDevice = UiDevice.getInstance(instrumentation)
        private lateinit var scenario: ActivityScenario<FieldTestsActivity>
        private var previousIme: String? = null

        @Before
        fun open() {
            previousIme = shell("settings get secure default_input_method").trim().takeIf { it.isNotEmpty() && it != "null" }
            shell("ime enable $IME_ID")
            shell("ime set $IME_ID")
            scenario = ActivityScenario.launch(FieldTestsActivity::class.java)
            scenario.onActivity { activity -> focusPassword(activity) }
            val keyboard = device.wait(Until.findObject(By.desc(context.getString(R.string.keyboard_content_description))), TIMEOUT_MS)
            checkNotNull(keyboard) { "Le clavier NextGlyph ne s'est pas affiché." }
            device.waitForIdle()
        }

        @After
        fun close() {
            scenario.close()
            previousIme?.let { if (it != IME_ID) shell("ime set $it") }
        }

        private fun focusPassword(activity: Activity) {
            val field = activity.findViewById<EditText>(R.id.field_password)
            field.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        }

        protected fun passwordText(): String {
            var text = ""
            scenario.onActivity { text = it.findViewById<EditText>(R.id.field_password).text.toString() }
            return text
        }

        /** Touche les touches de libellés [labels] sur la disposition du mot de passe (§7.1). */
        protected fun typeOnPassword(vararg labels: String) {
            val view = device.findObject(By.desc(context.getString(R.string.keyboard_content_description)))
            for (label in labels) {
                val (x, y) = keyCenter(view, label)
                device.click(x, y)
                device.waitForIdle()
            }
        }

        private fun keyCenter(view: UiObject2, label: String): Pair<Int, Int> {
            val bounds = view.visibleBounds
            val density = context.resources.displayMetrics.density
            val kind = LayoutKind.defaultForLanguage(context.resources.configuration.locales.get(0)?.language)
            val layout: KeyboardLayout = SpecialLayouts.password(kind)
            val geometry = KeyboardGeometry.compute(
                layout, bounds.width().toFloat(), KeyboardMetrics.DEFAULT_ROW_HEIGHT_DP * density, KeyboardMetrics.ROW_GAP_DP * density,
            )
            val rect = geometry.rows.flatten().first { matches(it.key.kind, it.key.label, label) }
            // Le mot de passe n'a pas de barre : la première rangée commence en haut de la vue.
            return (bounds.left + rect.centerX).toInt() to (bounds.top + rect.centerY).toInt()
        }

        private fun matches(kind: KeyKind, keyLabel: String, wanted: String): Boolean = when (wanted) {
            "⇧" -> kind == KeyKind.SHIFT
            "⌫" -> kind == KeyKind.BACKSPACE
            "↵" -> kind == KeyKind.ENTER
            else -> keyLabel == wanted
        }

        /** Laisse le temps à une écriture différée (préférences appliquées en arrière-plan, etc.). */
        protected fun settle() {
            device.waitForIdle()
            Thread.sleep(SETTLE_MS)
        }

        /** Chemin relatif → (taille, date) de chaque fichier du stockage de l'application, hors `code_cache` (ART). */
        protected fun snapshot(): Map<String, Pair<Long, Long>> {
            val root = context.dataDir
            return root.walkTopDown()
                .onEnter { it.name != "code_cache" || it.parentFile != root }
                .filter { it.isFile }
                .associate { it.relativeTo(root).path to (it.length() to it.lastModified()) }
        }

        protected fun diff(before: Map<String, Pair<Long, Long>>, after: Map<String, Pair<Long, Long>>): List<String> =
            after.filter { (path, state) -> before[path] != state }.keys.sorted() + before.keys.filter { it !in after }.map { "supprimé : $it" }

        private fun shell(command: String): String {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            return FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }.also { descriptor.close() }
        }

        init {
            assertTrue("Le test doit tourner sur la variante debug (panel de test).", BuildConfigCheck.hasFieldTests(context))
        }
    }

    private object BuildConfigCheck {
        fun hasFieldTests(context: Context): Boolean =
            runCatching { Class.forName("nextglyph.ime.debug.FieldTestsActivity", false, context.classLoader) }.isSuccess
    }

    private companion object {
        const val IME_ID = "io.github.superjojo79.nextglyph/nextglyph.ime.NextglyphInputMethodService"
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 1_500L
    }
}
