package nextglyph.tools.ngrams

import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramPruning
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

class BuildNgramsCliTest {

    private class Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val outStream = PrintStream(out, true, Charsets.UTF_8)
        val errStream = PrintStream(err, true, Charsets.UTF_8)
        val stdout: String get() = out.toString(Charsets.UTF_8)
        val stderr: String get() = err.toString(Charsets.UTF_8)
    }

    private val keepAll = NgramPruning(minBigramCount = 1, minTrigramCount = 1, minTrigramGain = 0.0)

    private fun write(path: Path, vararg lines: String) {
        Files.createDirectories(path.parent)
        Files.write(path, lines.toList())
    }

    private fun inputs(dir: Path, frList: List<String> = listOf("je\t100", "suis\t50", "là\t20", "tu\t10", "es\t10")) {
        write(dir.resolve("lists/fr.tsv"), *frList.toTypedArray())
        write(dir.resolve("lists/en.tsv"), "I\t100", "am\t50", "here\t20")
        write(dir.resolve("corpus/fr-train.txt"), "Je suis là.", "Je suis là.", "Tu es là.")
        write(dir.resolve("corpus/fr-test.txt"), "Je suis là.")
        write(dir.resolve("corpus/en-train.txt"), "I am here.", "I am here.")
        write(dir.resolve("corpus/en-test.txt"), "I am here.")
    }

    private fun run(
        dir: Path,
        captured: Captured,
        cli: BuildNgramsCli = BuildNgramsCli(pruning = keepAll, nanoTime = counter()),
    ): Int = cli.run(
        arrayOf(dir.resolve("lists").toString(), dir.resolve("corpus").toString(), dir.resolve("out").toString()),
        captured.outStream,
        captured.errStream,
    )

    private fun counter(): () -> Long {
        var now = 0L
        return { now += 1000; now }
    }

    private fun load(path: Path) = NgramModel.load(ByteBuffer.wrap(Files.readAllBytes(path)))

    @Nested
    inner class HappyPath {
        @Test
        fun `écrit un modèle rechargeable par langue et le rapport`(@TempDir dir: Path) {
            inputs(dir)
            val captured = Captured()

            val code = run(dir, captured)

            assertThat(code).isEqualTo(BuildNgramsCli.EXIT_OK)
            val fr = load(dir.resolve("out/fr.ngrm"))
            assertThat(fr.languageTag).isEqualTo("fr")
            assertThat(fr.next(listOf("je"), 1).map { it.text }).containsExactly("suis")
            val en = load(dir.resolve("out/en.ngrm"))
            assertThat(en.languageTag).isEqualTo("en")
            assertThat(en.next(emptyList(), 1).map { it.text }).containsExactly("I")
            val report = Files.readString(dir.resolve("out/${BuildNgramsCli.REPORT_NAME}"))
            assertThat(report).contains("## fr : `fr.ngrm`", "## en : `en.ngrm`", "Chargement (map + `NgramModel.load`) | 0.00 ms")
            assertThat(captured.stdout).contains("Mot suivant fr", "Mot suivant en", "top-1")
            assertThat(captured.stderr).isEmpty()
        }

        @Test
        fun `les taux du rapport viennent du corpus de test`(@TempDir dir: Path) {
            inputs(dir)

            run(dir, Captured())

            val report = Files.readString(dir.resolve("out/${BuildNgramsCli.REPORT_NAME}"))
            assertThat(report).contains("| Positions du test | 3 |")
            assertThat(report).contains("| Top-1, positions éligibles | 100.00 % |")
        }

        @Test
        fun `le dossier de sortie est créé`(@TempDir dir: Path) {
            inputs(dir)

            run(dir, Captured())

            assertThat(dir.resolve("out")).isDirectory()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un nombre d'arguments incorrect affiche l'usage`(@TempDir dir: Path) {
            val captured = Captured()

            val code = BuildNgramsCli().run(arrayOf("a", "b"), captured.outStream, captured.errStream)

            assertThat(code).isEqualTo(BuildNgramsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Usage : buildNgrams")
        }

        @Test
        fun `une liste de mots manquante est signalée`(@TempDir dir: Path) {
            inputs(dir)
            Files.delete(dir.resolve("lists/en.tsv"))
            val captured = Captured()

            val code = run(dir, captured)

            assertThat(code).isEqualTo(BuildNgramsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Lecture ou écriture impossible")
        }

        @Test
        fun `un corpus manquant est signalé`(@TempDir dir: Path) {
            inputs(dir)
            Files.delete(dir.resolve("corpus/fr-test.txt"))
            val captured = Captured()

            assertThat(run(dir, captured)).isEqualTo(BuildNgramsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Lecture ou écriture impossible")
        }

        @Test
        fun `une liste de mots invalide est signalée avec sa ligne`(@TempDir dir: Path) {
            inputs(dir, frList = listOf("je\t100", "suis"))
            val captured = Captured()

            val code = run(dir, captured)

            assertThat(code).isEqualTo(BuildNgramsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("fr.tsv, ligne 2")
        }

        @Test
        fun `un modèle au-delà du budget fait échouer, le rapport est écrit quand même`(@TempDir dir: Path) {
            inputs(dir)
            val captured = Captured()

            val code = run(dir, captured, BuildNgramsCli(sizeBudget = 10, pruning = keepAll, nanoTime = counter()))

            assertThat(code).isEqualTo(BuildNgramsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Modèle fr", "Modèle en", "au-delà du budget de 10 octets")
            assertThat(Files.readString(dir.resolve("out/${BuildNgramsCli.REPORT_NAME}"))).contains("HORS BUDGET")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un budget égal à la taille du plus gros modèle est respecté`(@TempDir dir: Path) {
            inputs(dir)
            run(dir, Captured())
            val biggest = maxOf(Files.size(dir.resolve("out/fr.ngrm")), Files.size(dir.resolve("out/en.ngrm")))

            val ok = run(dir, Captured(), BuildNgramsCli(sizeBudget = biggest, pruning = keepAll, nanoTime = counter()))
            val tooSmall = run(dir, Captured(), BuildNgramsCli(sizeBudget = biggest - 1, pruning = keepAll, nanoTime = counter()))

            assertThat(ok).isEqualTo(BuildNgramsCli.EXIT_OK)
            assertThat(tooSmall).isEqualTo(BuildNgramsCli.EXIT_FAILURE)
        }

        @Test
        fun `une liste de mots vide donne des modèles vides sans erreur`(@TempDir dir: Path) {
            inputs(dir, frList = emptyList())
            Files.write(dir.resolve("lists/en.tsv"), emptyList<String>())

            val code = run(dir, Captured())

            assertThat(code).isEqualTo(BuildNgramsCli.EXIT_OK)
            assertThat(load(dir.resolve("out/fr.ngrm")).vocabularySize).isZero()
        }

        @Test
        fun `des corpus vides donnent des modèles vides`(@TempDir dir: Path) {
            inputs(dir)
            Files.write(dir.resolve("corpus/fr-train.txt"), emptyList<String>())
            Files.write(dir.resolve("corpus/fr-test.txt"), emptyList<String>())
            val captured = Captured()

            val code = run(dir, captured)

            assertThat(code).isEqualTo(BuildNgramsCli.EXIT_OK)
            assertThat(load(dir.resolve("out/fr.ngrm")).vocabularySize).isZero()
            assertThat(Files.readString(dir.resolve("out/${BuildNgramsCli.REPORT_NAME}"))).contains("| Positions du test | 0 |")
        }
    }
}
