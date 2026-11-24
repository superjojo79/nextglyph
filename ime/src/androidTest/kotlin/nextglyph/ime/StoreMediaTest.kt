package nextglyph.ime

import android.content.Context
import android.graphics.Point
import android.util.Log
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import nextglyph.ime.debug.ShowcaseActivity
import nextglyph.ime.settings.HeatmapActivity
import nextglyph.ime.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.regex.Pattern

/**
 * Production des visuels bruts des stores : captures d'écran et séquence filmée, dans la langue du système
 * (français AZERTY ou anglais QWERTY : l'émulateur est lancé dans la bonne locale, voir `scripts/store-media.sh`).
 *
 * Le test ouvre `ShowcaseActivity` (fausse messagerie, variante debug), active le clavier NextGlyph (`ime set`) et
 * tape touche par touche : chaque touche et chaque case de la barre est un nœud d'accessibilité virtuel décrit par
 * `KeyDescriptions` (lettre, « Espace », « Effacer », « Majuscule »…), donc trouvable par `By.desc` quelle que soit
 * la géométrie adaptative. Les lettres se cherchent sans tenir compte de la casse (la majuscule automatique en
 * début de phrase les décrit en capitales).
 *
 * **Ne s'exécute que si l'argument d'instrumentation `storeMedia` vaut `true`** (`-e storeMedia true`) : sinon tous
 * les tests sont ignorés (`Assume`) pour ne pas gêner les autres lancements (`connectedDebugAndroidTest`).
 *
 * Les captures sont prises par `screencap` (shell, via `UiAutomation`) dans [OUTPUT_DIR] (`/data/local/tmp`) :
 * le stockage externe de l'APK de test n'est pas monté sur l'émulateur, et le shell écrit et relit ce dossier
 * sans permission (`adb pull`). Fichiers nommés `NN-description.png`.
 *
 * JUnit 4 (AndroidJUnitRunner) n'a pas de `@Nested` : la convention HappyPath / ErrorCases / BoundaryValues est
 * tenue par des classes imbriquées et le lanceur `Enclosed`. L'ordre des méthodes n'étant pas garanti, les captures
 * dépendantes de l'état du champ sont enchaînées dans un seul test séquentiel. Noms de tests sans espaces (DEX < API 30).
 *
 * Lancement : `bash scripts/store-media.sh <dossier>` (émulateur ou appareil), ou seule la vidéo :
 * `-e class 'nextglyph.ime.StoreMediaTest$HappyPath#video_scenario'`.
 */
@RunWith(Enclosed::class)
class StoreMediaTest {

    class HappyPath : StoreMediaFixture() {

        @Test
        fun captures_dans_l_ordre() {
            // 01 : lettres probables pendant la frappe d'un mot.
            typeText(texts.partialWord)
            capture("01-lettres-probables.png")

            // 02 : mot suivant, juste après un mot validé par espace.
            clearField()
            typeText(texts.greeting)
            tapSpace()
            capture("02-mot-suivant.png")

            // 03 : correction annoncée par la barre d'espace (faute de frappe, sans espace).
            clearField()
            typeText(texts.typo)
            capture("03-correction.png")

            // 04 : panneau des émojis (appui long sur ↵).
            clearField()
            val emoji = runCatching {
                openEmojiPanel()
                capture("04-emojis.png")
                tap(Pattern.quote(string(R.string.a11y_letters)), "la touche « ${string(R.string.a11y_letters)} » du panneau émojis")
                waitForKeyboard()
            }

            // 05 et 06 : écrans de l'application, par-dessus le décor.
            captureActivity(SettingsActivity::class.java, "05-reglages.png")
            captureActivity(HeatmapActivity::class.java, "06-carte-de-frappe.png")
            // L'échec des émojis n'empêche pas les captures 05 et 06, mais fait échouer le test.
            emoji.getOrThrow()
        }

