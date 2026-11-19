package nextglyph.tools.wordlists

import nextglyph.tools.wordlists.ArchiveFixtures.lexique
import nextglyph.tools.wordlists.ArchiveFixtures.lexiqueRow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class PipelineTest {

    private fun wordName(index: Int): String {
        var n = index
        val builder = StringBuilder()
        do {
            builder.append('a' + n % 26)
            n /= 26
        } while (n > 0)
        return "w" + builder.reverse()
    }

    private class Captured {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val outStream = PrintStream(out, true, Charsets.UTF_8)
        val errStream = PrintStream(err, true, Charsets.UTF_8)
        val stdout: String get() = out.toString(Charsets.UTF_8)
        val stderr: String get() = err.toString(Charsets.UTF_8)
    }

    private fun createInputs(dir: Path): WordListInputs {
        val data = Files.createDirectories(dir.resolve("data"))
        val lexiqueZip = data.resolve("Lexique410.zip")
        ArchiveFixtures.zip(
            lexiqueZip,
            linkedMapOf(
                "__MACOSX/Lexique4.1/._Lexique4.1.tsv" to "junk",
                "Lexique4.1/Lexique4.1.tsv" to lexique(
                    lexiqueRow("je", "20"), lexiqueRow("vois", "10"), lexiqueRow("nombre", "5"), lexiqueRow("l'", "8"),
                ),
            ),
        )
        val dico = data.resolve("lexique-grammalecte-fr-v7.7.zip")
        ArchiveFixtures.zip(
            dico,
            mapOf("README_lexique.txt" to "licence", "lexique-grammalecte-fr-v7.7.txt" to ArchiveFixtures.dicollecte("je", "vois", "nombre", "Paris")),
        )
        val scowl = data.resolve("scowl-2020.12.07.tar.gz")
        ArchiveFixtures.tarGz(
            scowl,
            ArchiveFixtures.scowlFiles(
                "scowl-2020.12.07/final/english-words.10" to "I\nthe\n",
                "scowl-2020.12.07/final/english-words.20" to "see\n",
                "scowl-2020.12.07/final/english-words.95" to "zzz\n",
                "scowl-2020.12.07/README" to "x",
            ),
        )
        val fr = data.resolve("fra_sentences.tsv.bz2")
        // une ligne mal formée : comptée comme ignorée
        ArchiveFixtures.bzip2(fr, ArchiveFixtures.tatoeba("fra", 300, "Je vois") + "pas une ligne tatoeba\n")
        val en = data.resolve("eng_sentences.tsv.bz2")
        ArchiveFixtures.bzip2(en, ArchiveFixtures.tatoeba("eng", 300, "I see"))
        return WordListInputs(lexiqueZip, dico, scowl, fr, en)
    }

    private fun manifest(dir: Path, ids: List<String>): Path {
        val names = mapOf(
            "lexique-4-10-fr" to "Lexique410.zip",
            "dicollecte-lexique-7-7-fr" to "lexique-grammalecte-fr-v7.7.zip",
            "scowl-final-lists-2020-12-07-en" to "scowl-2020.12.07.tar.gz",
            "tatoeba-fra-sentences" to "fra_sentences.tsv.bz2",
            "tatoeba-eng-sentences" to "eng_sentences.tsv.bz2",
        )
        val sources = ids.joinToString(",") { id ->
            """{"id":"$id","language":"fr","need":"a","url":"https://example.org/f/${names.getValue(id)}",""" +
                """"sha256":"${"a".repeat(64)}","size":10,"license":"test"}"""
        }
        return dir.resolve("sources.json").also { Files.writeString(it, """{"sources":[$sources]}""") }
    }

    private val allIds = listOf(
        "lexique-4-10-fr", "dicollecte-lexique-7-7-fr", "scowl-final-lists-2020-12-07-en",
        "tatoeba-fra-sentences", "tatoeba-eng-sentences",
    )

    @Nested
    inner class HappyPath {
        @Test
        fun `couverture - mots les plus fréquents et occurrences`() {
            val test = listOf("Le chat dort.", "Le chien dort.", "Le chat mange xyz")

            val result = CoverageAnalysis.compute(test, Tokenizer(Language.FR), setOf("le", "chat", "dort"))

            // clés : le 3, chat 2, dort 2, chien 1, mange 1, xyz 1 -> 6 clés, 3 couvertes
            assertThat(result.topWords).isEqualTo(Coverage(3, 6))
            assertThat(result.tokens).isEqualTo(Coverage(7, 10))
            assertThat(result.rejectedTokens).isZero()
        }

        @Test
        fun `couverture - texte et pourcentage`() {
            assertThat(Coverage(1, 4).percent).isEqualTo(25.0)
            assertThat(Coverage(973, 1000).render()).isEqualTo("973 / 1000 (97.30 %)")
        }

        @Test
        fun `statistiques - texte complet`() {
            val stats = ListStats(
                language = Language.FR, entries = 5, distinctKeys = 4,
                exclusions = linkedMapOf("motif A" to 2, "motif B" to 1), counters = linkedMapOf("compteur" to 7),
                trainSentences = 90, testSentences = 10, duplicateSentences = 3, ignoredCorpusLines = 1,
                coverage = CoverageAnalysis.Result(Coverage(1, 2), Coverage(3, 4), 5), maxFrequency = 9, minFrequency = 1,
            )

            assertThat(stats.render()).isEqualTo(
                """
                Liste de mots : fr
                entrées (formes d'affichage) : 5
                clés distinctes (TextNormalizer.normalize) : 4
                fréquence min / max : 1 / 9
                exclusions : 3
                  motif A : 2
                  motif B : 1
                compteur : 7
                phrases d'entraînement : 90
                phrases de test : 10
                doublons de phrases écartés : 3
                lignes de corpus ignorées (mal formées ou autre langue) : 1
                couverture des 1000 mots les plus fréquents du test : 1 / 2 (50.00 %)
                couverture des occurrences du test (mots valides) : 3 / 4 (75.00 %)
                mots du test hors alphabet (non couverts, non comptés) : 5

                """.trimIndent(),
            )
        }

        @Test
        fun `chaîne complète - sorties, partition sans fuite, tri, statistiques`(@TempDir dir: Path) {
            val inputs = createInputs(dir)
            val out = dir.resolve("build")
            val log = mutableListOf<String>()

            WordListPipeline { log += it }.run(inputs, out)

            val fr = Files.readAllLines(out.resolve("wordlists/fr.tsv"))
            assertThat(fr).containsExactly("je\t2000", "vois\t1000", "l'\t800", "nombre\t500", "Paris\t1")
            val en = Files.readAllLines(out.resolve("wordlists/en.tsv"))
            assertThat(en.map { it.substringBefore('\t') }).containsExactly("I", "the", "see")
            assertThat(en.map { it.substringAfter('\t').toLong() }).isSortedAccordingTo(reverseOrder())
            for (lang in listOf("fr", "en")) {
                val train = Files.readAllLines(out.resolve("corpus/$lang-train.txt"))
                val test = Files.readAllLines(out.resolve("corpus/$lang-test.txt"))
                assertThat(train.size + test.size).isEqualTo(300)
                assertThat(train.toSet() intersect test.toSet()).isEmpty()
                assertThat(test).isNotEmpty()
            }
            val mixed = Files.readAllLines(out.resolve("corpus/mixed-test.txt"))
            val labels = Files.readAllLines(out.resolve("corpus/mixed-test-languages.txt"))
            assertThat(mixed).hasSameSizeAs(labels)
            assertThat(labels.toSet()).containsExactlyInAnyOrder("fr", "en")
            val frStats = Files.readString(out.resolve("wordlists/fr-stats.txt"))
            assertThat(frStats).contains("entrées (formes d'affichage) : 5", "clés distinctes (TextNormalizer.normalize) : 5")
            val frTrain = Files.readAllLines(out.resolve("corpus/fr-train.txt")).size
            val frTest = Files.readAllLines(out.resolve("corpus/fr-test.txt")).size
            assertThat(frStats).contains(
                "fréquence min / max : 1 / 2000", "exclusions : 0", "phrases d'entraînement : $frTrain",
                "phrases de test : $frTest", "doublons de phrases écartés : 0",
                "lignes de corpus ignorées (mal formées ou autre langue) : 1", "formes à fréquence plancher : 1",
                "couverture des 1000 mots les plus fréquents du test : 3 / 3 (100.00 %)",
                "couverture des occurrences du test (mots valides) : ${3 * frTest} / ${3 * frTest} (100.00 %)",
            )
            val enStats = Files.readString(out.resolve("wordlists/en-stats.txt"))
            assertThat(enStats).contains(
                "couverture des 1000 mots les plus fréquents du test : 2 / 3 (66.67 %)", "mots comptés dans le corpus d'entraînement : ",
            )
            assertThat(enStats).contains("entrées (formes d'affichage) : 3", "formes SCOWL au-dessus du niveau 70 (ignorées) : 1")
            assertThat(log).anyMatch { it.contains("Corpus mixte") }
        }

        @Test
        fun `chaîne complète - deux exécutions donnent des fichiers identiques`(@TempDir dir: Path) {
            val inputs = createInputs(dir)

            WordListPipeline().run(inputs, dir.resolve("a"))
            WordListPipeline().run(inputs, dir.resolve("b"))

            for (file in listOf("wordlists/fr.tsv", "wordlists/en.tsv", "corpus/fr-test.txt", "corpus/en-train.txt", "corpus/mixed-test.txt")) {
                assertThat(Files.readAllBytes(dir.resolve("a").resolve(file))).isEqualTo(Files.readAllBytes(dir.resolve("b").resolve(file)))
            }
        }

        @Test
        fun `ligne de commande - succès`(@TempDir dir: Path) {
            createInputs(dir)
            val captured = Captured()

            val code = BuildWordListsCli.run(
                arrayOf(manifest(dir, allIds).toString(), dir.resolve("data").toString(), dir.resolve("out").toString()),
                captured.outStream, captured.errStream,
            )

            assertThat(code).isEqualTo(BuildWordListsCli.EXIT_OK)
            assertThat(captured.stderr).isEmpty()
            assertThat(captured.stdout).contains("Liste de mots : fr", "Liste de mots : en")
            assertThat(dir.resolve("out/wordlists/fr.tsv")).exists()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `ligne de commande - mauvais nombre d'arguments`() {
            val captured = Captured()

            val code = BuildWordListsCli.run(arrayOf("a", "b"), captured.outStream, captured.errStream)

            assertThat(code).isEqualTo(BuildWordListsCli.EXIT_USAGE)
            assertThat(captured.stderr).contains("Usage")
        }

        @Test
        fun `ligne de commande - manifeste introuvable`(@TempDir dir: Path) {
            val captured = Captured()

            val code = BuildWordListsCli.run(arrayOf(dir.resolve("absent.json").toString(), "d", "o"), captured.outStream, captured.errStream)

            assertThat(code).isEqualTo(BuildWordListsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Manifeste introuvable")
        }

        @Test
        fun `ligne de commande - manifeste invalide`(@TempDir dir: Path) {
            val bad = dir.resolve("bad.json").also { Files.writeString(it, "{pas du json") }
            val captured = Captured()

            val code = BuildWordListsCli.run(arrayOf(bad.toString(), "d", "o"), captured.outStream, captured.errStream)

            assertThat(code).isEqualTo(BuildWordListsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Manifeste invalide")
        }

        @Test
        fun `ligne de commande - source absente du manifeste`(@TempDir dir: Path) {
            val captured = Captured()

            val code = BuildWordListsCli.run(
                arrayOf(manifest(dir, allIds.drop(1)).toString(), dir.toString(), dir.resolve("o").toString()),
                captured.outStream, captured.errStream,
            )

            assertThat(code).isEqualTo(BuildWordListsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("lexique-4-10-fr").contains("absente du manifeste")
        }

        @Test
        fun `ligne de commande - fichier de données absent`(@TempDir dir: Path) {
            val captured = Captured()

            val code = BuildWordListsCli.run(
                arrayOf(manifest(dir, allIds).toString(), dir.resolve("vide").toString(), dir.resolve("o").toString()),
                captured.outStream, captured.errStream,
            )

            assertThat(code).isEqualTo(BuildWordListsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Fichier absent").contains("fetchData")
        }

        @Test
        fun `ligne de commande - archive corrompue`(@TempDir dir: Path) {
            val inputs = createInputs(dir)
            Files.writeString(inputs.tatoebaEn, "pas du bzip2")
            val captured = Captured()

            val code = BuildWordListsCli.run(
                arrayOf(manifest(dir, allIds).toString(), dir.resolve("data").toString(), dir.resolve("o").toString()),
                captured.outStream, captured.errStream,
            )

            assertThat(code).isEqualTo(BuildWordListsCli.EXIT_FAILURE)
            assertThat(captured.stderr).contains("Lecture ou écriture impossible")
        }

        @Test
        fun `chaîne - archive Lexique sans le fichier attendu`(@TempDir dir: Path) {
            val inputs = createInputs(dir)
            ArchiveFixtures.zip(inputs.lexiqueZip, mapOf("autre.txt" to "x"))

            assertThatThrownBy { WordListPipeline().run(inputs, dir.resolve("o")) }
                .isInstanceOf(WordListException::class.java)
        }

        @Test
        fun `couverture - les mots hors alphabet sont comptés à part`() {
            val result = CoverageAnalysis.compute(listOf("ab ß€ x_y 日本 ab"), Tokenizer(Language.EN), setOf("ab"))

            assertThat(result.rejectedTokens).isEqualTo(2)
            assertThat(result.tokens).isEqualTo(Coverage(2, 4))
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `couverture - test vide`() {
            val result = CoverageAnalysis.compute(emptyList(), Tokenizer(Language.FR), setOf("a"))

            assertThat(result.topWords).isEqualTo(Coverage(0, 0))
            assertThat(result.tokens).isEqualTo(Coverage(0, 0))
            assertThat(Coverage(0, 0).percent).isEqualTo(0.0)
            assertThat(Coverage(0, 0).render()).isEqualTo("0 / 0 (0.00 %)")
        }

        @Test
        fun `couverture - seuls les 1000 mots les plus fréquents comptent`() {
            val words = (0 until 1100).map(::wordName)
            // le mot i apparaît (1200 - i) fois : les 1000 premiers sont les plus fréquents
            val test = words.mapIndexed { i, w -> List(1200 - i) { w }.joinToString(" ") }
            val keys = words.take(500).toSet()

            val result = CoverageAnalysis.compute(test, Tokenizer(Language.EN), keys)

            assertThat(CoverageAnalysis.TOP_WORDS).isEqualTo(1000)
            assertThat(result.topWords).isEqualTo(Coverage(500, 1000))
        }

        @Test
        fun `couverture - à égalité de fréquence, l'ordre alphabétique départage au rang 1000`() {
            val words = (0 until 1001).map(::wordName).sorted()
            val keys = words.take(1000).toSet()

            val result = CoverageAnalysis.compute(listOf(words.joinToString(" ")), Tokenizer(Language.EN), keys)

            assertThat(result.topWords).isEqualTo(Coverage(1000, 1000))
            assertThat(result.tokens).isEqualTo(Coverage(1000, 1001))
        }

        @Test
        fun `pourcentage - couverture totale et nulle`() {
            assertThat(Coverage(4, 4).percent).isEqualTo(100.0)
            assertThat(Coverage(0, 4).percent).isEqualTo(0.0)
            assertThat(Coverage(1, 3).render()).isEqualTo("1 / 3 (33.33 %)")
        }

        @Test
        fun `statistiques - aucune exclusion`() {
            val stats = ListStats(
                Language.EN, 0, 0, emptyMap(), emptyMap(), 0, 0, 0, 0,
                CoverageAnalysis.Result(Coverage(0, 0), Coverage(0, 0), 0), 0, 0,
            )

            assertThat(stats.render()).contains("Liste de mots : en", "exclusions : 0", "fréquence min / max : 0 / 0")
        }

        @Test
        fun `ligne de commande - codes de sortie distincts`() {
            assertThat(setOf(BuildWordListsCli.EXIT_OK, BuildWordListsCli.EXIT_FAILURE, BuildWordListsCli.EXIT_USAGE)).hasSize(3)
        }
    }
}
