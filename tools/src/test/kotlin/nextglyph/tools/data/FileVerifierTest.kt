package nextglyph.tools.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class FileVerifierTest {

    private val content = "bonjour le monde".toByteArray()

    @Nested
    inner class HappyPath {

        @Test
        fun `calcule le SHA-256 connu de abc`(@TempDir dir: Path) {
            val file = Files.write(dir.resolve("abc.txt"), "abc".toByteArray())

            assertThat(FileVerifier.sha256Of(file))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        }

        @Test
        fun `un fichier de bonne taille et de bonne empreinte est valide`(@TempDir dir: Path) {
            val file = Files.write(dir.resolve("a.bin"), content)

            val result = FileVerifier.verify(file, sourceFor("a", content))

            assertThat(result.state).isEqualTo(FileState.VALID)
            assertThat(result.needsDownload).isFalse()
            assertThat(result.actualSha256).isEqualTo(sha256Hex(content))
        }

        @Test
        fun `un fichier de même taille mais d'empreinte différente est refusé`(@TempDir dir: Path) {
            val other = "BONJOUR LE MONDE".toByteArray()
            val file = Files.write(dir.resolve("a.bin"), other)

            val result = FileVerifier.verify(file, sourceFor("a", content))

            assertThat(result.state).isEqualTo(FileState.WRONG_HASH)
            assertThat(result.needsDownload).isTrue()
            assertThat(result.actualSha256).isEqualTo(sha256Hex(other))
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un fichier absent doit être téléchargé`(@TempDir dir: Path) {
            val result = FileVerifier.verify(dir.resolve("absent.bin"), sourceFor("a", content))

            assertThat(result).isEqualTo(FileVerification(FileState.ABSENT))
            assertThat(result.needsDownload).isTrue()
        }

        @Test
        fun `un dossier à la place du fichier est traité comme absent`(@TempDir dir: Path) {
            val folder = Files.createDirectory(dir.resolve("a.bin"))

            assertThat(FileVerifier.verify(folder, sourceFor("a", content)).state).isEqualTo(FileState.ABSENT)
        }

        @Test
        fun `un fichier vide doit être téléchargé`(@TempDir dir: Path) {
            val file = Files.write(dir.resolve("a.bin"), ByteArray(0))

            val result = FileVerifier.verify(file, sourceFor("a", content))

            assertThat(result).isEqualTo(FileVerification(FileState.EMPTY))
            assertThat(result.needsDownload).isTrue()
        }

        @Test
        fun `un fichier de taille différente est refusé sans calcul d'empreinte`(@TempDir dir: Path) {
            val file = Files.write(dir.resolve("a.bin"), content + 1)

            val result = FileVerifier.verify(file, sourceFor("a", content))

            assertThat(result).isEqualTo(FileVerification(FileState.WRONG_SIZE))
            assertThat(result.needsDownload).isTrue()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `le SHA-256 d'un fichier vide est celui de la chaîne vide`(@TempDir dir: Path) {
            val file = Files.write(dir.resolve("empty"), ByteArray(0))

            assertThat(FileVerifier.sha256Of(file))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }

        @ParameterizedTest
        @ValueSource(ints = [1, 65535, 65536, 65537, 131072, 200000])
        fun `l'empreinte est correcte autour de la taille du tampon de lecture`(size: Int, @TempDir dir: Path) {
            val bytes = ByteArray(size) { (it * 31 + 7).toByte() }
            val file = Files.write(dir.resolve("big.bin"), bytes)

            assertThat(FileVerifier.sha256Of(file)).isEqualTo(sha256Hex(bytes))
        }

        @Test
        fun `un fichier d'un seul octet est valide si la source le décrit`(@TempDir dir: Path) {
            val one = byteArrayOf(42)
            val file = Files.write(dir.resolve("one.bin"), one)

            assertThat(FileVerifier.verify(file, sourceFor("one", one)).state).isEqualTo(FileState.VALID)
        }

        @Test
        fun `taille attendue moins un et plus un sont refusées`(@TempDir dir: Path) {
            val source = sourceFor("a", content)
            val shorter = Files.write(dir.resolve("shorter"), content.copyOf(content.size - 1))
            val longer = Files.write(dir.resolve("longer"), content.copyOf(content.size + 1))

            assertThat(FileVerifier.verify(shorter, source).state).isEqualTo(FileState.WRONG_SIZE)
            assertThat(FileVerifier.verify(longer, source).state).isEqualTo(FileState.WRONG_SIZE)
        }
    }
}
