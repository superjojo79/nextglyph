package nextglyph.tools.analysis

import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.SuggestionBar
import nextglyph.engine.predict.Candidate
import nextglyph.engine.predict.Prudence
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

class EngineAnalysisTest {

    private val small = EngineAnalysisConfig(
        prudences = listOf(Prudence.NORMAL),
        errorRates = listOf(0.0, 0.5),
        mixedErrorRates = listOf(0.0, 0.5),
        samplePercent = 100,
        mixedSamplePercent = 100,
        cacheSize = 100,
    )

    private val frList = listOf(
        "le\t100", "la\t50", "bonjour\t90", "bonne\t10", "chat\t20", "j'\t50", "ai\t60", "l'\t40", "école\t30",
        "écolé\t1", "peut-être\t10", "a\t20", "à\t25", "était\t15",
    )
    private val enList = listOf("the\t100", "cat\t20", "table\t5", "don't\t40", "its\t5", "it's\t30")
    private val frTest = listOf("Bonjour le chat.", "J'ai l'école, peut-être.", "Il était à la table")
    private val enTest = listOf("The cat.", "I don't see its table, it's cold.")

    private fun input(language: Language, list: List<String>, test: List<String>, layout: KeyboardLayout) =
        LanguageInput(language, WordListLoader.load(list.asSequence(), language.code), layout, test)

    private fun run(config: EngineAnalysisConfig = small, log: (String) -> Unit = {}): String =
        EngineAnalysis(config, log).run(
            input(Language.FR, frList, frTest, KeyboardLayout.AZERTY),
            input(Language.EN, enList, enTest, KeyboardLayout.QWERTY),
            frTest + enTest,
            listOf(Language.FR, Language.FR, Language.FR, Language.EN, Language.EN),
        )

    private fun prediction(case: SpaceCase, top: String? = null) =
        Prediction("t", "t", case, "t", listOfNotNull(top?.let { Candidate(it, it, 0.9) }), SuggestionBar.EMPTY, "fr")

