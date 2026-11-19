package nextglyph.tools.wordlists

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Petits fichiers d'exemple pour les tests : jamais les vraies données. */
object ArchiveFixtures {

    fun zip(path: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun bzip2(path: Path, text: String) {
        BZip2CompressorOutputStream(Files.newOutputStream(path)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun tarGz(path: Path, entries: Map<String, ByteArray>) {
        TarArchiveOutputStream(GZIPOutputStream(Files.newOutputStream(path))).use { tar ->
            for ((name, bytes) in entries) {
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    const val LEXIQUE_HEADER = "1_Mot\t2_Phono\t4_Lemme\t10_FreqMot\t11_FreqOrtho"

    fun lexiqueRow(form: String, frequency: String) = "$form\tphono\tlemme\t0\t$frequency"

    fun lexique(vararg rows: String): String = (listOf(LEXIQUE_HEADER) + rows).joinToString("\n") + "\n"

    const val DICOLLECTE_HEADER = "id\tfid\tFlexion\tLemme\tÉtiquettes"

    fun dicollecte(vararg forms: String): String =
        "# préambule\n\nCorpus :\n * Google\n\n" + DICOLLECTE_HEADER + "\n" +
            forms.joinToString("") { "0\t1\t$it\tlemme\tnom\n" }

    fun scowlFiles(vararg files: Pair<String, String>): Map<String, ByteArray> =
        files.associate { (name, content) -> name to content.toByteArray(Charsets.ISO_8859_1) }

    fun tatoeba(language: String, count: Int, prefix: String): String =
        (1..count).joinToString("") { "$it\t$language\t$prefix $it nombre.\n" }
}
