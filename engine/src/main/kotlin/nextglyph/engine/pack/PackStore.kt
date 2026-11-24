package nextglyph.engine.pack

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconFile
import nextglyph.engine.ngram.NgramModel
import java.io.EOFException
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.PublicKey
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Un paquet installé : son manifeste et le dossier qui contient ses fichiers. */
data class InstalledPack(val manifest: PackManifest, val directory: File) {
    val version: Long get() = manifest.version

    /** Fichier [name] du paquet (existe pour les noms de [PackFormat.PACKED_FILES]). */
    fun file(name: String): File = File(directory, name)
}

/**
 * Paquet de dictionnaires installé dans [root] : un dossier `<version>` par paquet et un fichier `current`
 * qui contient la version courante en décimal. Toute installation est vérifiée de bout en bout avant de
 * devenir visible ; un échec laisse l'état précédent intact.
 *
 * @param keys clés publiques dont l'une doit avoir signé le manifeste.
 * @param limits plafonds de lecture de l'archive.
 */
class PackStore(
    private val root: File,
    private val keys: List<PublicKey> = PackKeys.trusted(),
    private val limits: PackLimits = PackLimits(),
) {

    /**
     * Paquet installé, ou `null` (aucun, pointeur invalide, dossier ou fichier absent ou de taille différente
     * du manifeste, formats incompatibles) : le clavier retombe alors sur les dictionnaires embarqués.
     * Ne lève jamais.
     */
    fun current(): InstalledPack? = try {
        val version = File(root, POINTER).readText(Charsets.UTF_8).trim().toLongOrNull()
        val directory = File(root, version.toString())
        val manifest = PackManifest.parse(File(directory, PackFormat.MANIFEST).readBytes())
        val complete = manifest.version == version && manifest.compatible &&
            manifest.files.all { File(directory, it.name).let { file -> file.isFile && file.length() == it.size } }
        if (complete) InstalledPack(manifest, directory) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Installe le paquet lu dans [input] (ZIP) sans le fermer.
     *
     * Étapes : lecture en flux vers un dossier de préparation avec plafonds, signature, manifeste,
     * compatibilité, version plus récente, taille et SHA-256 de chaque fichier, chargement à l'essai,
     * puis renommage en `<version>`, pointeur écrit de façon atomique et suppression des anciennes versions.
     *
     * @throws PackException si le paquet est refusé ou si le disque échoue ; l'état précédent est conservé.
     */
    fun install(input: InputStream): InstalledPack {
        val staging = File(root, "$STAGING_PREFIX${UUID.randomUUID()}")
        var moved: File? = null
        try {
            Files.createDirectories(staging.toPath())
            extract(input, staging)
            val manifest = verify(staging)
            val target = File(root, manifest.version.toString())
            deleteRecursively(target)
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            moved = target
            writePointer(manifest.version)
            moved = null
            removeOthers(keep = target)
            return InstalledPack(manifest, target)
        } catch (e: IOException) {
            throw PackException(PackException.Reason.IO, "Installation impossible : ${e.message}", e)
        } finally {
            deleteRecursively(staging)
            moved?.let(::deleteRecursively)
        }
    }

    /** Efface le pointeur puis tous les dossiers de paquet : retour aux dictionnaires embarqués. */
    fun reset() {
        File(root, POINTER).delete()
        removeOthers(keep = null)
    }

    /**
     * Vérifie le manifeste distant ([manifest], [signature]) : signature, format, compatibilité.
     *
     * @return le manifeste s'il est plus récent que [current] (ou s'il n'y a pas de paquet installé), sinon `null`.
     * @throws PackException (`SIGNATURE`, `CORRUPT`, `INCOMPATIBLE`) si le manifeste est refusé.
     */
    fun checkUpdate(manifest: ByteArray, signature: ByteArray): PackManifest? {
        val parsed = checked(manifest, signature)
        val installed = current()
        return if (installed == null || parsed.version > installed.version) parsed else null
    }

    private fun checked(manifest: ByteArray, signature: ByteArray): PackManifest {
        if (!PackSignature.verify(manifest, signature, keys)) {
            throw PackException(PackException.Reason.SIGNATURE, "Signature du paquet invalide : paquet non reconnu.")
        }
        val parsed = PackManifest.parse(manifest)
        if (!parsed.compatible) {
            throw PackException(PackException.Reason.INCOMPATIBLE, "Paquet incompatible avec cette version du clavier.")
        }
        return parsed
    }

    private fun extract(input: InputStream, staging: File) {
        val allowed = PackFormat.PACKED_FILES.toSet() + PackFormat.MANIFEST + PackFormat.SIGNATURE
        val seen = HashSet<String>()
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            ZipInputStream(object : FilterInputStream(input) {
                override fun close() = Unit
            }).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (seen.size >= limits.maxEntries) throw tooLarge("plus de ${limits.maxEntries} entrées")
                    if (entry.isDirectory || entry.name !in allowed) {
                        throw corrupt("entrée inattendue « ${entry.name} »")
                    }
                    if (!seen.add(entry.name)) throw corrupt("entrée « ${entry.name} » en double")
                    var size = 0L
                    FileOutputStream(File(staging, entry.name)).use { out ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            size += read
                            total += read
                            if (size > limits.maxEntryBytes) throw tooLarge("« ${entry.name} » dépasse ${limits.maxEntryBytes} octets")
                            if (total > limits.maxTotalBytes) throw tooLarge("le paquet dépasse ${limits.maxTotalBytes} octets")
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw corrupt("archive illisible (${e.message})", e)
        } catch (e: EOFException) {
            throw corrupt("archive tronquée", e)
        }
    }

    private fun verify(staging: File): PackManifest {
        val manifestFile = File(staging, PackFormat.MANIFEST)
        val signatureFile = File(staging, PackFormat.SIGNATURE)
        if (!manifestFile.isFile || manifestFile.length() > PackFormat.MAX_MANIFEST_BYTES) throw corrupt("manifeste absent ou trop long")
        if (!signatureFile.isFile) {
            throw PackException(PackException.Reason.SIGNATURE, "Signature du paquet absente.")
        }
        val manifest = checked(manifestFile.readBytes(), signatureFile.readBytes())
        val installed = current()
        if (installed != null && manifest.version <= installed.version) {
            throw PackException(
                PackException.Reason.OUTDATED,
                "Paquet ${manifest.version} pas plus récent que celui installé (${installed.version}).",
            )
        }
        for (entry in manifest.files) {
            val file = File(staging, entry.name)
            if (!file.isFile || file.length() != entry.size) throw corrupt("« ${entry.name} » absent ou de taille inattendue")
            if (PackFormat.sha256(file) != entry.sha256) throw corrupt("« ${entry.name} » ne correspond pas à son empreinte")
        }
        for (name in PackFormat.DATA_FILES) tryLoad(File(staging, name))
        return manifest
    }

    private fun tryLoad(file: File) {
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                if (file.name.endsWith(LexiconFile.EXTENSION)) Lexicon.load(buffer) else NgramModel.load(buffer)
            }
        } catch (e: RuntimeException) {
            throw corrupt("« ${file.name} » ne se charge pas (${e.message})", e)
        }
    }

    private fun writePointer(version: Long) {
        val temporary = File(root, "$POINTER.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(version.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temporary.toPath(), File(root, POINTER).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
    }

    /** Supprime les dossiers de préparation et les dossiers de version autres que [keep]. */
    private fun removeOthers(keep: File?) {
        val folders = root.listFiles(FileFilter { it.isDirectory }) ?: return
        for (folder in folders) {
            if (folder != keep && (folder.name.startsWith(STAGING_PREFIX) || folder.name.toLongOrNull() != null)) {
                deleteRecursively(folder)
            }
        }
    }

    private fun deleteRecursively(file: File) {
        file.deleteRecursively()
    }

    private fun corrupt(message: String, cause: Throwable? = null) =
        PackException(PackException.Reason.CORRUPT, "Paquet corrompu : $message.", cause)

    private fun tooLarge(message: String) = PackException(PackException.Reason.TOO_LARGE, "Paquet trop volumineux : $message.")

    private companion object {
        const val POINTER = "current"
        const val STAGING_PREFIX = ".staging-"
        const val BUFFER_BYTES = 64 shl 10
    }
}

