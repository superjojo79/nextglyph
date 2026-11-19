package nextglyph.tools.lexicons

import nextglyph.engine.lexicon.Lexicon
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

class BuildLexiconsCliTest {

    private class Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val outStream = PrintStream(out, true, Charsets.UTF_8)
        val errStream = PrintStream(err, true, Charsets.UTF_8)
        val stdout: String get() = out.toString(Charsets.UTF_8)
        val stderr: String get() = err.toString(Charsets.UTF_8)
    }

    private fun write(path: Path, vararg lines: String) {
        Files.createDirectories(path.parent)
        Files.write(path, lines.toList())
    }

    private fun inputs(dir: Path, frTsv: List<String> = listOf("de\t100", "école\t50", "l'\t20", "3D\t4"), testLines: List<String> = listOf("De l'école.")) {
        write(dir.resolve("lists/fr.tsv"), *frTsv.toTypedArray())
        write(dir.resolve("lists/en.tsv"), "the\t100", "cat\t10")
        write(dir.resolve("corpus/fr-test.txt"), *testLines.toTypedArray())
        write(dir.resolve("corpus/en-test.txt"), "The cat.")
    }

    private fun run(
        out: Path,
        captured: Captured,
        cli: BuildLexiconsCli = BuildLexiconsCli(nanoTime = counter()),
        dir: Path = out,
    ): Int =
        cli.run(
            arrayOf(dir.resolve("lists").toString(), dir.resolve("corpus").toString(), out.resolve("out").toString()),
            captured.outStream, captured.errStream,
        )

    private fun counter(): () -> Long {
        var now = 0L
        return { now += 1000; now }
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `écrit les deux lexiques rechargeables et le rapport`(@TempDir dir: Path) {
            inputs(dir)
            val captured = Captured()

            val code = run(dir, captured)

            assertThat(code).isZero()
            val fr = Lexicon.load(ByteBuffer.wrap(Files.readAllBytes(dir.resolve("out/fr.nglx"))))
            assertThat(fr.languageTag).isEqualTo("fr")
            assertThat(fr.keyCount).isEqualTo(3)
            val en = Lexicon.load(ByteBuffer.wrap(Files.readAllBytes(dir.resolve("out/en.nglx"))))
            assertThat(en.languageTag).isEqualTo("en")
            assertThat(en.formCount).isEqualTo(2)
            assertThat(captured.stderr).isEmpty()
            assertThat(captured.stdout).contains("Lexique fr : lecture de", "3 clés, 3 formes", "1 formes ignorées", "Lexique en")
        }

        @Test
        fun `le rapport décrit chaque langue, les formes ignorées et la latence`(@TempDir dir: Path) {
            inputs(dir)

            run(dir, Captured())

            val report = Files.readString(dir.resolve("out/report.md"))
            assertThat(report).contains(
                "## fr", "## en", "| Clés | 3 |", "| Formes d'affichage | 3 |", "Exemples de formes ignorées : `3D`",
                "| Chargement (map + `Lexicon.load`) | 0.00 ms |",
                "| `distribution` moyenne | 1.00 µs |",
            )
            // « De l'école. » : de (2) + l' (2) + école (5) = 9 préfixes.
            assertThat(report).contains("| Préfixes mesurés | 9 |")
        }

        @Test
        fun `le nombre de préfixes mesurés est plafonné`(@TempDir dir: Path) {
            inputs(dir)

            run(dir, Captured(), BuildLexiconsCli(maxPrefixes = 4, nanoTime = counter()))

            assertThat(Files.readString(dir.resolve("out/report.md"))).contains("| Préfixes mesurés | 4 |")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `arguments absents - usage`() {
            val captured = Captured()

            val code = BuildLexiconsCli().run(arrayOf("a", "b"), captured.outStream, captured.errStream)

            assertThat(code).isEqualTo(1)
            assertThat(captured.stderr).contains("Usage : buildLexicons")
        }

        @Test
        fun `ligne mal formée - échec avec le fichier et la ligne`(@TempDir dir: Path) {
            inputs(dir, frTsv = listOf("de\t100", "ligne cassée"))
            val captured = Captured()

            assertThat(run(dir, captured)).isEqualTo(1)

            assertThat(captured.stderr).contains("fr.tsv, ligne 2")
        }

        @Test
        fun `fréquence invalide - échec`(@TempDir dir: Path) {
            inputs(dir, frTsv = listOf("de\t0"))
            val captured = Captured()

            assertThat(run(dir, captured)).isEqualTo(1)

            assertThat(captured.stderr).contains("fréquence invalide")
        }

        @Test
        fun `liste absente - échec de lecture`(@TempDir dir: Path) {
            val captured = Captured()

            assertThat(run(dir, captured)).isEqualTo(1)

            assertThat(captured.stderr).contains("Lecture ou écriture impossible")
        }

        @Test
        fun `corpus de test absent - échec de lecture`(@TempDir dir: Path) {
            inputs(dir)
            Files.delete(dir.resolve("corpus/en-test.txt"))
            val captured = Captured()

            assertThat(run(dir, captured)).isEqualTo(1)

            assertThat(captured.stderr).contains("Lecture ou écriture impossible")
        }

        @Test
        fun `lexique au-delà du budget - échec, rapport écrit quand même`(@TempDir dir: Path) {
            inputs(dir)
            val captured = Captured()

            val code = run(dir, captured, BuildLexiconsCli(sizeBudget = 10, nanoTime = counter()))

            assertThat(code).isEqualTo(1)
            assertThat(captured.stderr).contains("fr.nglx : ", "au-delà du budget de 10 octets")
            assertThat(Files.readString(dir.resolve("out/report.md"))).contains("HORS BUDGET")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `listes vides - lexiques vides, distribution non mesurée sans corpus`(@TempDir dir: Path) {
            write(dir.resolve("lists/fr.tsv"))
            write(dir.resolve("lists/en.tsv"))
            write(dir.resolve("corpus/fr-test.txt"))
            write(dir.resolve("corpus/en-test.txt"))

            val code = run(dir, Captured())

            assertThat(code).isZero()
            val report = Files.readString(dir.resolve("out/report.md"))
            assertThat(report).contains("| Clés | 0 |", "| `distribution` | non mesuré (aucun préfixe) |")
        }

        @Test
        fun `taille exactement égale au budget - acceptée, un octet de moins - refusée`(@TempDir dir: Path) {
            inputs(dir)
            val fr = Lexicon.load(ByteBuffer.wrap(Files.readAllBytes(run(dir, Captured()).let { dir.resolve("out/fr.nglx") }))).sizeInBytes.toLong()

            assertThat(run(dir.resolve("b"), Captured(), BuildLexiconsCli(sizeBudget = fr, nanoTime = counter()), dir)).isZero()
            assertThat(run(dir.resolve("c"), Captured(), BuildLexiconsCli(sizeBudget = fr - 1, nanoTime = counter()), dir)).isEqualTo(1)
        }

        @Test
        fun `dossier de sortie créé s'il n'existe pas`(@TempDir dir: Path) {
            inputs(dir)

            run(dir, Captured())

            assertThat(dir.resolve("out")).isDirectory
        }
    }
}