        @Test
        fun video_scenario() {
            // Laisse le temps à l'enregistrement d'écran (lancé par le script juste avant ce test) de démarrer.
            Thread.sleep(2_000)
            val words = texts.sentence.split(' ')
            for ((index, word) in words.withIndex()) {
                if (index == texts.acceptedWordIndex) typeWordAcceptingSuggestion(word) else typeText(word, VIDEO_KEY_PAUSE_MS)
                if (index < words.lastIndex && !fieldText().endsWith(" ")) tapSpace(VIDEO_KEY_PAUSE_MS)
            }
            Thread.sleep(2_500)
            capture("07-video-fin.png")
            assertTrue("La phrase doit être dans le champ : « ${fieldText()} ».", fieldText().isNotBlank())
        }
    }

    class ErrorCases : StoreMediaFixture() {

        @Test
        fun une_touche_inconnue_echoue_avec_un_message_clair() {
            try {
                tap(Pattern.quote("touche-inexistante"), "la touche de test", timeoutMs = 1_500)
                fail("Une touche absente doit faire échouer le tap.")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("Touche introuvable"))
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("la touche de test"))
            }
            assertEquals("", fieldText())
        }

        @Test
        fun la_capture_dans_un_dossier_impossible_echoue() {
            val blocked = "$OUTPUT_DIR/fichier-ordinaire"
            shell("touch $blocked")
            try {
                val error = runCatching { screenshot("$blocked/inaccessible.png") }.exceptionOrNull()
                assertTrue("Une capture sous un fichier ordinaire doit échouer.", error != null)
            } finally {
                shell("rm -f $blocked")
            }
        }
    }

    class BoundaryValues : StoreMediaFixture() {

        @Test
        fun capture_du_clavier_au_repos_avec_le_champ_vide() {
            assertEquals("", fieldText())
            capture("00-repos.png")
            assertTrue(sizeOf("$OUTPUT_DIR/00-repos.png") > 0)
        }

        @Test
        fun effacer_un_champ_deja_vide_ne_boucle_pas() {
            clearField()
            assertEquals("", fieldText())
        }
    }

    /** Activation du clavier, décor, frappe touche par touche et captures. Ignoré sans `-e storeMedia true`. */
    abstract class StoreMediaFixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        protected val context: Context = instrumentation.targetContext
        private val device: UiDevice = UiDevice.getInstance(instrumentation)
        private var scenario: ActivityScenario<ShowcaseActivity>? = null
        private var previousIme: String? = null


        internal val texts: StoreTexts = when (context.resources.configuration.locales.get(0)?.language) {
            "fr" -> StoreTexts("Bonj", "Bonjour", "bonjoir", "Bonjour Camille je passe vers sept heures et on se retrouve devant le cinema", 3)
            else -> StoreTexts("Hel", "Hello", "helli", "Hi Camille I will come around seven and we can meet in front of the cinema", 3)
        }

        @Before
        fun open() {
            assumeTrue(
                "Visuels des stores : lancer avec -e storeMedia true.",
                InstrumentationRegistry.getArguments().getString("storeMedia") == "true",
            )
            previousIme = shell("settings get secure default_input_method").trim().takeIf { it.isNotEmpty() && it != "null" }
            shell("ime enable $IME_ID")
            shell("ime set $IME_ID")
            scenario = ActivityScenario.launch(ShowcaseActivity::class.java)
            waitForKeyboard()
            device.waitForIdle()
            Thread.sleep(SETTLE_MS)
        }

        @After
        fun close() {
            scenario?.close()
            previousIme?.let { if (it != IME_ID) shell("ime set $it") }
        }

        protected fun string(id: Int): String = context.getString(id)

        protected fun waitForKeyboard() {
            val keyboard = device.wait(Until.findObject(By.desc(string(R.string.keyboard_content_description))), TIMEOUT_MS)
            checkNotNull(keyboard) { "Le clavier NextGlyph ne s'est pas affiché." }
        }

        protected fun fieldText(): String {
            var text = ""
            scenario?.onActivity { text = it.findViewById<EditText>(R.id.showcase_input).text.toString() }
            return text
        }

        /**
         * Touche le nœud dont la description correspond entièrement à [regex] ; [what] nomme la touche dans le message
         * d'erreur. Courte pause ensuite pour un rendu naturel.
         */
        protected fun tap(regex: String, what: String, pauseMs: Long = KEY_PAUSE_MS, timeoutMs: Long = TIMEOUT_MS) {
            val node = find(regex, timeoutMs)
                ?: throw IllegalStateException("Touche introuvable : $what (description « $regex »). Le clavier est-il affiché ?")
            node.click()
            device.waitForIdle()
            Thread.sleep(pauseMs)
        }

        private fun find(regex: String, timeoutMs: Long = 0): UiObject2? {
            val selector = By.desc(Pattern.compile(regex))
            return if (timeoutMs > 0) device.wait(Until.findObject(selector), timeoutMs) else device.findObject(selector)
        }

        /** Touche une lettre (casse ignorée), un chiffre ou une ponctuation ; bascule sur les symboles si nécessaire. */
        protected fun tapChar(char: Char, pauseMs: Long = KEY_PAUSE_MS) {
            if (char == ' ') return tapSpace(pauseMs)
            val regex = "(?i)" + Pattern.quote(char.toString())
            if (find(regex) != null) return tap(regex, "le caractère « $char »", pauseMs)
            val symbols = Pattern.quote(string(R.string.a11y_symbols))
            if (find(symbols) != null) {
                tap(symbols, "la touche Symboles", pauseMs)
                if (find(regex) != null) tap(regex, "le caractère « $char »", pauseMs)
                else Log.w(TAG, "Caractère « $char » absent des symboles : ignoré.")
                find(Pattern.quote(string(R.string.a11y_letters)))?.let {
                    tap(Pattern.quote(string(R.string.a11y_letters)), "la touche Lettres", pauseMs)
                }
            } else {
                Log.w(TAG, "Caractère « $char » absent du clavier : ignoré.")
            }
        }

        protected fun typeText(text: String, pauseMs: Long = KEY_PAUSE_MS) {
            for (char in text) tapChar(char, pauseMs)
        }

        protected fun tapSpace(pauseMs: Long = KEY_PAUSE_MS) =
            tap(Pattern.quote(string(R.string.a11y_space)) + ".*", "la barre d'espace", pauseMs)

        /** Frappe le début d'un mot, accepte la première suggestion de la barre si elle existe, sinon finit le mot. */
        protected fun typeWordAcceptingSuggestion(word: String) {
            val prefix = word.take(word.length / 2 + 1)
            typeText(prefix, VIDEO_KEY_PAUSE_MS)
            Thread.sleep(600)
            val suggestion = find(Pattern.quote(string(R.string.a11y_suggestion).substringBefore("%")) + ".+")
            if (suggestion != null) {
                suggestion.click()
                device.waitForIdle()
                Thread.sleep(800)
            } else {
                Log.w(TAG, "Aucune suggestion pour « $prefix » : le mot est tapé en entier.")
                typeText(word.drop(prefix.length), VIDEO_KEY_PAUSE_MS)
            }
        }

        /** Vide le champ avec `⌫` (au plus [MAX_ERASE] appuis). */
        protected fun clearField() {
            var guard = 0
            while (fieldText().isNotEmpty() && guard++ < MAX_ERASE) {
                tap(Pattern.quote(string(R.string.a11y_backspace)), "la touche Effacer", ERASE_PAUSE_MS)
            }
            check(fieldText().isEmpty()) { "Impossible de vider le champ : « ${fieldText()} »." }
        }

        /** Le panneau émojis s'ouvre par un appui long sur `↵` (menu de touche, spec §7) puis relâchement sur « 😀 ». */
        protected fun openEmojiPanel() {
            val enter = listOf(
                R.string.a11y_enter_send, R.string.a11y_enter, R.string.a11y_enter_go, R.string.a11y_enter_done,
                R.string.a11y_enter_next, R.string.a11y_enter_search,
            ).map { Pattern.quote(string(it)) }.joinToString("|")
            val node = device.wait(Until.findObject(By.desc(Pattern.compile(enter))), TIMEOUT_MS)
                ?: throw IllegalStateException("Touche introuvable : la touche Entrée (description « $enter »).")
            // Le menu de l'appui long s'ouvre au-dessus de la première rangée, à gauche (KeyboardView.showPopup) :
            // on garde le doigt sur ↵, puis on glisse jusqu'à l'entrée « émojis » avant de relâcher.
            val start = node.visibleCenter
            val topLeft = if (context.resources.configuration.locales.get(0)?.language == "fr") "a" else "q"
            val firstKey = device.findObject(By.desc(Pattern.compile("(?i)$topLeft")))
                ?: throw IllegalStateException("Touche introuvable : première touche de la rangée du haut (a ou q).")
            val target = Point(firstKey.visibleBounds.left + firstKey.visibleBounds.width() / 2, firstKey.visibleCenter.y)
            check(device.swipe(arrayOf(start, Point(start.x, start.y), target, target), HOLD_STEPS)) { "Appui long sur ↵ refusé." }
            val letters = string(R.string.a11y_letters)
            checkNotNull(device.wait(Until.findObject(By.desc(letters)), TIMEOUT_MS)) {
                "Le panneau émojis ne s'est pas ouvert (touche « $letters » introuvable)."
            }
            device.waitForIdle()
            Thread.sleep(SETTLE_MS)
        }

        protected fun <T : android.app.Activity> captureActivity(type: Class<T>, name: String) {
            ActivityScenario.launch(type).use {
                device.waitForIdle()
                Thread.sleep(SETTLE_MS)
                capture(name)
            }
            device.waitForIdle()
        }

        protected fun capture(name: String) {
            device.waitForIdle()
            Thread.sleep(SETTLE_MS / 2)
            val file = "$OUTPUT_DIR/$name"
            screenshot(file)
            Log.i(TAG, "Capture : $file")
        }

        /** Capture d'écran par le shell ; échoue si le fichier n'existe pas ou est vide. */
        protected fun screenshot(path: String) {
            shell("mkdir -p $OUTPUT_DIR")
            shell("screencap -p $path")
            check(sizeOf(path) > 0) { "La capture d'écran a échoué pour $path." }
        }

        /** Taille du fichier [path] lue par le shell, 0 s'il n'existe pas. */
        protected fun sizeOf(path: String): Long = shell("stat -c %s $path").trim().toLongOrNull() ?: 0L

        protected fun shell(command: String): String {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            return FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }.also { descriptor.close() }
        }
    }

    private companion object {
        const val IME_ID = "io.github.superjojo79.nextglyph/nextglyph.ime.NextglyphInputMethodService"
        const val TAG = "StoreMediaTest"
        const val OUTPUT_DIR = "/data/local/tmp/store-media"
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 1_000L
        const val KEY_PAUSE_MS = 120L
        const val ERASE_PAUSE_MS = 60L
        const val VIDEO_KEY_PAUSE_MS = 250L
        /** Pas de 5 ms environ : 200 pas ≈ 1 s de maintien par segment du geste. */
        const val HOLD_STEPS = 200
        const val MAX_ERASE = 40
    }
}

/** Textes des scénarios, selon la langue du système (hors de `StoreMediaTest` : `Enclosed` n'accepte que des classes de test). */
internal class StoreTexts(
    val partialWord: String,
    val greeting: String,
    val typo: String,
    val sentence: String,
    val acceptedWordIndex: Int,
)
