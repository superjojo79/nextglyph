package nextglyph.tools.data

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Au moins un fichier n'a pu être obtenu ou vérifié ; le message liste chaque échec. */
class DataFetchException(message: String) : Exception(message)

sealed interface FetchOutcome {
    val source: DataSource

    /** Fichier déjà présent et valide : rien n'a été téléchargé. */
    data class AlreadyPresent(override val source: DataSource) : FetchOutcome

    data class Downloaded(override val source: DataSource) : FetchOutcome
}

/**
 * Garantit la présence, dans `outputDir`, de chaque fichier du manifeste avec la bonne empreinte.
 * Un fichier valide n'est pas retéléchargé ; le téléchargement passe par un fichier `.part`
 * renommé seulement après vérification, donc `outputDir` ne contient jamais de fichier douteux.
 */
class DataFetcher(private val downloader: Downloader, private val log: (String) -> Unit = {}) {

    fun fetch(sources: List<DataSource>, outputDir: Path): List<FetchOutcome> {
        Files.createDirectories(outputDir)
        val outcomes = mutableListOf<FetchOutcome>()
        val failures = mutableListOf<String>()
        for (source in sources) {
            try {
                outcomes += fetchOne(source, outputDir)
            } catch (e: DataFetchException) {
                failures += e.message.orEmpty()
            }
        }
        if (failures.isNotEmpty()) {
            throw DataFetchException(
                failures.joinToString(separator = "\n", prefix = "Échec de la récupération :\n") { "- $it" },
            )
        }
        return outcomes
    }

    private fun fetchOne(source: DataSource, outputDir: Path): FetchOutcome {
        val target = outputDir.resolve(source.fileName)
        if (!FileVerifier.verify(target, source).needsDownload) {
            log("${source.id} : déjà présent et valide")
            return FetchOutcome.AlreadyPresent(source)
        }
        log("${source.id} : téléchargement de ${source.url}")
        val partial = outputDir.resolve(source.fileName + PARTIAL_SUFFIX)
        try {
            Files.deleteIfExists(partial)
            try {
                downloader.download(source.url, partial)
            } catch (e: IOException) {
                throw DataFetchException("${source.id} : téléchargement impossible (${e.message}) depuis ${source.url}")
            }
            val check = FileVerifier.verify(partial, source)
            if (check.needsDownload) throw DataFetchException(describeMismatch(source, check))
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
        return FetchOutcome.Downloaded(source)
    }

    private fun describeMismatch(source: DataSource, check: FileVerification): String {
        val detail = when (check.state) {
            FileState.ABSENT -> "aucun fichier reçu"
            FileState.EMPTY -> "fichier reçu vide"
            FileState.WRONG_SIZE -> "taille différente de celle attendue (${source.sizeBytes} octets)"
            else -> "SHA-256 attendu ${source.sha256}, obtenu ${check.actualSha256}"
        }
        return "${source.id} : contenu téléchargé refusé, $detail. " +
            "La source a peut-être changé : vérifier sa licence puis mettre à jour tools/data/sources.json."
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
    }
}
