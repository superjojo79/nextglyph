package nextglyph.tools.analysis

import nextglyph.engine.ngram.NgramModelBuilder
import nextglyph.engine.ngram.NgramPruning
import nextglyph.engine.predict.Prudence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class AnalyzeContextCliTest {

    private val small = ContextAnalysisConfig(sentences = 50, prudences = listOf(Prudence.NORMAL), cacheSize = 100, threads = 2)

    private val frList = listOf("de\t100000000", "je\t100000", "suis\t50000", "monsieur\t10000")
    private val enList = listOf("zz\t100000000", "the\t90000", "cat\t30000")

    private fun write(dir: Path, name: String, lines: List<String>) {
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), lines)
    }

    private fun prepare(root: Path, fr: List<String> = frList) {
        write(root.resolve("wordlists"), "fr.tsv", fr)
        write(root.resolve("wordlists"), "en.tsv", enList)
        write(root.resolve("corpus"), "fr-test.txt", listOf("Je suis monsieur."))
        write(root.resolve("corpus"), "en-test.txt", listOf("The cat."))
        Files.createDirectories(root.resolve("lexicons"))
    }

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = AnalyzeContextCli.run(arrayOf(*args), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8), small)
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun args(root: Path, sentences: String? = null) = listOfNotNull(
        root.resolve("wordlists").toString(), root.resolve("corpus").toString(), root.resolve("lexicons").toString(),
        root.resolve("out/M55.md").toString(), sentences,
    ).toTypedArray()

    @Nested
    inner class HappyPath {
        @Test
        fun `la commande écrit le rapport, sans modèle de bigrammes`(@TempDir root: Path) {
            prepare(root)

            val (code, out, err) = cli(*args(root))

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_OK)
            assertThat(err).isEmpty()
            assertThat(out).contains("Pas de modèle de bigrammes pour fr", "Pas de modèle de bigrammes pour en", "Résultats écrits dans")
            val report = Files.readString(root.resolve("out/M55.md"))
            assertThat(report).contains("sentences=50", "## fr (AZERTY), sans modèle de mot suivant", "## en (QWERTY), sans modèle de mot suivant", "Durée de l'analyse")
        }

        @Test
        fun `les modèles de bigrammes du dossier des lexiques sont lus`(@TempDir root: Path) {
            prepare(root)
            val builder = NgramModelBuilder("fr", NgramPruning(minBigramCount = 1, minTrigramCount = 1, minTrigramGain = 0.0))
            builder.addSentence(listOf("je", "suis"), 2)
            Files.write(root.resolve("lexicons/fr.ngrm"), builder.build())

            val (code, out, _) = cli(*args(root))

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_OK)
            assertThat(out).contains("Modèle de bigrammes fr :", "Pas de modèle de bigrammes pour en")
            assertThat(Files.readString(root.resolve("out/M55.md"))).contains("## fr (AZERTY), avec modèle de mot suivant")
        }

        @Test
        fun `le nombre de phrases en argument remplace celui de la configuration`(@TempDir root: Path) {
            prepare(root)

            val (code, _, _) = cli(*args(root, "7"))

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_OK)
            assertThat(Files.readString(root.resolve("out/M55.md"))).contains("sentences=7,")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un nombre d'arguments incorrect affiche l'usage`() {
            val (code, _, err) = cli("a", "b", "c")

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_USAGE)
            assertThat(err).contains("Usage : analyzeContext")
        }

        @Test
        fun `un nombre de phrases invalide affiche l'usage`(@TempDir root: Path) {
            for (bad in listOf("0", "-3", "abc")) {
                val (code, _, err) = cli(*args(root, bad))

                assertThat(code).describedAs(bad).isEqualTo(AnalyzeContextCli.EXIT_USAGE)
                assertThat(err).contains("Usage")
            }
        }

        @Test
        fun `des listes absentes sont signalées`(@TempDir root: Path) {
            Files.createDirectories(root.resolve("wordlists"))

            val (code, _, err) = cli(*args(root))

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_FAILURE)
            assertThat(err).contains("Lecture ou écriture impossible", "buildLexicons")
        }

        @Test
        fun `une liste mal formée est signalée`(@TempDir root: Path) {
            prepare(root, fr = listOf("je sans fréquence"))

            val (code, _, err) = cli(*args(root))

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_FAILURE)
            assertThat(err).contains("mal formée")
        }

        @Test
        fun `un modèle de bigrammes invalide est signalé`(@TempDir root: Path) {
            prepare(root)
            Files.write(root.resolve("lexicons/fr.ngrm"), byteArrayOf(1, 2, 3))

            val (code, _, err) = cli(*args(root))

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_FAILURE)
            assertThat(err).contains("Modèle de mot suivant invalide")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `le dossier de sortie est créé`(@TempDir root: Path) {
            prepare(root)

            cli(*args(root))

            assertThat(root.resolve("out/M55.md")).exists()
        }

        @Test
        fun `un fichier de sortie sans dossier parent est accepté`(@TempDir root: Path) {
            prepare(root)
            val output = Path.of("M55-test-${System.nanoTime()}.md")
            try {
                val (code, _, _) = cli(
                    root.resolve("wordlists").toString(), root.resolve("corpus").toString(), root.resolve("lexicons").toString(), output.toString(),
                )

                assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_OK)
                assertThat(output).exists()
            } finally {
                Files.deleteIfExists(output)
            }
        }

        @Test
        fun `la commande accepte exactement quatre ou cinq arguments`(@TempDir root: Path) {
            prepare(root)

            assertThat(cli(*args(root)).first).isEqualTo(AnalyzeContextCli.EXIT_OK)
            assertThat(cli(*args(root, "5")).first).isEqualTo(AnalyzeContextCli.EXIT_OK)
            assertThat(cli(*args(root, "5"), "6").first).isEqualTo(AnalyzeContextCli.EXIT_USAGE)
        }

        @Test
        fun `les durées affichées sont des secondes plausibles`(@TempDir root: Path) {
            prepare(root)

            val (_, out, _) = cli(*args(root))

            val logged = Regex("\\[(\\d+) s\\] M55 ").findAll(out).map { it.groupValues[1].toLong() }.toList()
            assertThat(logged).isNotEmpty().allMatch { it in 0..600 }
            val total = Regex("Résultats écrits dans .* \\((\\d+) s\\)").find(out)!!.groupValues[1].toLong()
            assertThat(total).isBetween(0L, 600L)
            val report = Files.readString(root.resolve("out/M55.md"))
            assertThat(Regex("Durée de l'analyse : (\\d+) s\\.").find(report)!!.groupValues[1].toLong()).isBetween(0L, 600L)
        }

        @Test
        fun `une seule phrase par langue est acceptée`(@TempDir root: Path) {
            prepare(root)

            val (code, _, _) = cli(*args(root, "1"))

            assertThat(code).isEqualTo(AnalyzeContextCli.EXIT_OK)
        }
    }
}
