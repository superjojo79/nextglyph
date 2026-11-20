package nextglyph.tools.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DataFetcherTest {

    private val content = "contenu valide".toByteArray()
    private val source = sourceFor("lexique", content)

    private val mirror = "https://miroir.example/copie/lexique.bin"
    private val mirrored = source.copy(mirrors = listOf(mirror))

    private fun downloaderServing(vararg served: Pair<DataSource, ByteArray>) =
        FakeDownloader(served.associate { (src, bytes) -> src.url to bytes })

    private fun filesIn(dir: Path): List<String> =
        Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

    @Nested
    inner class HappyPath {

        @Test
        fun `télécharge un fichier absent puis le laisse sous son nom définitif`(@TempDir dir: Path) {
            val downloader = downloaderServing(source to content)

            val outcomes = DataFetcher(downloader).fetch(listOf(source), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(source))
            assertThat(downloader.calls).containsExactly(source.url)
            assertThat(Files.readAllBytes(dir.resolve("lexique.bin"))).isEqualTo(content)
            assertThat(filesIn(dir)).containsExactly("lexique.bin")
        }

        @Test
        fun `ne retélécharge pas un fichier présent et valide`(@TempDir dir: Path) {
            Files.write(dir.resolve("lexique.bin"), content)
            val downloader = downloaderServing()

            val outcomes = DataFetcher(downloader).fetch(listOf(source), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.AlreadyPresent(source))
            assertThat(downloader.calls).isEmpty()
        }

        @Test
        fun `une seconde exécution ne télécharge plus rien`(@TempDir dir: Path) {
            val downloader = downloaderServing(source to content)
            val fetcher = DataFetcher(downloader)

            fetcher.fetch(listOf(source), dir)
            val second = fetcher.fetch(listOf(source), dir)

            assertThat(second).containsExactly(FetchOutcome.AlreadyPresent(source))
            assertThat(downloader.calls).hasSize(1)
        }

        @Test
        fun `retélécharge un fichier présent avec une mauvaise empreinte`(@TempDir dir: Path) {
            Files.write(dir.resolve("lexique.bin"), "CONTENU VALIDE".toByteArray())
            val downloader = downloaderServing(source to content)

            val outcomes = DataFetcher(downloader).fetch(listOf(source), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(source))
            assertThat(Files.readAllBytes(dir.resolve("lexique.bin"))).isEqualTo(content)
        }

        @Test
        fun `journalise chaque décision`(@TempDir dir: Path) {
            Files.write(dir.resolve("lexique.bin"), content)
            val other = sourceFor("autre", "x".toByteArray())
            val messages = mutableListOf<String>()

            DataFetcher(downloaderServing(other to "x".toByteArray()), messages::add).fetch(listOf(source, other), dir)

            assertThat(messages).containsExactly(
                "lexique : déjà présent et valide",
                "autre : téléchargement de ${other.url}",
            )
        }

        @Test
        fun `crée le dossier de sortie manquant`(@TempDir dir: Path) {
            val nested = dir.resolve("build").resolve("data")

            DataFetcher(downloaderServing(source to content)).fetch(listOf(source), nested)

            assertThat(nested.resolve("lexique.bin")).exists()
        }

        @Test
        fun `télécharge depuis le miroir sans toucher l'url officielle`(@TempDir dir: Path) {
            val downloader = FakeDownloader(mapOf(mirror to content, source.url to content))

            val outcomes = DataFetcher(downloader).fetch(listOf(mirrored), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(mirrored))
            assertThat(downloader.calls).containsExactly(mirror)
            assertThat(Files.readAllBytes(dir.resolve("lexique.bin"))).isEqualTo(content)
        }

        @Test
        fun `se replie sur l'url officielle si le miroir est injoignable`(@TempDir dir: Path) {
            val downloader = FakeDownloader(mapOf(source.url to content))
            val messages = mutableListOf<String>()

            val outcomes = DataFetcher(downloader, messages::add).fetch(listOf(mirrored), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(mirrored))
            assertThat(downloader.calls).containsExactly(mirror, source.url)
            assertThat(messages).containsExactly(
                "lexique : téléchargement de $mirror",
                "lexique : téléchargement de ${source.url}",
            )
            assertThat(filesIn(dir)).containsExactly("lexique.bin")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `échoue clairement si l'empreinte reçue est mauvaise et ne garde rien`(@TempDir dir: Path) {
            val tampered = "CONTENU VALIDE".toByteArray()

            assertThatThrownBy { DataFetcher(downloaderServing(source to tampered)).fetch(listOf(source), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContainingAll(
                    "lexique",
                    "SHA-256 attendu ${source.sha256}",
                    "obtenu ${sha256Hex(tampered)}",
                    "sources.json",
                )
            assertThat(filesIn(dir)).isEmpty()
        }

        @Test
        fun `échoue si la taille reçue est mauvaise`(@TempDir dir: Path) {
            assertThatThrownBy { DataFetcher(downloaderServing(source to content + 1)).fetch(listOf(source), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContainingAll("lexique", "taille", "${source.sizeBytes} octets")
            assertThat(filesIn(dir)).isEmpty()
        }

        @Test
        fun `échoue si le contenu reçu est vide`(@TempDir dir: Path) {
            assertThatThrownBy { DataFetcher(downloaderServing(source to ByteArray(0))).fetch(listOf(source), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContainingAll("lexique", "vide")
            assertThat(filesIn(dir)).isEmpty()
        }

        @Test
        fun `échoue si le téléchargeur n'a rien écrit`(@TempDir dir: Path) {
            val silent = Downloader { _, _ -> }

            assertThatThrownBy { DataFetcher(silent).fetch(listOf(source), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContainingAll("lexique", "aucun fichier reçu")
        }

        @Test
        fun `échoue avec l'url et la cause si le téléchargement est impossible`(@TempDir dir: Path) {
            assertThatThrownBy { DataFetcher(downloaderServing()).fetch(listOf(source), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContainingAll("lexique", "téléchargement impossible", "HTTP 404", source.url)
        }

        @Test
        fun `un fichier invalide existant n'est pas écrasé par un téléchargement refusé`(@TempDir dir: Path) {
            val old = "ancien".toByteArray()
            Files.write(dir.resolve("lexique.bin"), old)

            assertThatThrownBy { DataFetcher(downloaderServing(source to "faux".toByteArray())).fetch(listOf(source), dir) }
                .isInstanceOf(DataFetchException::class.java)

            assertThat(Files.readAllBytes(dir.resolve("lexique.bin"))).isEqualTo(old)
            assertThat(filesIn(dir)).containsExactly("lexique.bin")
        }

        @Test
        fun `rapporte tous les échecs mais obtient quand même les sources valides`(@TempDir dir: Path) {
            val bad1 = sourceFor("mauvais-un", "un".toByteArray())
            val good = sourceFor("bon", "bon".toByteArray())
            val bad2 = sourceFor("mauvais-deux", "deux".toByteArray())
            val downloader = downloaderServing(good to "bon".toByteArray(), bad2 to "autre".toByteArray())

            assertThatThrownBy { DataFetcher(downloader).fetch(listOf(bad1, good, bad2), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContainingAll("Échec de la récupération", "- mauvais-un", "- mauvais-deux")
                .message().doesNotContain("bon :")

            assertThat(filesIn(dir)).containsExactly("bon.bin")
        }

        @Test
        fun `une exception inattendue du téléchargeur se propage et nettoie le fichier partiel`(@TempDir dir: Path) {
            val exploding = Downloader { _, target ->
                Files.writeString(target, "partiel")
                throw IllegalStateException("boum")
            }

            assertThatThrownBy { DataFetcher(exploding).fetch(listOf(source), dir) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(filesIn(dir)).isEmpty()
        }

        @Test
        fun `rapporte l'échec de chaque adresse si aucune ne convient`(@TempDir dir: Path) {
            val downloader = FakeDownloader(mapOf(source.url to "écrasé".toByteArray()))

            assertThatThrownBy { DataFetcher(downloader).fetch(listOf(mirrored), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContainingAll(
                    "téléchargement impossible (HTTP 404) depuis $mirror",
                    " ; ",
                    "contenu téléchargé depuis ${source.url} refusé",
                )
            assertThat(filesIn(dir)).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une liste de sources vide ne fait rien`(@TempDir dir: Path) {
            val downloader = downloaderServing()

            assertThat(DataFetcher(downloader).fetch(emptyList(), dir)).isEmpty()
            assertThat(downloader.calls).isEmpty()
        }

        @Test
        fun `un fichier présent mais vide est retéléchargé`(@TempDir dir: Path) {
            Files.write(dir.resolve("lexique.bin"), ByteArray(0))

            val outcomes = DataFetcher(downloaderServing(source to content)).fetch(listOf(source), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(source))
            assertThat(Files.readAllBytes(dir.resolve("lexique.bin"))).isEqualTo(content)
        }

        @Test
        fun `un fichier partiel résiduel d'une exécution interrompue est écarté`(@TempDir dir: Path) {
            Files.write(dir.resolve("lexique.bin.part"), "reste".toByteArray())

            val outcomes = DataFetcher(downloaderServing(source to content)).fetch(listOf(source), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(source))
            assertThat(filesIn(dir)).containsExactly("lexique.bin")
        }

        @Test
        fun `un fichier d'un seul octet est géré`(@TempDir dir: Path) {
            val tiny = sourceFor("tiny", byteArrayOf(1))

            val outcomes = DataFetcher(downloaderServing(tiny to byteArrayOf(1))).fetch(listOf(tiny), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(tiny))
        }

        @Test
        fun `le téléchargeur peut lever une IOException sans message`(@TempDir dir: Path) {
            val failing = Downloader { _, _ -> throw IOException() }

            assertThatThrownBy { DataFetcher(failing).fetch(listOf(source), dir) }
                .isInstanceOf(DataFetchException::class.java)
                .hasMessageContaining("téléchargement impossible")
        }

        @Test
        fun `un miroir au contenu refusé ne laisse rien avant le repli`(@TempDir dir: Path) {
            val downloader = FakeDownloader(mapOf(mirror to "faux".toByteArray(), source.url to content))

            val outcomes = DataFetcher(downloader).fetch(listOf(mirrored), dir)

            assertThat(outcomes).containsExactly(FetchOutcome.Downloaded(mirrored))
            assertThat(downloader.calls).containsExactly(mirror, source.url)
            assertThat(filesIn(dir)).containsExactly("lexique.bin")
        }

        @Test
        fun `un fichier valide n'interroge ni le miroir ni l'url officielle`(@TempDir dir: Path) {
            Files.write(dir.resolve("lexique.bin"), content)
            val downloader = FakeDownloader(emptyMap())

            assertThat(DataFetcher(downloader).fetch(listOf(mirrored), dir)).containsExactly(FetchOutcome.AlreadyPresent(mirrored))
            assertThat(downloader.calls).isEmpty()
        }
    }
}