    @Nested
    inner class HappyPath {
        @Test
        fun `le rapport contient toutes les parties`() {
            val logs = ArrayList<String>()

            val report = run(log = logs::add)

            assertThat(report).contains(
                "# Résultats bruts de `:tools:analyzeEngine` (M2)",
                "## Validation par espace — fr (AZERTY), 13 mots",
                "## Validation par espace — en (QWERTY), 9 mots",
                "| 0 % | moteur, NORMAL (0,70) |",
                "| 50 % | M0, minProb 0,70 |",
                "## Apostrophes et traits d'union omis — fr",
                "## Apostrophes et traits d'union omis — en",
                "## Restitution des accents — fr",
                "| français |",
                "| français + anglais |",
                "## Corpus mixte — 5 phrases, 22 mots, AZERTY, deux lexiques actifs",
                "| 0 % | NORMAL |",
                "| 50 % | NORMAL |",
            )
            assertThat(logs).contains("M2 fr : fautes 0 %, NORMAL", "M2 corpus mixte : fautes 50 %, NORMAL")
        }

        @Test
        fun `chiffres - séparateurs et accents restitués sur le petit corpus`() {
            val report = run()

            // « jai » → « j'ai », « lecole » → « l'école » ; « peutetre » → « peut-être ».
            assertThat(report).contains("| élision | 2 | 100,00 % | 0,00 % | 0,00 % |")
            assertThat(report).contains("| trait d'union | 1 | 100,00 % | 0,00 % | 0,00 % |")
            // « dont » → « don't » ; « it's » validé dès « it » (complétion), avant le mot « its ».
            assertThat(report).contains("| apostrophe | 2 | 100,00 % | 0,00 % | 0,00 % |")
            assertThat(report).doesNotContain("Principaux mots non restitués")
            // Accents : école (dominante malgré « écolé »), était, peut-être ; « à » ambigu avec « a ».
            assertThat(report).contains("| français | 4 | 75,00 % | 0,00 % | 25,00 % | 0,00 % |")
            assertThat(report).contains("| 0 % | NORMAL | 59,09 % | 7,69 % | 8,33 % | 0,00 % | 90,91 % | 95,45 % |")
        }

        @Test
        fun `la commande avec --engine écrit le rapport M2`(@TempDir root: Path) {
            write(root.resolve("wordlists"), "fr.tsv", frList)
            write(root.resolve("wordlists"), "en.tsv", enList)
            write(root.resolve("corpus"), "fr-test.txt", frTest)
            write(root.resolve("corpus"), "en-test.txt", enTest)
            write(root.resolve("corpus"), "mixed-test.txt", frTest + enTest)
            write(root.resolve("corpus"), "mixed-test-languages.txt", listOf("fr", "fr", "fr", "en", "en"))
            val out = ByteArrayOutputStream()
            val output = root.resolve("out/M2.md")

            val code = AnalyzeCorpusCli.run(
                arrayOf("--engine", root.resolve("wordlists").toString(), root.resolve("corpus").toString(), output.toString(), "100"),
                PrintStream(out, true, Charsets.UTF_8),
                PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
                engineConfig = small.copy(samplePercent = 3, mixedSamplePercent = 3),
            )

            assertThat(code).isEqualTo(AnalyzeCorpusCli.EXIT_OK)
            assertThat(Files.readString(output)).contains("samplePercent=100, mixedSamplePercent=100", "## Corpus mixte")
            assertThat(out.toString(Charsets.UTF_8)).contains("M2 fr")
        }

        @Test
        fun `les fichiers français séparés par licence donnent les mêmes résultats que la liste unique`(@TempDir root: Path) {
            write(root.resolve("wordlists"), "fr.tsv", frList)
            write(root.resolve("wordlists"), "en.tsv", enList)
            // Lexique : orthographes sans casse et fréquences ; Dicollecte : formes au plancher (comptées en plus).
            write(root.resolve("wordlists"), "fr-lexique.tsv", frList)
            write(root.resolve("wordlists"), "fr-dicollecte.tsv", listOf("tchatche\t1"))
            write(root.resolve("corpus"), "fr-test.txt", frTest)
            write(root.resolve("corpus"), "en-test.txt", enTest)
            write(root.resolve("corpus"), "mixed-test.txt", frTest + enTest)
            write(root.resolve("corpus"), "mixed-test-languages.txt", listOf("fr", "fr", "fr", "en", "en"))
            fun report(flag: String): String {
                val output = root.resolve("out/$flag.md")
                val out = ByteArrayOutputStream()
                AnalyzeCorpusCli.run(
                    arrayOf(flag, root.resolve("wordlists").toString(), root.resolve("corpus").toString(), output.toString(), "100"),
                    PrintStream(out, true, Charsets.UTF_8),
                    PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
                    engineConfig = small,
                )
                return Files.readString(output).lines().filter { it.startsWith("|") }.joinToString("\n") + out.toString(Charsets.UTF_8).let { if ("séparés" in it) "\nséparés" else "" }
            }

            val split = report(AnalyzeCorpusCli.ENGINE_FLAG)
            val combined = report(AnalyzeCorpusCli.ENGINE_COMBINED_FLAG)

            assertThat(split).endsWith("séparés")
            assertThat(combined).doesNotContain("séparés")
            assertThat(split.removeSuffix("\nséparés")).isEqualTo(combined)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `corpus mixte et étiquettes de tailles différentes`() {
            assertThatThrownBy {
                EngineAnalysis(small).run(
                    input(Language.FR, frList, frTest, KeyboardLayout.AZERTY),
                    input(Language.EN, enList, enTest, KeyboardLayout.QWERTY),
                    frTest,
                    listOf(Language.FR),
                )
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("ne correspondent pas")
        }

        @Test
        fun `configuration invalide refusée`() {
            assertThatThrownBy { EngineAnalysisConfig(prudences = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EngineAnalysisConfig(errorRates = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EngineAnalysisConfig(samplePercent = 0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EngineAnalysisConfig(mixedSamplePercent = 101) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `décision au format M0 - seul le cas dominant donne un candidat`() {
            assertThat(EngineAnalysis.decision(prediction(SpaceCase.WORD))).isEqualTo(Decision(true, null, 0.0, 0.0))
            assertThat(EngineAnalysis.decision(prediction(SpaceCase.DOMINANT, "bonjour"))).isEqualTo(Decision(false, "bonjour", 1.0, 0.0))
            assertThat(EngineAnalysis.decision(prediction(SpaceCase.LITERAL, "bonjour"))).isEqualTo(Decision(false, null, 0.0, 0.0))
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `restitution - complétion avant la fin, faux, tel quel`() {
            val decide = { t: String ->
                when (t) {
                    "ja" -> Decision(false, "j'ai", 1.0, 0.0)
                    "xy" -> Decision(false, "autre", 1.0, 0.0)
                    else -> Decision(false, null, 0.0, 0.0)
                }
            }

            assertThat(EngineAnalysis.restoration("jai", "j'ai", decide)).isEqualTo(Restoration.RESTORED)
            assertThat(EngineAnalysis.restoration("xy", "x-y", decide)).isEqualTo(Restoration.WRONG)
            assertThat(EngineAnalysis.restoration("zz", "z'z", decide)).isEqualTo(Restoration.LITERAL)
        }

        @Test
        fun `compteurs de restitution`() {
            val c = RestorationCounters()

            c.add(Restoration.RESTORED, 3)
            c.add(Restoration.WRONG, 1)
            c.add(Restoration.LITERAL, 2)

            assertThat(c.words).isEqualTo(6)
            assertThat(c.restored).isEqualTo(3)
            assertThat(c.wrong).isEqualTo(1)
            assertThat(c.literal).isEqualTo(2)
        }

        @Test
        fun `dispositions - correspondance outils et moteur`() {
            assertThat(EngineAnalysis.layoutKind(KeyboardLayout.AZERTY)).isEqualTo(LayoutKind.AZERTY)
            assertThat(EngineAnalysis.layoutKind(KeyboardLayout.QWERTY)).isEqualTo(LayoutKind.QWERTY)
        }

        @Test
        fun `échantillon minimal - aucune phrase du corpus mixte`() {
            val report = run(small.copy(samplePercent = 1, mixedSamplePercent = 1))

            assertThat(report).contains("## Corpus mixte — 0 phrases, 0 mots")
        }
    }

    private fun write(dir: Path, name: String, lines: List<String>) {
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), lines)
    }
}
