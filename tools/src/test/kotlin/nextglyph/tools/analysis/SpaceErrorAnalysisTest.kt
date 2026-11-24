package nextglyph.tools.analysis

import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramModelBuilder
import nextglyph.engine.ngram.NgramPruning
import nextglyph.engine.predict.Candidate
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.PredictionConfig
import nextglyph.engine.predict.Prudence
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.SpaceEditConfig
import nextglyph.engine.predict.SuggestionBar
import nextglyph.tools.wordlists.Language
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class SpaceErrorAnalysisTest {

    /** `de` pèse presque toute la masse : `je`, `suis`... ont des probabilités réalistes. */
    private val frList = listOf(
        "de\t100000000", "je\t100000", "suis\t50000", "bonjour\t40000", "monsieur\t10000", "ordinateur\t5000", "et\t90000",
    )
    private val enList = listOf("zz\t100000000", "the\t90000", "cat\t30000", "dog\t20000")
    private val frTest = listOf("Je suis monsieur.", "Bonjour monsieur et je suis.")
    private val enTest = listOf("The cat and the dog.", "The dog.")

    private fun input(language: Language, list: List<String>, test: List<String>, layout: KeyboardLayout, model: NgramModel? = null) =
        SpaceInput(LanguageInput(language, WordListLoader.load(list.asSequence(), language.code), layout, test), model)

    private fun model(tag: String, vararg sentences: String): NgramModel {
        val builder = NgramModelBuilder(tag, NgramPruning(minBigramCount = 1, minTrigramCount = 1, minTrigramGain = 0.0))
        for (sentence in sentences) builder.addSentence(sentence.split(' '), 3)
        return NgramModel.load(ByteBuffer.wrap(builder.build()))
    }

    private val small = SpaceAnalysisConfig(
        sentences = 100,
        prudences = listOf(Prudence.NORMAL),
        sigma = 0.0,
        cacheSize = 100,
        threads = 2,
        unigramsOnly = false,
    )

    private fun run(config: SpaceAnalysisConfig = small, withModel: Boolean = true, log: (String) -> Unit = {}): String =
        SpaceErrorAnalysis(config, log).run(
            listOf(
                input(Language.FR, frList, frTest, KeyboardLayout.AZERTY, if (withModel) model("fr", "je suis monsieur", "bonjour monsieur") else null),
                input(Language.EN, enList, enTest, KeyboardLayout.QWERTY),
            ),
        )


    private val richList = frList + listOf("toujours\t20000", "pourquoi\t20000")
    private val richTest = listOf("Je suis monsieur.", "Bonjour monsieur et je suis.", "Ordinateur toujours pourquoi.", "Toujours bonjour.")

    private fun rich(sigma: Double): String =
        SpaceErrorAnalysis(small.copy(sigma = sigma, threads = 1, examples = 3))
            .run(listOf(input(Language.FR, richList, richTest, KeyboardLayout.AZERTY)))

    /** « aimebien » se coupe en `aime bien` ou en `aimeb ien` : seul le bigramme `aime bien` départage. */
    private fun ambiguous(): String {
        val list = listOf("de\t100000000", "aime\t1000", "bien\t1000", "aimeb\t100000", "ien\t100000")
        val builder = NgramModelBuilder("fr", NgramPruning(minBigramCount = 1, minTrigramCount = 1, minTrigramGain = 0.0))
        repeat(50) { builder.addSentence(listOf("aime", "bien"), 2) }
        val model = NgramModel.load(ByteBuffer.wrap(builder.build()))
        return SpaceErrorAnalysis(small.copy(unigramsOnly = true, threads = 1))
            .run(listOf(input(Language.FR, list, listOf("Aime bien."), KeyboardLayout.AZERTY, model)))
    }

    private fun prediction(case: SpaceCase, key: String, replaces: String? = null, word: String = "t", span: String = word) =
        Prediction(word, word, case, key, listOf(Candidate(key, key, 0.9, replaces)), SuggestionBar.EMPTY, "fr", span)

    @Nested
    inner class HappyPath {
        @Test
        fun `le rapport contient les deux langues, les fausses corrections et les erreurs injectées`() {
            val logs = ArrayList<String>()

            val report = run(log = logs::add)

            assertThat(report).contains(
                "# Résultats bruts de `:tools:analyzeSpaces` (M52)",
                "## fr (AZERTY), avec bigrammes",
                "## en (QWERTY), sans bigrammes",
                "### Fausses corrections sur texte juste",
                "### Erreurs d'espace injectées",
                "| espace tapée sur n, b, v ou c | NORMAL |",
                "| espace oubliée | NORMAL |",
                "| espace en trop | NORMAL |",
                "| espace en trop, deux fragments inconnus | NORMAL |",
                "| espace en trop, premier fragment déjà corrigé | NORMAL |",
            )
            assertThat(logs).anyMatch { it.startsWith("M52 fr : NORMAL en ") }
            assertThat(logs).anyMatch { it.startsWith("M52 en : NORMAL, sans bigrammes en ") }
        }

        @Test
        fun `des frappes parfaites sur des mots du lexique - aucune fausse correction, toutes les espaces oubliées sont rétablies`() {
            val report = run()

            // Français : 6 paires de mots voisins connus, 8 mots, aucune coupure appliquée à un mot du lexique.
            val fr = report.substringAfter("## fr (AZERTY)").substringBefore("## en (QWERTY)")
            assertThat(fr).contains("| juste | NORMAL | oui | 8 | 0,00 % | 0,000 % | 0,000 % | 0,000 % |")
            assertThat(fr).contains("| espace oubliée | NORMAL | 6 | 100,00 % | 0,00 % | 0,00 % |")
            assertThat(fr).contains("| espace tapée sur n, b, v ou c | NORMAL | 6 | 100,00 % | 0,00 % | 0,00 % |")
        }

        @Test
        fun `un échantillon plus petit que le corpus garde un sous-ensemble déterministe de phrases`() {
            val many = (1..40).map { "Je suis monsieur." }
            val config = small.copy(sentences = 10)

            val a = SpaceErrorAnalysis(config).run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY)))
            val b = SpaceErrorAnalysis(config).run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY)))

            assertThat(a).isEqualTo(b)
            val words = Regex("\\| juste \\| NORMAL \\| non \\| (\\d+) ").find(a)!!.groupValues[1].toInt()
            assertThat(words).isBetween(3, 3 * 25)
        }

        @Test
        fun `la comparaison sans bigrammes est ajoutée quand un modèle existe`() {
            val config = small.copy(unigramsOnly = true)

            val report = run(config)

            assertThat(report).contains("| juste | NORMAL | oui |", "| juste | NORMAL | non |")
        }

        @Test
        fun `les exemples de fausses corrections et d'espaces en trop manquées sont listés`() {
            val report = run(small.copy(examples = 5))

            assertThat(report).contains("Espaces en trop non corrigées, fragments inconnus (Normale) :")
        }

        @Test
        fun `un mot de texte juste qui serait coupé est compté comme fausse correction`() {
            // « jesuis » est inconnu du lexique mais coupable en « je suis » : une fausse correction sur du texte juste.
            val tricky = SpaceErrorAnalysis(small.copy(examples = 3)).run(
                listOf(input(Language.FR, frList, listOf("Jesuis monsieur."), KeyboardLayout.AZERTY)),
            )

            assertThat(tricky).contains("| juste | NORMAL | non | 2 | 50,00 % | 50,000 % | 0,000 % | 50,000 % |")
            assertThat(tricky).contains("Fausses corrections les plus fréquentes, texte juste (Normale) : `jesuis → je suis` 1.")
        }

        @Test
        fun `sans faute de frappe, les chiffres exacts d'une petite analyse`() {
            val report = rich(0.0)

            assertThat(report).contains(
                "| juste | NORMAL | non | 13 | 0,00 % | 0,000 % | 0,000 % | 0,000 % |",
                "| frappes simulées (σ 0,00, 0,00 % de lettres fausses) | NORMAL | non | 13 | 0,00 % | 0,000 % | 0,000 % | 0,000 % |",
                "| espace tapée sur n, b, v ou c | NORMAL | 9 | 100,00 % | 0,00 % | 0,00 % | non |",
                "| espace oubliée | NORMAL | 9 | 100,00 % | 0,00 % | 0,00 % | non |",
                "| espace en trop | NORMAL | 10 | 10,00 % | 0,00 % | 90,00 % | non |",
                "| espace en trop, deux fragments inconnus | NORMAL | 10 | 10,00 % | 0,00 % | 90,00 % | non |",
                "| espace en trop, premier fragment déjà corrigé | NORMAL | 9 | | | | non |",
                "Espaces en trop non corrigées, fragments inconnus (Normale) : `su is → suis suis` 2, `bonj our → bonjour pourquoi` 1, `mons ieur → monsieur ieur` 1.",
            )
        }

        @Test
        fun `avec des frappes très dispersées, les lettres fausses sont comptées et les mots voulus restent tapés au centre des touches`() {
            val report = rich(50.0)

            assertThat(report).contains(
                "| frappes simulées (σ 50,00, 98,72 % de lettres fausses) | NORMAL | non | 13 | 100,00 % | 0,000 % | 0,000 % | 0,000 % |",
                "| espace tapée sur n, b, v ou c | NORMAL | 9 | 100,00 % | 0,00 % | 0,00 % | non |",
                "| espace oubliée | NORMAL | 9 | 100,00 % | 0,00 % | 0,00 % | non |",
                "| espace en trop | NORMAL | 10 | 0,00 % | 0,00 % | 100,00 % | non |",
                "| espace en trop, premier fragment déjà corrigé | NORMAL | 10 | | | | non |",
            )
        }

        @Test
        fun `avec des fautes de frappe modérées, une partie des mots n'est plus un mot`() {
            val report = rich(0.3)

            assertThat(report).contains(
                "| frappes simulées (σ 0,30, 7,69 % de lettres fausses) | NORMAL | non | 13 | 38,46 % | 0,000 % | 0,000 % | 0,000 % |",
                "| espace en trop | NORMAL | 10 | 10,00 % | 0,00 % | 90,00 % | non |",
            )
        }

        @Test
        fun `le modèle de bigrammes départage une coupure ambiguë que les unigrammes manquent`() {
            val report = ambiguous()

            val withModel = report.lines().filter { it.endsWith("| oui |") }
            val without = report.lines().filter { it.endsWith("| non |") && it.startsWith("| espace") }
            assertThat(withModel).contains(
                "| espace oubliée | NORMAL | 1 | 100,00 % | 0,00 % | 0,00 % | oui |",
                "| espace tapée sur n, b, v ou c | NORMAL | 1 | 100,00 % | 0,00 % | 0,00 % | oui |",
            )
            assertThat(without).contains(
                "| espace oubliée | NORMAL | 1 | 0,00 % | 0,00 % | 100,00 % | non |",
                "| espace tapée sur n, b, v ou c | NORMAL | 1 | 0,00 % | 100,00 % | 0,00 % | non |",
            )
            assertThat(report.indexOf("| oui |")).isLessThan(report.indexOf("| non |"))
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `aucune langue n'est refusée`() {
            assertThatThrownBy { SpaceErrorAnalysis(small).run(emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Au moins une langue")
        }

        @Test
        fun `la configuration refuse les valeurs invalides`() {
            assertThatThrownBy { SpaceAnalysisConfig(sentences = 0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { SpaceAnalysisConfig(prudences = emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { SpaceAnalysisConfig(sigma = -0.1) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { SpaceAnalysisConfig(sigma = Double.NaN) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { SpaceAnalysisConfig(threads = 0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { SpaceAnalysisConfig(examples = -1) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `un moteur sans correction d'espace ne corrige rien`() {
            val off = small.copy(engine = PredictionConfig(spaceEdits = SpaceEditConfig(spaceBarWeight = 0.0, gluedWeight = 0.0, joinWeight = 0.0)))

            val report = run(off)

            assertThat(report).contains("| espace oubliée | NORMAL | 6 | 0,00 % |")
        }

        @Test
        fun `kindOf ne reconnaît que les corrections dominantes d'espace`() {
            assertThat(SpaceErrorAnalysis.kindOf(prediction(SpaceCase.WORD, "je suis"))).isNull()
            assertThat(SpaceErrorAnalysis.kindOf(prediction(SpaceCase.LITERAL, "je suis"))).isNull()
            assertThat(SpaceErrorAnalysis.kindOf(prediction(SpaceCase.DOMINANT, "bonjour"))).isNull()
            val empty = Prediction("t", "t", SpaceCase.DOMINANT, "t", emptyList(), SuggestionBar.EMPTY, "fr")
            assertThat(SpaceErrorAnalysis.kindOf(empty)).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `kindOf distingue la coupure de l'espace supprimée`() {
            assertThat(SpaceErrorAnalysis.kindOf(prediction(SpaceCase.DOMINANT, "je suis"))).isEqualTo(SpaceEditKind.SPLIT)
            assertThat(SpaceErrorAnalysis.kindOf(prediction(SpaceCase.DOMINANT, "ordina teur", replaces = "ordina teur"))).isEqualTo(SpaceEditKind.JOIN)
        }

        @Test
        fun `dernier mot et clés d un texte à plusieurs mots`() {
            assertThat(SpaceErrorAnalysis.lastWord("je suis")).isEqualTo("suis")
            assertThat(SpaceErrorAnalysis.lastWord("suis")).isEqualTo("suis")
            assertThat(SpaceErrorAnalysis.lastWord("")).isEmpty()
            assertThat(SpaceErrorAnalysis.keyOf("Je SUIS")).isEqualTo("je suis")
            assertThat(SpaceErrorAnalysis.keyOf("été ?")).isEqualTo("ete ?")
        }

        @Test
        fun `les compteurs de rappel - zéro, un et fusion`() {
            val c = RecallCounters()
            assertThat(c.total).isZero()
            assertThat(c.wrong).isZero()

            c.add(SpaceFix.FIXED)
            c.add(SpaceFix.LEFT)
            c.add(SpaceFix.WRONG)
            val other = RecallCounters().apply { add(SpaceFix.FIXED) }
            c.merge(other)

            assertThat(c.total).isEqualTo(4)
            assertThat(c.fixed).isEqualTo(2)
            assertThat(c.left).isEqualTo(1)
            assertThat(c.wrong).isEqualTo(1)
        }

        @Test
        fun `les compteurs de fausses corrections - mots, non-mots, coupures, espaces supprimées et fusion`() {
            val c = FalsePositiveCounters()
            c.add(prediction(SpaceCase.WORD, "bonjour"))
            c.add(prediction(SpaceCase.LITERAL, "xyz"))
            c.add(prediction(SpaceCase.DOMINANT, "je suis", word = "jesuis"))
            c.add(prediction(SpaceCase.DOMINANT, "ordina teur", replaces = "ordina teur", word = "teur", span = "ordina teur"))
            val other = FalsePositiveCounters().apply { add(prediction(SpaceCase.DOMINANT, "je suis", word = "jesuis")) }
            c.merge(other)

            assertThat(c.words).isEqualTo(5)
            assertThat(c.notWords).isEqualTo(4)
            assertThat(c.splits).isEqualTo(2)
            assertThat(c.joins).isEqualTo(1)
            assertThat(c.applied).isEqualTo(3)
            assertThat(c.examples).containsEntry("jesuis → je suis", 2L).containsEntry("ordina teur → ordina teur", 1L)
        }

        @Test
        fun `la configuration expose ses valeurs et accepte les bornes exactes`() {
            val c = SpaceAnalysisConfig(
                sentences = 1, prudences = listOf(Prudence.BOLD), sigma = 0.0, seed = 7L, cacheSize = 3, threads = 1, unigramsOnly = false, examples = 0,
            )

            assertThat(c.sentences).isEqualTo(1)
            assertThat(c.prudences).containsExactly(Prudence.BOLD)
            assertThat(c.sigma).isEqualTo(0.0)
            assertThat(c.seed).isEqualTo(7L)
            assertThat(c.cacheSize).isEqualTo(3)
            assertThat(c.threads).isEqualTo(1)
            assertThat(c.unigramsOnly).isFalse()
            assertThat(c.examples).isZero()
            assertThat(SpaceAnalysisConfig().unigramsOnly).isTrue()
            assertThat(SpaceAnalysisConfig().seed).isEqualTo(20261123L)
            assertThat(SpaceAnalysisConfig().cacheSize).isEqualTo(200_000)
            assertThat(SpaceAnalysisConfig().sigma).isEqualTo(0.2)
            assertThatThrownBy { SpaceAnalysisConfig(sigma = Double.POSITIVE_INFINITY) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `seuls les couples de mots de lettres a à z sont injectés, a et z compris`() {
            val list = frList + listOf("azur\t1000", "zaza\t1000", "l'ami\t1000")
            val test = listOf("Azur zaza.", "Je suis l'ami.")

            val report = SpaceErrorAnalysis(small.copy(threads = 1))
                .run(listOf(input(Language.FR, list, test, KeyboardLayout.AZERTY)))

            // (azur, zaza) et (je, suis) ; ni (suis, l'ami) ni (l'ami, ...) : l'apostrophe exclut le couple.
            assertThat(report).contains("| espace oubliée | NORMAL | 2 |")
        }

        @Test
        fun `l'échantillon garde au plus le nombre de phrases demandé, et tout le corpus s'il est plus petit`() {
            val many = (1..40).map { "Je suis monsieur." }

            fun words(sentences: Int): Int {
                val report = SpaceErrorAnalysis(small.copy(sentences = sentences, threads = 1))
                    .run(listOf(input(Language.FR, frList, many, KeyboardLayout.AZERTY)))
                return Regex("\\| juste \\| NORMAL \\| non \\| (\\d+) ").find(report)!!.groupValues[1].toInt()
            }

            // Échantillon déterministe (hachage de l'indice) : trois mots par phrase gardée.
            assertThat(listOf(1, 10, 11, 39, 40, 41).map(::words)).containsExactly(0, 18, 24, 114, 120, 120)
        }

        @Test
        fun `sans exemples demandés ou sans fausse correction, aucune ligne d'exemples n'est écrite`() {
            val none = SpaceErrorAnalysis(small.copy(examples = 0, threads = 1))
                .run(listOf(input(Language.FR, richList, richTest, KeyboardLayout.AZERTY)))

            assertThat(none).doesNotContain("Espaces en trop non corrigées", "Fausses corrections les plus fréquentes")
            assertThat(rich(0.0)).doesNotContain("Fausses corrections les plus fréquentes")
        }
    }
}
