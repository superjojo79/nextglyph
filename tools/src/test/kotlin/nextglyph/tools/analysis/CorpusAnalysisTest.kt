package nextglyph.tools.analysis

import nextglyph.tools.wordlists.Language
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class CorpusAnalysisTest {

    private val small = AnalysisConfig(
        grid = listOf(Thresholds(0.6, 2.0), Thresholds(0.8, 3.0, 2), Thresholds(0.7, 3.0, 2)),
        errorRates = listOf(0.0, 0.5),
        correctKnownPrefixes = listOf(false, true),
        windows = listOf(2),
        evidenceModes = listOf(EvidenceMode.FREQUENCY),
        mixedThresholds = listOf(Thresholds(0.6, 2.0)),
        mixedWindows = listOf(2),
        mixedErrorRates = listOf(0.0, 0.5),
    )

    private val frList = listOf("le\t100", "la\t50", "bonjour\t90", "bonne\t10", "chat\t20")
    private val enList = listOf("the\t100", "cat\t20", "table\t5")
    private val frTest = listOf("Bonjour le chat.", "La bonne table !")
    private val enTest = listOf("The cat.", "the table")

    private fun input(language: Language, list: List<String>, test: List<String>, layout: KeyboardLayout) =
        LanguageInput(language, WordListLoader.load(list.asSequence(), language.code), layout, test)

    private fun run(config: AnalysisConfig = small, log: (String) -> Unit = {}): String =
        CorpusAnalysis(config, log).run(
            input(Language.FR, frList, frTest, KeyboardLayout.AZERTY),
            input(Language.EN, enList, enTest, KeyboardLayout.QWERTY),
            frTest + enTest,
            listOf(Language.FR, Language.FR, Language.EN, Language.EN),
        )

    private fun write(dir: Path, name: String, lines: List<String>) {
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), lines)
    }

    private fun prepare(root: Path, labels: List<String> = listOf("fr", "fr", "en", "en"), fr: List<String> = frList) {
        write(root.resolve("wordlists"), "fr.tsv", fr)
        write(root.resolve("wordlists"), "en.tsv", enList)
        write(root.resolve("corpus"), "fr-test.txt", frTest)
        write(root.resolve("corpus"), "en-test.txt", enTest)
        write(root.resolve("corpus"), "mixed-test.txt", frTest + enTest)
        write(root.resolve("corpus"), "mixed-test-languages.txt", labels)
    }

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = AnalyzeCorpusCli.run(arrayOf(*args), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8), small)
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `le rapport contient les trois parties et les chiffres attendus`() {
            val logs = ArrayList<String>()

            val report = run(log = logs::add)

            assertThat(report).contains(
                "## Partie 1 — fr", "## Partie 1 — en", "## Partie 3 — corpus mixte",
                "## Partie 2 — fr (AZERTY), fautes 0 % par lettre, correction des préfixes inconnus seulement",
                "## Partie 2 — en (QWERTY), fautes 50 % par lettre, correction aussi des préfixes connus",
                // 6 mots français, 6 clés distinctes, aucun hors alphabet.
                "Mots du test : 6 ; hors alphabet (ignorés) : 0 ; clés distinctes : 6.",
                "| FREQUENCY | 2 |",
                "| 0 % | FREQUENCY | 2 | minProb=0.6 minRatio=2.0 minLength=1 |",
                "| 50 % | FREQUENCY | 2 |",
                "Mots : 10 ; changements de langue : 1.",
            )
            // Position 1 en français : 6 occurrences, préfixe toujours connu.
            assertThat(report).contains("| 1 | 6 | 0,00 % |")
            assertThat(logs).anyMatch { it.startsWith("Partie 1 (fr)") }.anyMatch { it.startsWith("Partie 3") }
        }

        @Test
        fun `lignes du rapport calculées sur le petit corpus`() {
            val report = run()

            assertThat(report).contains(
                "| 1 | 6 | 0,00 % | 3,0 | 1,28 | 0,321 | 0,578 | 1,80 | 83,33 % | 83,33 % | 16,67 % |",
                "| 7 | 1 | 0,00 % | 1,0 | 0,00 | 1,000 | 1,000 | 1,00 | 100,00 % | 100,00 % | 0,00 % |",
                "| 8+ | 0 | – | – |",
                "P(fin | mot complet) moyen (mots du lexique, 5 occurrences) : 1,000.",
                "Mots du test : 4 ; hors alphabet (ignorés) : 0 ; clés distinctes : 3.",
                "| 1 | 0,6 | 2,0 | 66,67 % | 0,00 % | 0,00 % | 0,00 % | 0,00 % | 0,645 | 0,645 | 0,00 % | 31,25 % |",
                "| 1 | 0,6 | 2,0 | 50,00 % | 50,00 % | 0,00 % | 0,00 % | 0,00 % | 0,677 | 0,677 | 50,00 % | 40,00 % |",
                "| 2 | 0,8 | 3,0 | 100,00 % | 75,00 % | 0,00 % | 0,00 % | 0,00 % | 0,722 | 0,722 | 0,00 % | 0,00 % |",
                "| minProb=0.6 minRatio=2.0 minLength=1 | k lettres tapées | 40,00 % | 50,00 % | 33,33 % | 0,00 % |",
                "| minProb=0.8 minRatio=3.0 minLength=2 | longueur du mot | 0,00 % |",
                "| Seuils | Mesure | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8+ |",
                "| FREQUENCY | 2 | 90,00 % | 1 | 1,00 | 1 | 1 | 0 |",
                "| 0 % | FREQUENCY | 2 | minProb=0.6 minRatio=2.0 minLength=1 | 90,00 % | 0,00 % | 0,00 % | 0,00 % |",
                "| 50 % | FREQUENCY | 2 | minProb=0.6 minRatio=2.0 minLength=1 | 60,00 % | 0,00 % | 0,00 % | 0,00 % |",
            )
            // Seuils hors du détail : longueur minimale 2 et rapport 3, absents du tableau détaillé.
            assertThat(report).doesNotContain("| minProb=0.7 minRatio=3.0 minLength=2 | longueur du mot")
            assertThat(report).contains("| 2 | 0,7 | 3,0 |")
            assertThat(report).doesNotContain("NaN")
        }

        @Test
        fun `la commande écrit le fichier de résultats`(@TempDir root: Path) {
            prepare(root)
            val output = root.resolve("analysis/out.md")

            val (code, out, err) = cli(root.resolve("wordlists").toString(), root.resolve("corpus").toString(), output.toString())

            assertThat(code).isEqualTo(AnalyzeCorpusCli.EXIT_OK)
            assertThat(err).isEmpty()
            assertThat(out).contains("Lexique fr", "Résultats écrits dans")
            // Durées en secondes depuis le début (quelques secondes au plus sur ce petit corpus).
            assertThat(out).containsPattern("\\[\\d{1,2} s\\] Partie 1 \\(fr\\)").containsPattern("\\(\\d{1,2} s\\)")
            assertThat(Files.readString(output)).containsPattern("Durée de l'analyse : \\d{1,2} s")
            assertThat(Files.readString(output)).contains("## Partie 3", "Durée de l'analyse")
        }

        @Test
        fun `échantillon passé en argument`(@TempDir root: Path) {
            prepare(root)
            val output = root.resolve("out.md")

            val (code, _, _) = cli(root.resolve("wordlists").toString(), root.resolve("corpus").toString(), output.toString(), "50")

            assertThat(code).isEqualTo(AnalyzeCorpusCli.EXIT_OK)
            assertThat(Files.readString(output)).contains("samplePercent=50")
        }

        @Test
        fun `grille par défaut - 3 longueurs, 6 probabilités, 5 rapports`() {
            assertThat(AnalysisConfig.defaultGrid()).hasSize(90).contains(Thresholds(0.6, 2.0, 1), Thresholds(0.9, 10.0, 3))
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `étiquettes du corpus mixte de taille différente`() {
            assertThatThrownBy {
                CorpusAnalysis(small).run(
                    input(Language.FR, frList, frTest, KeyboardLayout.AZERTY),
                    input(Language.EN, enList, enTest, KeyboardLayout.QWERTY),
                    frTest, listOf(Language.FR),
                )
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("étiquettes")
        }

        @Test
        fun `configuration invalide refusée`() {
            assertThatThrownBy { AnalysisConfig(grid = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { AnalysisConfig(windows = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { AnalysisConfig(mixedThresholds = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { AnalysisConfig(correctKnownPrefixes = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { AnalysisConfig(samplePercent = 0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("0")
            assertThatThrownBy { AnalysisConfig(samplePercent = 101) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `arguments invalides - code d'usage`() {
            assertThat(cli("a", "b").first).isEqualTo(AnalyzeCorpusCli.EXIT_USAGE)
            assertThat(cli("a", "b", "c", "d", "e").first).isEqualTo(AnalyzeCorpusCli.EXIT_USAGE)
            assertThat(cli("a", "b", "c", "x").first).isEqualTo(AnalyzeCorpusCli.EXIT_USAGE)
            assertThat(cli("a", "b", "c", "0").first).isEqualTo(AnalyzeCorpusCli.EXIT_USAGE)
            val (code, _, err) = cli("a", "b", "c", "101")
            assertThat(code).isEqualTo(AnalyzeCorpusCli.EXIT_USAGE)
            assertThat(err).contains("Usage")
        }

        @Test
        fun `fichiers absents - échec avec message`(@TempDir root: Path) {
            val (code, _, err) = cli(root.resolve("w").toString(), root.resolve("c").toString(), root.resolve("o.md").toString())

            assertThat(code).isEqualTo(AnalyzeCorpusCli.EXIT_FAILURE)
            assertThat(err).contains("buildWordLists")
        }

        @Test
        fun `étiquette de langue inconnue`(@TempDir root: Path) {
            prepare(root, labels = listOf("fr", "fr", "de", "en"))

            val (code, _, err) = cli(root.resolve("wordlists").toString(), root.resolve("corpus").toString(), root.resolve("o.md").toString())

            assertThat(code).isEqualTo(AnalyzeCorpusCli.EXIT_FAILURE)
            assertThat(err).contains("ligne 3", "de")
            assertThat(root.resolve("o.md")).doesNotExist()
        }

        @Test
        fun `liste de mots mal formée`(@TempDir root: Path) {
            prepare(root, fr = listOf("le\tbeaucoup"))

            val (code, _, err) = cli(root.resolve("wordlists").toString(), root.resolve("corpus").toString(), root.resolve("o.md").toString())

            assertThat(code).isEqualTo(AnalyzeCorpusCli.EXIT_FAILURE)
            assertThat(err).contains("ligne 1")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `échantillon de 1 pour cent - phrases filtrées de façon déterministe`() {
            val a = run(small.copy(samplePercent = 1))
            val b = run(small.copy(samplePercent = 1))

            assertThat(a).isEqualTo(b)
            assertThat(a).contains("Mots du test : 0 ;") // rangs 0 et 1 : hachages 19 et 49 modulo 100
        }

        @Test
        fun `échantillon de 100 pour cent passé explicitement - tout le corpus`(@TempDir root: Path) {
            prepare(root)
            val output = root.resolve("out.md")

            assertThat(cli(root.resolve("wordlists").toString(), root.resolve("corpus").toString(), output.toString(), "100").first)
                .isEqualTo(AnalyzeCorpusCli.EXIT_OK)
            assertThat(Files.readString(output)).contains("Mots du test : 6 ;")
        }

        @Test
        fun `une position jamais connue est rendue par des tirets`() {
            val report = CorpusAnalysis(small).run(
                input(Language.FR, frList, listOf("zzz"), KeyboardLayout.AZERTY),
                input(Language.EN, enList, enTest, KeyboardLayout.QWERTY),
                emptyList(), emptyList(),
            )

            assertThat(report).contains("| 2 | 1 | 100,00 % | – |", "Mots : 0 ; changements de langue : 0.")
        }

        @Test
        fun `formats numériques français`() {
            assertThat(CorpusAnalysis.f(1.5, 2)).isEqualTo("1,50")
            assertThat(CorpusAnalysis.pct(0.1234)).isEqualTo("12,34 %")
            assertThat(CorpusAnalysis.percent(0.02)).isEqualTo("2 %")
        }

        @Test
        fun `mots du corpus mixte - mots non indexables écartés`() {
            val words = CorpusAnalysis.mixedWords(listOf("L'école 3D", "Straße ok"), listOf(Language.FR, Language.EN))

            assertThat(words).containsExactly(MixedWord("l'", Language.FR), MixedWord("ecole", Language.FR), MixedWord("ok", Language.EN))
        }
    }
}
