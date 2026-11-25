package nextglyph.engine.pack

import nextglyph.engine.lexicon.LexiconLanguage
import java.io.File
import java.security.MessageDigest

/**
 * Constantes du paquet de dictionnaires `.ngpk` (archive ZIP signée, spec §10 et `docs/PUBLISHING.md`).
 *
 * Le paquet contient `manifest.txt`, `manifest.sig`, les fichiers de données (lexiques `.nglx` et modèles
 * de mot suivant `.ngrm`, ceux que `:tools:buildLexicons` produit) et `NOTICE.txt`.
 */
object PackFormat {

    /** Version du format du manifeste (ligne `format=`). */
    const val FORMAT: Int = 1

    /** Nom du manifeste dans l'archive. */
    const val MANIFEST: String = "manifest.txt"

    /** Nom de la signature du manifeste dans l'archive. */
    const val SIGNATURE: String = "manifest.sig"

    /** Nom des attributions des données dans l'archive. */
    const val NOTICE: String = "NOTICE.txt"

    /** Extension d'un paquet. */
    const val EXTENSION: String = ".ngpk"

    /** Nom du modèle de mot suivant d'une langue (`fr.ngrm`). */
    fun nextWordFileName(language: String): String = "$language.ngrm"

    /** Fichiers de données : lexiques (ordre de [LexiconLanguage.ALL_FILES]) puis modèles de mot suivant. */
    val DATA_FILES: List<String> =
        LexiconLanguage.ALL_FILES.map { it.name } + LexiconLanguage.SUPPORTED.map(::nextWordFileName)

    /** Fichiers que le manifeste liste, dans l'ordre du manifeste et de l'archive : les données puis [NOTICE]. */
    val PACKED_FILES: List<String> = DATA_FILES + NOTICE

    /** Taille décompressée maximale d'une entrée. */
    const val MAX_ENTRY_BYTES: Long = 64L shl 20

    /** Taille décompressée maximale de l'ensemble des entrées. */
    const val MAX_TOTAL_BYTES: Long = 160L shl 20

    /** Nombre maximal d'entrées de l'archive. */
    const val MAX_ENTRIES: Int = 16

    /** Taille maximale du manifeste (il fait quelques centaines d'octets). */
    internal const val MAX_MANIFEST_BYTES: Long = 64L shl 10

    /** Empreinte SHA-256 du contenu de [file], en hexadécimal minuscule. */
    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 shl 10)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Plafonds appliqués à la lecture d'un paquet ; ceux de [PackFormat] par défaut (réduits dans les tests). */
data class PackLimits(
    val maxEntryBytes: Long = PackFormat.MAX_ENTRY_BYTES,
    val maxTotalBytes: Long = PackFormat.MAX_TOTAL_BYTES,
    val maxEntries: Int = PackFormat.MAX_ENTRIES,
)

/** Cause du refus d'un paquet. */
class PackException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** La signature du manifeste n'est valide pour aucune clé de confiance. */
        SIGNATURE,

        /** Les formats binaires du paquet ne sont pas ceux de ce moteur. */
        INCOMPATIBLE,

        /** Archive, manifeste ou fichier mal formé, ou ne correspondant pas au manifeste. */
        CORRUPT,

        /** La version du paquet n'est pas postérieure à celle installée. */
        OUTDATED,

        /** Un plafond de taille ou de nombre d'entrées est dépassé. */
        TOO_LARGE,

        /** Lecture ou écriture impossible. */
        IO,
    }
}
