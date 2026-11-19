package nextglyph.tools.lexicons

import nextglyph.tools.wordlists.WordEntry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class PrototypeExportTest {

    private val entries = listOf(WordEntry("a", 5), WordEntry("école", 90), WordEntry("l'", 40), WordEntry("b", 5))

    private class Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val outStream = PrintStream(out, true, Charsets.UTF_8)
        val errStream = PrintStream(err, true, Charsets.UTF_8)
        val stdout: String get() = out.toString(Charsets.UTF_8)
        val stderr: String get() = err.toString(Charsets.UTF_8)
    }

    private fun lists(dir: Path, fr: List<String> = listOf("de\t100", "école\t50", "l'\t20"), en: List<String> = listOf("the\t9")): Path {
        val lists = Files.createDirectories(dir.resolve("lists"))
        Files.write(lists.resolve("fr.tsv"), fr)
        Files.write(lists.resolve("en.tsv"), en)
        return lists
    }

    private fun run(vararg args: String, captured: Captured = Captured()) =
        ExportPrototypeDataCli.run(arrayOf(*args), captured.outStream, captured.errStream)

    @Nested
    inner class HappyPath {
        @Test
        fun `JSON - formes triées par fréquence décroissante puis forme, une par ligne`() {
            assertThat(PrototypeExport.toJson(entries, 10)).isEqualTo(
                "[\n{\"w\":\"école\",\"f\":90},\n{\"w\":\"l'\",\"f\":40},\n{\"w\":\"a\",\"f\":5},\n{\"w\":\"b\",\"f\":5}\n]\n",
            )
        }

        @Test
        fun `JSON - limité aux N plus fréquentes`() {
            assertThat(PrototypeExport.toJson(entries, 2)).isEqualTo("[\n{\"w\":\"école\",\"f\":90},\n{\"w\":\"l'\",\"f\":40}\n]\n")
        }

        @Test
        fun `échappements - guillemet, antislash, contrôles, le reste tel quel`() {
            assertThat(PrototypeExport.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"")
            assertThat(PrototypeExport.quote("t\tn\nr\r\u0001\u001f")).isEqualTo("\"t\\u0009n\\u000a" + "r\\u000d\\u0001\\u001f\"")
            assertThat(PrototypeExport.quote("œuvre l'été </script> é")).isEqualTo("\"œuvre l'été </script> é\"")
        }

        @Test
        fun `commande - écrit fr json et en json avec la limite`(@TempDir dir: Path) {
            val lists = lists(dir)
            val captured = Captured()

            val code = run(lists.toString(), dir.resolve("data").toString(), "2", captured = captured)

            assertThat(code).isZero()
            assertThat(Files.readString(dir.resolve("data/fr.json")))
                .isEqualTo("[\n{\"w\":\"de\",\"f\":100},\n{\"w\":\"école\",\"f\":50}\n]\n")
            assertThat(Files.readString(dir.resolve("data/en.json"))).isEqualTo("[\n{\"w\":\"the\",\"f\":9}\n]\n")
            assertThat(captured.stdout).contains("fr.json : 2 formes sur 3", "en.json : 1 formes sur 1")
        }

        @Test
        fun `commande - limite par défaut de 100000`(@TempDir dir: Path) {
            val lists = lists(dir)

            val code = run(lists.toString(), dir.resolve("data").toString())

            assertThat(code).isZero()
            assertThat(PrototypeExport.DEFAULT_LIMIT).isEqualTo(100_000)
            assertThat(Files.readString(dir.resolve("data/fr.json"))).contains("\"de\"", "\"école\"", "\"l'\"")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `JSON - N négatif refusé`() {
            assertThatThrownBy { PrototypeExport.toJson(entries, -1) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("-1")
        }

        @Test
        fun `commande - mauvais nombre d'arguments`() {
            val captured = Captured()

            assertThat(run("a", captured = captured)).isEqualTo(1)
            assertThat(captured.stderr).contains("Usage : exportPrototypeData")
            assertThat(run("a", "b", "3", "4")).isEqualTo(1)
        }

        @Test
        fun `commande - N non numérique ou négatif`() {
            val captured = Captured()

            assertThat(run("a", "b", "beaucoup", captured = captured)).isEqualTo(1)
            assertThat(captured.stderr).contains("N invalide « beaucoup »")
            assertThat(run("a", "b", "-5")).isEqualTo(1)
        }

        @Test
        fun `commande - ligne mal formée`(@TempDir dir: Path) {
            val lists = lists(dir, fr = listOf("de\t100", "cassée"))
            val captured = Captured()

            assertThat(run(lists.toString(), dir.resolve("data").toString(), captured = captured)).isEqualTo(1)

            assertThat(captured.stderr).contains("fr.tsv, ligne 2")
        }

        @Test
        fun `commande - fréquence invalide`(@TempDir dir: Path) {
            val lists = lists(dir, en = listOf("the\tx"))
            val captured = Captured()

            assertThat(run(lists.toString(), dir.resolve("data").toString(), captured = captured)).isEqualTo(1)

            assertThat(captured.stderr).contains("en.tsv, ligne 1", "fréquence invalide")
        }

        @Test
        fun `commande - liste absente`(@TempDir dir: Path) {
            val captured = Captured()

            assertThat(run(dir.toString(), dir.resolve("data").toString(), captured = captured)).isEqualTo(1)

            assertThat(captured.stderr).contains("Lecture ou écriture impossible")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `JSON - N égal à 0 ou aucune entrée - tableau vide`() {
            assertThat(PrototypeExport.toJson(entries, 0)).isEqualTo("[]\n")
            assertThat(PrototypeExport.toJson(emptyList(), 5)).isEqualTo("[]\n")
        }

        @Test
        fun `JSON - N supérieur au nombre de formes - toutes les formes`() {
            val json = PrototypeExport.toJson(entries, 1_000_000)

            assertThat(json.lines().filter { it.startsWith("{") }).hasSize(4)
        }

        @Test
        fun `JSON - N égal au nombre de formes et N inférieur de un`() {
            assertThat(PrototypeExport.toJson(entries, 4).lines().count { it.startsWith("{") }).isEqualTo(4)
            assertThat(PrototypeExport.toJson(entries, 3).lines().count { it.startsWith("{") }).isEqualTo(3)
        }

        @Test
        fun `échappements - bornes des caractères de contrôle`() {
            assertThat(PrototypeExport.quote("\u001f \u007f")).isEqualTo("\"\\u001f \u007f\"")
            assertThat(PrototypeExport.quote("")).isEqualTo("\"\"")
        }

        @Test
        fun `commande - N égal à 0 - tableaux vides`(@TempDir dir: Path) {
            val lists = lists(dir)

            val code = run(lists.toString(), dir.resolve("data").toString(), "0")

            assertThat(code).isZero()
            assertThat(Files.readString(dir.resolve("data/fr.json"))).isEqualTo("[]\n")
        }

        @Test
        fun `commande - N supérieur au nombre de formes - compteur borné`(@TempDir dir: Path) {
            val lists = lists(dir)
            val captured = Captured()

            run(lists.toString(), dir.resolve("data").toString(), "500", captured = captured)

            assertThat(captured.stdout).contains("fr.json : 3 formes sur 3")
        }

        @Test
        fun `commande - listes vides`(@TempDir dir: Path) {
            val lists = lists(dir, fr = emptyList(), en = emptyList())

            assertThat(run(lists.toString(), dir.resolve("data").toString())).isZero()
            assertThat(Files.readString(dir.resolve("data/en.json"))).isEqualTo("[]\n")
        }

        @Test
        fun `le JSON produit est relu par un analyseur JSON standard`() {
            val json = PrototypeExport.toJson(listOf(WordEntry("a\"b\\c\td", 1), WordEntry("é", 2)), 10)

            val parsed = com.google.gson.JsonParser.parseString(json).asJsonArray
            assertThat(parsed.map { it.asJsonObject["w"].asString }).containsExactly("é", "a\"b\\c\td")
        }
    }
}
