package nextglyph.ime

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cohérence des textes : chaque texte traduisible de `values/` (anglais, par défaut) existe dans `values-fr/` et
 * inversement, avec les mêmes paramètres de format, et chaque explication `pref_*_info` (issue #48) a un texte.
 * Les tests unitaires Gradle s'exécutent dans le dossier du module : `src/main/res` s'y résout.
 */
class StringResourcesTest {

    /** Un texte ou un pluriel : [text] réunit les éléments `<item>` d'un pluriel, un par ligne. */
    private data class Entry(val name: String, val text: String, val plural: Boolean)

    private val resources = File("src/main/res")

    /** Textes traduisibles d'un dossier de ressources (tous ses `strings*.xml`), par nom. */
    private fun load(folder: String): Map<String, Entry> =
        (resources.resolve(folder).listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" } ?: emptyArray())
            .sortedBy { it.name }
            .flatMap { parse(it.readText()) }
            .associateBy { it.name }

    private fun parse(xml: String): List<Entry> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val children = document.documentElement.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }.mapNotNull { element ->
            val name = element.getAttribute("name")
            when {
                element.getAttribute("translatable") == "false" -> null
                element.tagName == "string" -> Entry(name, element.textContent, plural = false)
                element.tagName == "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    Entry(name, (0 until items.length).joinToString("\n") { items.item(it).textContent }, plural = true)
                }
                else -> null
            }
        }
    }

    private fun placeholders(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.value }.toSet()

    private fun duplicates(xml: String): Set<String> =
        parse(xml).groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys

    private val english get() = load("values")
    private val french get() = load("values-fr")

    @Nested
    inner class HappyPath {

        @Test
        fun `chaque texte traduisible anglais existe en français`() {
            assertThat(french.keys).containsAll(english.keys)
        }

        @Test
        fun `chaque texte français existe en anglais`() {
            assertThat(english.keys).containsAll(french.keys)
        }

        @Test
        fun `les paramètres de format sont les mêmes en anglais et en français`() {
            for ((name, entry) in english) {
                assertThat(placeholders(french.getValue(name).text)).describedAs(name).isEqualTo(placeholders(entry.text))
            }
        }

        @Test
        fun `chaque réglage a un résumé et une explication non vides dans les deux langues`() {
            val infos = english.keys.filter { it.startsWith("pref_") && it.endsWith("_info") }
            assertThat(infos).isNotEmpty()
            for (languageTexts in listOf(english, french)) {
                for (info in infos) {
                    assertThat(languageTexts[info]?.text?.trim()).describedAs(info).isNotEmpty()
                    val summary = info.removeSuffix("_info") + "_summary"
                    assertThat(languageTexts[summary]?.text?.trim()).describedAs(summary).isNotEmpty()
                }
            }
        }

        @Test
        fun `tous les réglages de l'écran ont une explication`() {
            val expected = listOf(
                "ui_language", "layout", "adaptation", "row_height", "bottom_margin", "wide_space", "haptics", "languages",
                "preferred_language", "autocommit", "prudence", "french_typography", "double_space", "contacts", "next_word",
                "section_learned", "refusals", "touch_zones", "section_stats", "heatmap" "section_dictionaries", "dictionaries_online",
            )
            assertThat(english.keys).contains(*expected.map { "pref_${it}_info" }.toTypedArray())
        }

        @Test
        fun `les noms des langues sont définis une seule fois, sans traduction`() {
            assertThat(english).doesNotContainKeys("language_name_fr", "language_name_en")
            assertThat(french).doesNotContainKeys("language_name_fr", "language_name_en")
            val source = resources.resolve("values/strings_help.xml").readText()
            assertThat(source).contains("name=\"language_name_fr\" translatable=\"false\"")
            assertThat(source).contains("name=\"language_name_en\" translatable=\"false\"")
        }

        @Test
        fun `la description du bouton d'information porte le nom du réglage`() {
            assertThat(placeholders(english.getValue("pref_info_description").text)).containsExactly("%1\$s")
            assertThat(placeholders(french.getValue("pref_info_description").text)).containsExactly("%1\$s")
        }

        @Test
        fun `aucun nom de texte n'est défini deux fois dans une même langue`() {
            for (folder in listOf("values", "values-fr")) {
                val files = resources.resolve(folder).listFiles { file -> file.name.startsWith("strings") }.orEmpty()
                val names = files.flatMap { parse(it.readText()).map(Entry::name) }
                assertThat(names).describedAs(folder).doesNotHaveDuplicates()
            }
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un XML mal formé est refusé`() {
            assertThatThrownBy { parse("<resources><string name=\"a\">x</resources>") }.isInstanceOf(Exception::class.java)
        }

        @Test
        fun `un texte non traduisible est ignoré`() {
            val entries = parse("<resources><string name=\"a\" translatable=\"false\">x</string><string name=\"b\">y</string></resources>")

            assertThat(entries.map(Entry::name)).containsExactly("b")
        }

        @Test
        fun `un élément qui n'est ni un texte ni un pluriel est ignoré`() {
            assertThat(parse("<resources><color name=\"c\">#fff</color><string-array name=\"s\"/></resources>")).isEmpty()
        }

        @Test
        fun `un texte défini deux fois est signalé`() {
            assertThat(duplicates("<resources><string name=\"a\">x</string><string name=\"a\">y</string></resources>")).containsExactly("a")
        }

        @Test
        fun `un dossier de ressources absent ne contient aucun texte`() {
            assertThat(load("values-inexistant")).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un texte sans paramètre n'a aucun paramètre de format`() {
            assertThat(placeholders("Plus d'informations")).isEmpty()
        }

        @Test
        fun `les paramètres numérotés et typés sont reconnus`() {
            assertThat(placeholders("%1\$d sur %2\$s, puis %1\$d")).containsExactlyInAnyOrder("%1\$d", "%2\$s")
        }

        @Test
        fun `un paramètre non numéroté est reconnu`() {
            assertThat(placeholders("%s et %d")).containsExactlyInAnyOrder("%s", "%d")
        }

        @Test
        fun `les éléments d'un pluriel sont réunis`() {
            val entry = parse("<resources><plurals name=\"p\"><item quantity=\"one\">%1\$d mot</item><item quantity=\"other\">%1\$d mots</item></plurals></resources>").single()

            assertThat(entry.plural).isTrue()
            assertThat(entry.text).isEqualTo("%1\$d mot\n%1\$d mots")
        }

        @Test
        fun `une entité XML est résolue dans le texte`() {
            val entry = parse("<resources><string name=\"a\">a&#160;?</string></resources>").single()

            assertThat(entry.text).isEqualTo("a ?")
        }

        @Test
        fun `un fichier de ressources vide ne contient aucun texte`() {
            assertThat(parse("<resources/>")).isEmpty()
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("%(\\d+\\$)?[sd]")
    }
}
