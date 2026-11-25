package nextglyph.engine.pack

import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconFormat
import nextglyph.engine.ngram.NgramFixtures
import nextglyph.engine.ngram.NgramFormat
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Paquets de test : petits fichiers valides, manifeste signé, archive ZIP. */
object PackFixtures {

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Contenu valide de chaque fichier de [PackFormat.PACKED_FILES]. */
    fun files(): Map<String, ByteArray> = buildMap {
        for (name in PackFormat.DATA_FILES) {
            put(
                name,
                if (name.endsWith(".nglx")) LexiconFixtures.builder(LexiconFixtures.SAMPLE, "fr").toByteArray() else NgramFixtures.bytes(),
            )
        }
        put(PackFormat.NOTICE, "attributions".toByteArray())
    }

    fun manifest(
        version: Long,
        files: Map<String, ByteArray> = files(),
        lexiconFormat: Int = LexiconFormat.VERSION,
        ngramFormat: Int = NgramFormat.VERSION,
    ): PackManifest = PackManifest(
        version, lexiconFormat, ngramFormat,
        files.map { (name, bytes) -> PackEntry(name, bytes.size.toLong(), sha256(bytes)) },
    )

    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Tailles décompressées des entrées d'une archive. */
    fun sizes(zip: ByteArray): List<Long> {
        val result = ArrayList<Long>()
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            while (input.nextEntry != null) result += input.readBytes().size.toLong()
        }
        return result
    }

    /** Paquet signé : [version], fichiers (éventuellement altérés) ; le manifeste décrit [declared]. */
    fun pack(
        version: Long,
        key: KeyPair,
        files: Map<String, ByteArray> = files(),
        declared: Map<String, ByteArray> = files,
        lexiconFormat: Int = LexiconFormat.VERSION,
        ngramFormat: Int = NgramFormat.VERSION,
        extra: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val manifest = manifest(version, declared, lexiconFormat, ngramFormat).serialize()
        val signature = PackSignature.sign(manifest, key.private)
        return zip(listOf(PackFormat.MANIFEST to manifest, PackFormat.SIGNATURE to signature) + files.toList() + extra)
    }
}
