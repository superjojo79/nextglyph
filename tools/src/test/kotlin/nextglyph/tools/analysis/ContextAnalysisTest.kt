package nextglyph.tools.analysis

import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramModelBuilder
import nextglyph.engine.ngram.NgramPruning
import nextglyph.engine.predict.ConfusionConfig
import nextglyph.engine.predict.ConfusionSet
import nextglyph.engine.predict.Prudence
import nextglyph.tools.wordlists.Language
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class ContextAnalysisTest {

    private val frList = listOf(
        "il\t1000000", "elle\t900000", "va\t500000", "a\t800000", "à\t700000", "un\t600000", "chat\t100000",
        "paris\t90000", "l'\t300000", "école\t50000", "ses\t40000", "ces\t30000", "avec\t200000",
    )
    private val enList = listOf("zz\t100000000", "the\t90000", "cat\t30000")
    private val frTest = listOf("Il va à Paris.", "Elle a un chat.", "Il va à l'école avec ses amis.")
    private val enTest = listOf("The cat.")

    private fun input(language: Language, list: List<String>, test: List<String>, layout: KeyboardLayout, model: NgramModel? = null) =
        SpaceInput(LanguageInput(language, WordListLoader.load(list.asSequence(), language.code), layout, test), model)

    /** Modèle dont les confusions sont `(mot précédent, forme, occurrences)`. */
    private fun model(vararg counts: Triple<String, String, Int>): NgramModel {
        val builder = NgramModelBuilder(
            "fr", NgramPruning(minBigramCount = 1, minTrigramCount = 1, minTrigramGain = 0.0, minConfusionCount = 1),
            confusionSets = ConfusionConfig.FRENCH_SETS.map { it.forms },
        )
        builder.addSentence(listOf("il", "elle", "va", "avec", "un"), 2)
        for ((previous, form, count) in counts) builder.addConfusionContext(listOf(previous, form), count)
        return NgramModel.load(ByteBuffer.wrap(builder.build()))
    }

    private val small = ContextAnalysisConfig(sentences = 100, prudences = listOf(Prudence.NORMAL), cacheSize = 100, threads = 2, examples = 5)

    private fun run(model: NgramModel?, config: ContextAnalysisConfig = small, log: (String) -> Unit = {}): String =
        ContextAnalysis(config, log).run(
            listOf(
                input(Language.FR, frList, frTest, KeyboardLayout.AZERTY, model),
                input(Language.EN, enList, enTest, KeyboardLayout.QWERTY),
            ),
        )

    @Nested
    inner class HappyPath {
        @Test
        fun `le rapport contient les sections de chaque langue`() {
            val logs = ArrayList<String>()

            val report = run(model(Triple("va", "à", 100)), log = logs::add)

            assertThat(report).contains(
                "# Résultats bruts de `:tools:analyzeContext` (M55)",
                "## fr (AZERTY), avec modèle de mot suivant",
                "## en (QWERTY), sans modèle de mot suivant",
                "### Fausses corrections sur texte juste",
                "### Confusions injectées",
                "### Mots tapés en majuscules (sigles)",
                "(aucun ensemble de confusion pour cette langue)",
            )
            assertThat(logs).anySatisfy { assertThat(it).contains("M55 fr : NORMAL") }
            assertThat(logs).anySatisfy { assertThat(it).contains("M55 en : NORMAL") }
        }

        @Test
        fun `une confusion injectée est corrigée quand le mot précédent est sans ambiguïté`() {
            val report = run(model(Triple("va", "à", 100)))

            // « à » tapé « a » après « va » : 2 occurrences, toutes corrigées.
            assertThat(report).contains("| `a → à` | NORMAL | 2 | 100,00 % | 0,00 % | 0,00 % | 0,000 % |")
        }

        @Test
        fun `une confusion non décidée par le modèle est laissée ou proposée`() {
            val report = run(model(Triple("va", "à", 3), Triple("va", "a", 3)))

            assertThat(report).contains("| `a → à` | NORMAL | 2 | 0,00 % | 100,00 % | 0,00 % | 0,000 % |")
        }

        @Test
        fun `un mot juste changé à tort par le contexte est compté`() {
            // « Elle a un chat » : un modèle qui ne connaît que « elle à » change le « a » juste.
            val report = run(model(Triple("elle", "à", 100)))

            assertThat(report).contains("| `a` | NORMAL | 1 | 1 | 100,000 % |")
            assertThat(report).contains("`Elle a → à` 1")
        }

        @Test
        fun `les mots tapés en majuscules sans accent sont protégés, les autres sections sont chiffrées`() {
            val report = run(model(Triple("elle", "à", 100)))

            // 15 mots validés, un seul changé : « a » après « Elle ».
            assertThat(report).contains("| NORMAL | 15 | 1 | 6,6667 % |")
            assertThat(report).contains("| `a` | NORMAL | 1 | 1 | 100,000 % |", "| `ses` | NORMAL | 1 | 0 | 0,000 % |")
            assertThat(report).contains("| `ces → ses` | NORMAL | 1 | 0,00 % | 0,00 % | 100,00 % | 0,000 % |")
            // 12 mots de deux lettres ou plus : « école » tapé « ECOLE » est accentué sans la protection.
            assertThat(report).contains("| NORMAL | 12 | 8,33 % | 0,000 % |")
            assertThat(report).contains("Fausses corrections les plus fréquentes (Normale) : `Elle a → à` 1.")
        }

        @Test
        fun `les deux prudences sont rapportées dans l'ordre du réglage`() {
            val report = run(model(), small.copy(prudences = listOf(Prudence.BOLD, Prudence.CAUTIOUS)))

            assertThat(report.indexOf("| CAUTIOUS | 15 |")).isPositive().isLessThan(report.indexOf("| BOLD | 15 |"))
        }

        @Test
        fun `une virgule coupe le contexte et la confusion n'est plus corrigée`() {
            val withComma = ContextAnalysis(small.copy(threads = 1)).run(
                listOf(input(Language.FR, frList, listOf("Il va, à Paris."), KeyboardLayout.AZERTY, model(Triple("va", "à", 100)))),
            )
            val without = ContextAnalysis(small.copy(threads = 1)).run(
                listOf(input(Language.FR, frList, listOf("Il va à Paris."), KeyboardLayout.AZERTY, model(Triple("va", "à", 100)))),
            )

            assertThat(withComma).contains("| `a → à` | NORMAL | 1 | 0,00 % | 100,00 % | 0,00 % | 0,000 % |")
            assertThat(without).contains("| `a → à` | NORMAL | 1 | 100,00 % | 0,00 % | 0,00 % | 0,000 % |")
        }

        @Test
        fun `seules les formes écrites sans accent comptent dans les fausses corrections`() {
            val report = run(model(Triple("va", "à", 100)))

            assertThat(report).doesNotContain("| `à` |", "| `où` |")
            assertThat(report).contains("| `ses` | NORMAL | 1 | 0 | 0,000 % |")
        }

        @Test
        fun `une élision garde le mot suivant collé et une phrase vide n'ajoute rien`() {
            val report = ContextAnalysis(small.copy(threads = 1)).run(
                listOf(input(Language.FR, frList, listOf("L'école.", ""), KeyboardLayout.AZERTY, model())),
            )

            assertThat(report).contains("| NORMAL | 1 | 0 | 0,0000 % |")
        }

        @Test
        fun `la phrase est tapée mot par mot avec les virgules et les élisions`() {
            val config = small.copy(sentences = 1, threads = 1)

            val report = ContextAnalysis(config).run(
                listOf(input(Language.FR, frList, listOf("Il va, avec ses amis, à l'école."), KeyboardLayout.AZERTY, model(Triple("avec", "ses", 100)))),
            )

            assertThat(report).contains("`ces → ses`")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `aucune langue est refusée`() {
            assertThatThrownBy { ContextAnalysis(small).run(emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("langue")
        }

        @Test
        fun `une configuration invalide est refusée`() {
            assertThatThrownBy { ContextAnalysisConfig(sentences = 0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("phrase")
            assertThatThrownBy { ContextAnalysisConfig(prudences = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("prudence")
            assertThatThrownBy { ContextAnalysisConfig(threads = 0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("fil")
            assertThatThrownBy { ContextAnalysisConfig(examples = -1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("exemples")
        }

        @Test
        fun `sans modèle de mot suivant aucune confusion n'est corrigée`() {
            val report = run(model = null)

            assertThat(report).contains("## fr (AZERTY), sans modèle de mot suivant")
            // Sans modèle : jamais corrigée ; « à » reste seulement une variante de « a » dans la barre.
            assertThat(report).contains("| `a → à` | NORMAL | 2 | 0,00 % | 100,00 % | 0,00 % | 0,000 % |")
        }

        @Test
        fun `les exemples peuvent être désactivés`() {
            val report = run(model(Triple("elle", "à", 100)), small.copy(examples = 0))

            assertThat(report).doesNotContain("Fausses corrections les plus fréquentes")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `l'échantillon se limite au nombre de phrases demandé`() {
            val many = List(50) { "Il va à Paris." }
            val one = ContextAnalysis(small.copy(sentences = 1, threads = 1))
                .run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY, model(Triple("va", "à", 100)))))
            val all = ContextAnalysis(small.copy(sentences = 50, threads = 1))
                .run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY, model(Triple("va", "à", 100)))))

            assertThat(one).contains("| `a → à` | NORMAL | 1 |")
            assertThat(all).contains("| `a → à` | NORMAL | 50 |")
        }

        @Test
        fun `l'échantillon d'un grand corpus a exactement la taille demandée`() {
            val many = List(400) { "Il va à Paris." }
            val counts = listOf(100, 399, 400, 1000).map { sentences ->
                val report = ContextAnalysis(small.copy(sentences = sentences, threads = 1))
                    .run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY, model(Triple("va", "à", 100)))))
                Regex("\\| `a → à` \\| NORMAL \\| (\\d+) \\|").find(report)!!.groupValues[1].toInt()
            }

            assertThat(counts).containsExactly(100, 399, 400, 400)
        }

        @Test
        fun `l'échantillon est le même à chaque exécution`() {
            val many = List(400) { "Il va à Paris." }
            val config = small.copy(sentences = 100, threads = 1)
            val first = ContextAnalysis(config).run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY, model(Triple("va", "à", 100)))))
            val second = ContextAnalysis(config).run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY, model(Triple("va", "à", 100)))))

            assertThat(first.substringBefore("Durée")).isEqualTo(second.substringBefore("Durée"))
        }

        @Test
        fun `le texte vu par le moteur ne dépasse pas la fenêtre et la première espace dépend de la ponctuation`() {
            assertThat(ContextAnalysis.window("", "a", false)).isEqualTo("a")
            assertThat(ContextAnalysis.window("il", "a", true)).isEqualTo("il a")
            assertThat(ContextAnalysis.window("x".repeat(500), "a", true)).hasSize(nextglyph.engine.text.WordPrefix.LOOKBACK * 2)
            assertThat(ContextAnalysis.previousSpace("")).isFalse()
            assertThat(ContextAnalysis.previousSpace("l'")).isFalse()
            assertThat(ContextAnalysis.previousSpace("il, ")).isFalse()
            assertThat(ContextAnalysis.previousSpace("il")).isTrue()
        }

        @Test
        fun `les formes tapées sans accent sont les seules corrigeables`() {
            assertThat(ContextAnalysis.isPlain("a")).isTrue()
            assertThat(ContextAnalysis.isPlain("ses")).isTrue()
            assertThat(ContextAnalysis.isPlain("à")).isFalse()
            assertThat(ContextAnalysis.isPlain("")).isFalse()
            assertThat(ContextAnalysis.isPlain("l'")).isFalse()
        }

        @Test
        fun `un mot en majuscules est tapé sans accent`() {
            assertThat(ContextAnalysis.capitals("école")).isEqualTo("ECOLE")
            assertThat(ContextAnalysis.capitals("à")).isEqualTo("A")
            assertThat(ContextAnalysis.capitals("Il")).isEqualTo("IL")
        }

        @Test
        fun `la casse du mot voulu est reportée sur la forme injectée`() {
            assertThat(ContextAnalysis.matchCase("a", "À")).isEqualTo("A")
            assertThat(ContextAnalysis.matchCase("a", "à")).isEqualTo("a")
            assertThat(ContextAnalysis.matchCase("a", "")).isEqualTo("a")
            assertThat(ContextAnalysis.contextOf("une très longue phrase avant le mot")).hasSizeLessThanOrEqualTo(20)
        }

        @Test
        fun `les compteurs d'une confusion répartissent les issues`() {
            val counters = ConfusionCounters()
            ConfusionOutcome.entries.forEach(counters::add)
            counters.add(ConfusionOutcome.FIXED)

            assertThat(counters.total).isEqualTo(5)
            assertThat(counters.fixed).isEqualTo(2)
            assertThat(counters.offered).isEqualTo(1)
            assertThat(counters.left).isEqualTo(1)
            assertThat(counters.wrong).isEqualTo(1)

            val changes = FalseChangeCounters()
            changes.add(true)
            changes.add(false)
            assertThat(changes.occurrences).isEqualTo(2)
            assertThat(changes.changed).isEqualTo(1)
        }

        @Test
        fun `la configuration par défaut mesure les trois prudences`() {
            assertThat(ContextAnalysisConfig().prudences).containsExactly(Prudence.CAUTIOUS, Prudence.NORMAL, Prudence.BOLD)
            assertThat(ContextAnalysisConfig().sentences).isEqualTo(20_000)
            val sets = ContextAnalysisConfig(engine = nextglyph.engine.predict.PredictionConfig(confusion = ConfusionConfig(sets = mapOf("fr" to listOf(ConfusionSet(listOf("a", "à")))))))
            assertThat(sets.engine.confusion.sets.getValue("fr")).hasSize(1)
        }
    }
}
