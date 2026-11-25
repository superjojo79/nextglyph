package nextglyph.tools.pack

import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.engine.ngram.NgramModelBuilder
import nextglyph.engine.ngram.NgramPruning
import nextglyph.engine.pack.PackFormat
import nextglyph.engine.pack.PackManifest
import nextglyph.engine.pack.PackSignature
import nextglyph.engine.pack.PackStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class BuildDictionaryPackCliTest {

    @TempDir
    lateinit var dir: File

    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val stdout: String get() = out.toString(Charsets.UTF_8)
    private val stderr: String get() = err.toString(Charsets.UTF_8)

    private fun pem(key: java.security.PrivateKey = pair.private): String =
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) + "\n-----END PRIVATE KEY-----\n"

    private fun lexicons(): File {
        val folder = File(dir, "lexicons").apply { mkdirs() }
        for (name in PackFormat.DATA_FILES) {
            val bytes = if (name.endsWith(".nglx")) {
                LexiconBuilder("fr").apply { add("de", 100); add("école", 50) }.toByteArray()
            } else {
                NgramModelBuilder("fr", NgramPruning(1, 1, 8, 4, 1000, 0.0, 1)).apply {
                    addSentence(listOf("je", "suis", "content"), 3)
                }.build()
            }
            File(folder, name).writeBytes(bytes)
        }
        return folder
    }

    private fun notice(): File = File(dir, "NOTICE").apply { writeText("attributions") }

    private fun run(
        vararg args: String,
        key: String? = pem(),
        clock: Instant = Instant.parse("2026-11-24T18:30:59Z"),
        trusted: List<java.security.PublicKey> = listOf(pair.public),
    ): Int = BuildDictionaryPackCli({ if (it == "NEXTGLYPH_PACK_KEY") key else null }, { trusted }, { clock })
        .run(arrayOf(*args), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))

    private fun standard(vararg more: String, key: String? = pem(), lexicons: File = lexicons(), notice: File = notice()): Int =
        run("--lexicons", lexicons.path, "--notice", notice.path, "--out", File(dir, "out").path, *more, key = key)

    @Nested
    inner class HappyPath {
        @Test
        fun `écrit le paquet, le manifeste et la signature`() {
            val code = standard("--version", "202611241830")

            assertThat(code).isEqualTo(0)
            val output = File(dir, "out")
            assertThat(output.list()!!.toList()).containsExactlyInAnyOrder("nextglyph-dictionnaires.ngpk", "manifest.txt", "manifest.sig")
            val manifest = PackManifest.parse(File(output, "manifest.txt").readBytes())
            assertThat(manifest.version).isEqualTo(202611241830)
            assertThat(manifest.compatible).isTrue()
            assertThat(manifest.files.map { it.name }).containsExactlyElementsOf(PackFormat.PACKED_FILES)
            assertThat(manifest.entry("NOTICE.txt")!!.size).isEqualTo("attributions".length.toLong())
            assertThat(PackSignature.verify(File(output, "manifest.txt").readBytes(), File(output, "manifest.sig").readBytes(), listOf(pair.public))).isTrue()
            assertThat(stdout).contains("Paquet 202611241830").contains("6 fichiers")
        }

        @Test
        fun `le paquet produit s'installe avec la clé publique`() {
            standard("--version", "202611241830")

            val installed = PackStore(File(dir, "install"), listOf(pair.public))
                .install(File(dir, "out/nextglyph-dictionnaires.ngpk").inputStream())

            assertThat(installed.version).isEqualTo(202611241830)
        }

        @Test
        fun `prend l'heure UTC de l'horloge comme version par défaut`() {
            standard()

            assertThat(PackManifest.parse(File(dir, "out/manifest.txt").readBytes()).version).isEqualTo(202611241830)
        }

        @Test
        fun `produit le même manifeste pour la même version et les mêmes fichiers`() {
            standard("--version", "202611241830")
            val first = File(dir, "out/manifest.txt").readBytes()

            standard("--version", "202611241830")

            assertThat(File(dir, "out/manifest.txt").readBytes()).isEqualTo(first)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `échoue sans clé de signature`() {
            assertThat(standard(key = null)).isEqualTo(1)
            assertThat(stderr).contains("NEXTGLYPH_PACK_KEY")
            assertThat(File(dir, "out")).doesNotExist()
        }

        @Test
        fun `échoue si la clé ne correspond à aucune clé de confiance`() {
            val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

            val code = run("--lexicons", lexicons().path, "--notice", notice().path, "--out", File(dir, "out").path, trusted = listOf(other.public))

            assertThat(code).isEqualTo(1)
            assertThat(stderr).contains("aucune clé de PackKeys.TRUSTED").contains("docs/PUBLISHING.md")
            assertThat(File(dir, "out")).doesNotExist()
        }

        @Test
        fun `échoue sans aucune clé de confiance`() {
            assertThat(run("--lexicons", lexicons().path, "--notice", notice().path, "--out", File(dir, "out").path, trusted = emptyList())).isEqualTo(1)
            assertThat(stderr).contains("PackKeys.TRUSTED")
        }

        @Test
        fun `échoue avec une clé vide`() {
            assertThat(standard(key = "  \n")).isEqualTo(1)
            assertThat(stderr).contains("NEXTGLYPH_PACK_KEY")
        }

        @Test
        fun `échoue avec une clé qui n'est pas un PEM`() {
            assertThat(standard(key = "abc")).isEqualTo(1)
            assertThat(stderr).contains("PKCS#8")
        }

        @Test
        fun `échoue avec une clé qui n'est pas du EC`() {
            val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

            assertThat(standard(key = pem(rsa.private))).isEqualTo(1)
            assertThat(stderr).isNotBlank()
        }

        @Test
        fun `échoue si un fichier de données manque`() {
            val lexicons = lexicons().also { File(it, "en.ngrm").delete() }

            assertThat(standard(lexicons = lexicons)).isEqualTo(1)
            assertThat(stderr).contains("Fichier introuvable").contains("en.ngrm")
        }

        @Test
        fun `échoue si la notice manque`() {
            assertThat(standard(notice = File(dir, "absent"))).isEqualTo(1)
            assertThat(stderr).contains("Fichier introuvable")
        }

        @Test
        fun `échoue si un fichier de données est invalide`() {
            val lexicons = lexicons().also { File(it, "en.nglx").writeBytes(ByteArray(30)) }

            assertThat(standard(lexicons = lexicons)).isEqualTo(1)
            assertThat(stderr).contains("Paquet refusé").contains("en.nglx")
        }

        @Test
        fun `échoue si le dossier de sortie est un fichier`() {
            val file = File(dir, "occupe").apply { writeText("x") }

            assertThat(run("--lexicons", lexicons().path, "--notice", notice().path, "--out", File(file, "sous").path)).isEqualTo(1)
            assertThat(stderr).contains("impossible")
        }

        @Test
        fun `affiche l'usage sans argument`() {
            assertThat(run()).isEqualTo(1)
            assertThat(stderr).contains("--lexicons").contains("Usage")
        }

        @Test
        fun `refuse une option inconnue`() {
            assertThat(standard("--autre", "x")).isEqualTo(1)
            assertThat(stderr).contains("Option inconnue")
        }

        @Test
        fun `refuse une option sans valeur`() {
            assertThat(run("--lexicons")).isEqualTo(1)
            assertThat(stderr).contains("Valeur manquante")
        }

        @Test
        fun `refuse une option répétée`() {
            assertThat(standard("--version", "202611241830", "--version", "202611241831")).isEqualTo(1)
            assertThat(stderr).contains("Option répétée")
        }

        @Test
        fun `refuse une option obligatoire absente`() {
            assertThat(run("--lexicons", "a", "--out", "b")).isEqualTo(1)
            assertThat(stderr).contains("--notice")
        }

        @Test
        fun `refuse une version mal formée`() {
            for (version in listOf("abc", "2026112418", "2026-11-24 18:30", "2026112418300", "20261124183x")) {
                err.reset()

                assertThat(standard("--version", version)).describedAs(version).isEqualTo(1)
                assertThat(stderr).contains("Version invalide")
            }
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte une version de douze chiffres`() {
            assertThat(standard("--version", "000000000001")).isEqualTo(0)
            assertThat(PackManifest.parse(File(dir, "out/manifest.txt").readBytes()).version).isEqualTo(1)
        }

        @Test
        fun `complète la date par des zéros`() {
            standard(key = pem())
            err.reset()
            run("--lexicons", lexicons().path, "--notice", notice().path, "--out", File(dir, "out2").path, clock = Instant.parse("2027-01-02T03:04:00Z"))

            assertThat(PackManifest.parse(File(dir, "out2/manifest.txt").readBytes()).version).isEqualTo(202701020304)
        }

        @Test
        fun `accepte un dossier de sortie déjà existant`() {
            File(dir, "out").mkdirs()

            assertThat(standard()).isEqualTo(0)
        }
    }
}
