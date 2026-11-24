package nextglyph.tools.pack

import nextglyph.engine.pack.PackEntry
import nextglyph.engine.pack.PackException
import nextglyph.engine.pack.PackFormat
import nextglyph.engine.pack.PackKeys
import nextglyph.engine.pack.PackManifest
import nextglyph.engine.pack.PackSignature
import nextglyph.engine.pack.PackWriter
import nextglyph.engine.pack.PackStore
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `buildDictionaryPack --lexicons <dossier> --notice <fichier> --out <dossier> [--version yyyyMMddHHmm]` :
 * assemble le paquet de dictionnaires signé (spec §10, `docs/PUBLISHING.md`) à partir des fichiers de
 * `:tools:buildLexicons` et du `NOTICE` du dépôt, et écrit dans le dossier de sortie [PACK_NAME],
 * `manifest.txt` et `manifest.sig`.
 *
 * La clé privée (PEM PKCS#8) est lue dans la variable d'environnement [KEY_VARIABLE]. Avant de terminer,
 * l'outil vérifie la signature avec les clés de confiance (`PackKeys.TRUSTED`) et installe à blanc le paquet produit
 * (signature, empreintes, chargement des fichiers).
 *
 * Code de sortie : 0 ; 1 en cas d'erreur d'arguments, de clé, de fichier ou de vérification.
 *
 * @param environment valeur d'une variable d'environnement.
 * @param trustedKeys clés de confiance ; l'outil échoue si aucune ne vérifie la signature produite.
 * @param clock instant courant (version par défaut).
 */
class BuildDictionaryPackCli(
    private val environment: (String) -> String? = System::getenv,
    private val trustedKeys: () -> List<PublicKey> = PackKeys::trusted,
    private val clock: () -> Instant = Instant::now,
) {

    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        val options = try {
            Options.parse(args, DateTimeFormatter.ofPattern(VERSION_PATTERN).withZone(ZoneOffset.UTC).format(clock()))
        } catch (e: IllegalArgumentException) {
            err.println(e.message)
            err.println("Usage : buildDictionaryPack --lexicons <dossier> --notice <fichier> --out <dossier> [--version yyyyMMddHHmm]")
            return EXIT_FAILURE
        }
        val pem = environment(KEY_VARIABLE)
        if (pem.isNullOrBlank()) {
            err.println("Clé de signature absente : définir la variable d'environnement $KEY_VARIABLE (PEM PKCS#8, docs/PUBLISHING.md).")
            return EXIT_FAILURE
        }
        return try {
            val key = PackSignature.privateKeyFromPem(pem)
            build(options, key, out)
            EXIT_OK
        } catch (e: IllegalArgumentException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: PackException) {
            err.println("Paquet refusé à la vérification : ${e.message}")
            EXIT_FAILURE
        } catch (e: IOException) {
            err.println("Lecture ou écriture impossible : ${e.message}")
            EXIT_FAILURE
        }
    }

    private fun build(options: Options, key: PrivateKey, out: PrintStream) {
        val sources = PackFormat.PACKED_FILES.associateWith { name ->
            val file = if (name == PackFormat.NOTICE) options.notice else File(options.lexicons, name)
            require(file.isFile) { "Fichier introuvable : $file." }
            file
        }
        val manifest = PackManifest.create(
            options.version,
            sources.map { (name, file) -> PackEntry.of(name, file) },
        ).serialize()
        val signature = PackSignature.sign(manifest, key)
        val keys = trustedKeys()
        require(PackSignature.verify(manifest, signature, keys)) {
            "La clé de signature ne correspond à aucune clé de PackKeys.TRUSTED : ajouter sa clé publique (docs/PUBLISHING.md)."
        }

        Files.createDirectories(options.out.toPath())
        val pack = File(options.out, PACK_NAME)
        pack.outputStream().buffered().use { PackWriter.write(it, manifest, signature, sources) }
        File(options.out, PackFormat.MANIFEST).writeBytes(manifest)
        File(options.out, PackFormat.SIGNATURE).writeBytes(signature)

        val scratch = Files.createTempDirectory("nextglyph-pack").toFile()
        try {
            val installed = PackStore(scratch, keys).install(pack.inputStream().buffered())
            out.println("Paquet ${installed.version} : ${pack.length()} octets, ${PackFormat.PACKED_FILES.size} fichiers, vérifié par une installation à blanc.")
        } finally {
            scratch.deleteRecursively()
        }
    }

    private class Options(val lexicons: File, val notice: File, val out: File, val version: Long) {
        companion object {
            fun parse(args: Array<String>, defaultVersion: String): Options {
                val values = HashMap<String, String>()
                var i = 0
                while (i < args.size) {
                    val name = args[i]
                    require(name in setOf("--lexicons", "--notice", "--out", "--version")) { "Option inconnue : « $name »." }
                    require(i + 1 < args.size) { "Valeur manquante pour $name." }
                    require(values.put(name, args[i + 1]) == null) { "Option répétée : $name." }
                    i += 2
                }
                fun required(name: String) = values[name] ?: throw IllegalArgumentException("Option obligatoire manquante : $name.")
                val version = values["--version"] ?: defaultVersion
                require(VERSION_REGEX.matches(version)) { "Version invalide : « $version » (yyyyMMddHHmm attendu)." }
                return Options(File(required("--lexicons")), File(required("--notice")), File(required("--out")), version.toLong())
            }
        }
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FAILURE = 1
        const val KEY_VARIABLE = "NEXTGLYPH_PACK_KEY"
        const val PACK_NAME = "nextglyph-dictionnaires" + PackFormat.EXTENSION
        private const val VERSION_PATTERN = "yyyyMMddHHmm"
        private val VERSION_REGEX = Regex("[0-9]{12}")
    }
}
