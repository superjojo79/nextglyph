package nextglyph.tools.data

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** SHA-256 en hexadécimal minuscule, calculé indépendamment du code testé. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Construit une source cohérente avec [content] (taille et empreinte déduites). */
fun sourceFor(
    id: String,
    content: ByteArray,
    fileName: String = "$id.bin",
    needs: Set<Need> = setOf(Need.VOCABULARY),
): DataSource = DataSource(
    id = id,
    language = "fr",
    needs = needs,
    url = "https://example.org/data/$fileName",
    sha256 = sha256Hex(content),
    sizeBytes = content.size.toLong(),
    license = "CC BY 4.0",
    fileName = fileName,
)

/**
 * Téléchargeur de test : sert des contenus en mémoire (clé = URL) et mémorise les appels.
 * Écrit en CREATE_NEW pour détecter un fichier `.part` résiduel qui n'aurait pas été supprimé.
 */
class FakeDownloader(private val contents: Map<String, ByteArray>) : Downloader {

    val calls = mutableListOf<String>()

    override fun download(url: String, target: Path) {
        calls += url
        val bytes = contents[url] ?: throw IOException("HTTP 404")
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
    }
}
