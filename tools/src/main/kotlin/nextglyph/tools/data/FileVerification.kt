package nextglyph.tools.data

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** État d'un fichier local par rapport à sa source. Seul [VALID] dispense de télécharger. */
enum class FileState { ABSENT, EMPTY, WRONG_SIZE, WRONG_HASH, VALID }

/** Résultat d'une vérification ; [actualSha256] n'est calculé que si la taille concorde. */
data class FileVerification(val state: FileState, val actualSha256: String? = null) {
    val needsDownload: Boolean get() = state != FileState.VALID
}

object FileVerifier {

    fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Compare [file] à [source] : existence, non-vacuité, taille, puis empreinte. */
    fun verify(file: Path, source: DataSource): FileVerification {
        if (!Files.isRegularFile(file)) return FileVerification(FileState.ABSENT)
        val size = Files.size(file)
        if (size == 0L) return FileVerification(FileState.EMPTY)
        if (size != source.sizeBytes) return FileVerification(FileState.WRONG_SIZE)
        val actual = sha256Of(file)
        val state = if (actual == source.sha256) FileState.VALID else FileState.WRONG_HASH
        return FileVerification(state, actual)
    }

    private const val BUFFER_SIZE = 64 * 1024
}
