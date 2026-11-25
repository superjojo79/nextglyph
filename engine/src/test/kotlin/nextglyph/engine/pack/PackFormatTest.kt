package nextglyph.engine.pack

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PackFormatTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `liste les cinq fichiers de données dans l'ordre puis la notice`() {
            assertThat(PackFormat.DATA_FILES)
                .containsExactly("fr-lexique.nglx", "fr-dicollecte.nglx", "en.nglx", "fr.ngrm", "en.ngrm")
            assertThat(PackFormat.PACKED_FILES).containsExactlyElementsOf(PackFormat.DATA_FILES + "NOTICE.txt")
        }

        @Test
        fun `nomme le modèle de mot suivant d'une langue`() {
            assertThat(PackFormat.nextWordFileName("fr")).isEqualTo("fr.ngrm")
        }

        @Test
        fun `fixe les constantes du format`() {
            assertThat(PackFormat.FORMAT).isEqualTo(1)
            assertThat(PackFormat.MANIFEST).isEqualTo("manifest.txt")
            assertThat(PackFormat.SIGNATURE).isEqualTo("manifest.sig")
            assertThat(PackFormat.EXTENSION).isEqualTo(".ngpk")
            assertThat(PackFormat.MAX_ENTRY_BYTES).isEqualTo(64L * 1024 * 1024)
            assertThat(PackFormat.MAX_TOTAL_BYTES).isEqualTo(160L * 1024 * 1024)
            assertThat(PackFormat.MAX_ENTRIES).isEqualTo(16)
            assertThat(PackLimits()).isEqualTo(PackLimits(PackFormat.MAX_ENTRY_BYTES, PackFormat.MAX_TOTAL_BYTES, PackFormat.MAX_ENTRIES))
        }

        @Test
        fun `calcule le SHA-256 d'un fichier`(@TempDir dir: File) {
            val file = File(dir, "a").apply { writeText("abc") }

            assertThat(PackFormat.sha256(file)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `porte le motif et la cause de l'exception`() {
            val cause = RuntimeException("x")

            val e = PackException(PackException.Reason.IO, "message", cause)

            assertThat(e.reason).isEqualTo(PackException.Reason.IO)
            assertThat(e).hasMessage("message").hasCause(cause)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `calcule le SHA-256 d'un fichier vide`(@TempDir dir: File) {
            assertThat(PackFormat.sha256(File(dir, "vide").apply { writeBytes(ByteArray(0)) }))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }

        @Test
        fun `calcule le SHA-256 d'un fichier plus grand que le tampon`(@TempDir dir: File) {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }

            assertThat(PackFormat.sha256(File(dir, "gros").apply { writeBytes(bytes) })).isEqualTo(PackFixtures.sha256(bytes))
        }
    }
}