/** Écrit un paquet `.ngpk` reproductible. */
object PackWriter {

    private val FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0)

    /**
     * Écrit l'archive dans [out] sans la fermer : entrées dans l'ordre `manifest.txt`, `manifest.sig`, puis
     * [PackFormat.PACKED_FILES], compressées (DEFLATED), datées du 1980-01-01 : mêmes entrées, mêmes octets.
     *
     * @param files fichier source de chaque nom de [PackFormat.PACKED_FILES].
     * @throws IllegalArgumentException si un des fichiers manque.
     */
    fun write(out: OutputStream, manifest: ByteArray, signature: ByteArray, files: Map<String, File>) {
        val missing = PackFormat.PACKED_FILES.filter { it !in files }
        require(missing.isEmpty()) { "Fichiers manquants dans le paquet : ${missing.joinToString()}." }
        val zip = ZipOutputStream(out)
        put(zip, PackFormat.MANIFEST) { it.write(manifest) }
        put(zip, PackFormat.SIGNATURE) { it.write(signature) }
        for (name in PackFormat.PACKED_FILES) {
            put(zip, name) { FileInputStream(files.getValue(name)).use { input -> input.copyTo(it) } }
        }
        zip.finish()
    }

    private fun put(zip: ZipOutputStream, name: String, content: (OutputStream) -> Unit) {
        val entry = ZipEntry(name)
        entry.setTimeLocal(FIXED_TIME)
        zip.putNextEntry(entry)
        content(zip)
        zip.closeEntry()
    }
}
