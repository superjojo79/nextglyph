package nextglyph.engine.pack

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class PackWriterTest {

    @TempDir
    lateinit var dir: File

    private fun sources(): Map<String, File> =
        PackFixtures.files().mapValues { (name, bytes) -> File(dir, name).apply { writeBytes(bytes) } }

    private fun write(manifest: ByteArray = "m".toByteArray(), signature: ByteArray = "s".toByteArray(), files: Map<String, File> = sources()): ByteArray {
        val out = ByteArrayOutputStream()
        PackWriter.write(out, manifest, signature, files)
        return out.toByteArray()
    }

    private fun entries(bytes: ByteArray): List<ZipEntry> {
        val result = ArrayList<ZipEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) result += zip.nextEntry ?: break
        }
        return result
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `écrit manifeste, signature puis les fichiers dans l'ordre`() {
            assertThat(entries(write()).map { it.name })
                .containsExactlyElementsOf(listOf("manifest.txt", "manifest.sig") + PackFormat.PACKED_FILES)
        }

        @Test
        fun `produit les mêmes octets à chaque écriture`() {
            val first = write()
            sources().values.forEach { it.setLastModified(System.currentTimeMillis() + 5_000_000) }

            assertThat(write()).isEqualTo(first)
        }

        @Test
        fun `date toutes les entrées du premier janvier 1980 et les compresse`() {
            val entries = entries(write())

            assertThat(entries).allSatisfy {
                assertThat(it.timeLocal.toString()).startsWith("1980-01-01T00:00")
                assertThat(it.method).isEqualTo(ZipEntry.DEFLATED)
            }
        }

        @Test
        fun `produit un paquet que le magasin installe`() {
            val key = PackFixtures.keyPair()
            val manifest = PackFixtures.manifest(7).serialize()
            val bytes = write(manifest, PackSignature.sign(manifest, key.private))

            val installed = PackStore(File(dir, "packs"), listOf(key.public)).install(ByteArrayInputStream(bytes))

            assertThat(installed.version).isEqualTo(7)
        }

        @Test
        fun `ne ferme pas le flux de sortie`() {
            var closed = false
            val out = object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                }
            }

            PackWriter.write(out, byteArrayOf(1), byteArrayOf(2), sources())

            assertThat(closed).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `refuse un fichier manquant en le nommant`() {
            assertThatThrownBy { write(files = sources() - "en.ngrm") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("en.ngrm")
        }

        @Test
        fun `refuse une liste vide`() {
            assertThatThrownBy { write(files = emptyMap()) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte des fichiers vides`() {
            val empty = PackFormat.PACKED_FILES.associateWith { File(dir, it).apply { writeBytes(ByteArray(0)) } }

            assertThat(entries(write(files = empty))).hasSize(8)
        }

        @Test
        fun `ignore les fichiers en trop`() {
            assertThat(entries(write(files = sources() + ("autre.bin" to File(dir, "en.nglx"))))).hasSize(8)
        }

        @Test
        fun `accepte un manifeste et une signature vides`() {
            assertThat(entries(write(ByteArray(0), ByteArray(0)))).hasSize(8)
        }
    }
}
