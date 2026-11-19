package nextglyph.ime.learn

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Écriture atomique d'un fichier (spec §10.5) : le contenu est écrit dans un fichier temporaire voisin,
 * synchronisé sur le disque, puis renommé à la place de la cible. Une interruption (arrêt du processus,
 * batterie) laisse soit l'ancien fichier, soit le nouveau, jamais un fichier à moitié écrit.
 */
object AtomicFiles {

    /** Suffixe du fichier temporaire. */
    const val TEMP_SUFFIX: String = ".tmp"

    /** Fichier temporaire utilisé pour écrire [target]. */
    fun tempFor(target: File): File = File(target.absoluteFile.parentFile, target.name + TEMP_SUFFIX)

    /**
     * Remplace le contenu de [target] par [bytes].
     *
     * @throws IOException si l'écriture ou le renommage échoue ; [target] est alors inchangé et le
     *   fichier temporaire supprimé.
     */
    fun write(target: File, bytes: ByteArray) {
        val directory = target.absoluteFile.parentFile ?: throw IOException("Fichier sans dossier.")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Dossier impossible à créer.")
        val temp = tempFor(target)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    /** Supprime [target] et un éventuel fichier temporaire laissé par une écriture interrompue. Vrai si plus rien n'existe. */
    fun delete(target: File): Boolean {
        tempFor(target).delete()
        target.delete()
        return !target.exists() && !tempFor(target).exists()
    }
}
