package nextglyph.tools.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class FetchDataCliTest {

    private val content = "données".toByteArray()
    private val source = sourceFor("lexique", content)

    private class Captured {
        private val outBytes = ByteArrayOutputStream()
        private val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8)
        val err = PrintStream(errBytes, true, Charsets.UTF_8)
        val stdout: String get() = outBytes.toString(Charsets.UTF_8)
        val stderr: String get() = errBytes.toString(Charsets.UTF_8)
    }

    private fun manifestFor(dir: Path, src: DataSource): Path {
        val json = """
            {"sources":[{"id":"${src.id}","language":"fr","need":"a","url":"${src.url}",
            "sha256":"${src.sha256}","size":${src.sizeBytes},"license":"CC BY 4.0"}]}
        """.trimIndent()
        return Files.writeString(dir.resolve("sources.json"), json)
    }

    private fun run(captured: Captured, downloader: Downloader, vararg args: String): Int =
        FetchDataCli.run(arrayOf(*args), downloader, captured.out, captured.err)

    @Nested
    inner class HappyPath {

        @Test
        fun `télécharge et rend le code de sortie 0`(@TempDir dir: Path) {
            val manifest = manifestFor(dir, source)
            val out = dir.resolve("data")
            val captured = Captured()

            val code = run(captured, FakeDownloader(mapOf(source.url to content)), manifest.toString(), out.toString())

            assertThat(code).isEqualTo(0)
            assertThat(out.resolve("lexique.bin")).exists()
            assertThat(captured.stdout).contains("lexique : téléchargement", "1 fichier(s) prêt(s)", "1 téléchargé(s)")
            assertThat(captured.stderr).isEmpty()
        }

        @Test
        fun `ne retélécharge pas à la seconde exécution`(@TempDir dir: Path) {
            val manifest = manifestFor(dir, source)
            val out = dir.resolve("data")
            run(Captured(), FakeDownloader(mapOf(source.url to content)), manifest.toString(), out.toString())
            val second = FakeDownloader(emptyMap())
            val captured = Captured()

            val code = run(captured, second, manifest.toString(), out.toString())

            assertThat(code).isEqualTo(0)
            assertThat(second.calls).isEmpty()
            assertThat(captured.stdout).contains("déjà présent et valide", "0 téléchargé(s)")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rend 1 avec un message si le manifeste est introuvable`(@TempDir dir: Path) {
            val captured = Captured()

            val code = run(captured, FakeDownloader(emptyMap()), dir.resolve("absent.json").toString(), dir.toString())

            assertThat(code).isEqualTo(1)
            assertThat(captured.stderr).contains("Manifeste introuvable", "absent.json")
        }

        @Test
        fun `rend 1 avec le détail si le manifeste est invalide`(@TempDir dir: Path) {
            val manifest = Files.writeString(dir.resolve("sources.json"), "{\"sources\": []}")
            val captured = Captured()

            val code = run(captured, FakeDownloader(emptyMap()), manifest.toString(), dir.toString())

            assertThat(code).isEqualTo(1)
            assertThat(captured.stderr).contains("Manifeste invalide", "sources.json", "aucune source")
        }

        @Test
        fun `rend 1 et affiche les échecs si l'empreinte ne correspond pas`(@TempDir dir: Path) {
            val manifest = manifestFor(dir, source)
            val captured = Captured()

            val code = run(
                captured,
                FakeDownloader(mapOf(source.url to "autre chose".toByteArray())),
                manifest.toString(),
                dir.resolve("data").toString(),
            )

            assertThat(code).isEqualTo(1)
            assertThat(captured.stderr).contains("Échec de la récupération", "lexique")
            assertThat(captured.stdout).doesNotContain("prêt(s)")
        }

        @Test
        fun `rend 2 avec l'usage si les arguments sont absents ou en trop`(@TempDir dir: Path) {
            val none = Captured()
            val three = Captured()

            assertThat(run(none, FakeDownloader(emptyMap()))).isEqualTo(2)
            assertThat(run(three, FakeDownloader(emptyMap()), "a", "b", "c")).isEqualTo(2)
            assertThat(none.stderr).contains("Usage")
            assertThat(three.stderr).contains("Usage")
            assertThat(dir).isEmptyDirectory()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un seul argument est un usage incorrect`() {
            val captured = Captured()

            assertThat(run(captured, FakeDownloader(emptyMap()), "sources.json")).isEqualTo(2)
        }

        @Test
        fun `les codes de sortie sont distincts`() {
            assertThat(setOf(FetchDataCli.EXIT_OK, FetchDataCli.EXIT_FAILURE, FetchDataCli.EXIT_USAGE)).hasSize(3)
            assertThat(FetchDataCli.EXIT_OK).isEqualTo(0)
        }

        @Test
        fun `un manifeste qui est un dossier est introuvable`(@TempDir dir: Path) {
            val captured = Captured()

            assertThat(run(captured, FakeDownloader(emptyMap()), dir.toString(), dir.toString())).isEqualTo(1)
            assertThat(captured.stderr).contains("introuvable")
        }
    }
}
