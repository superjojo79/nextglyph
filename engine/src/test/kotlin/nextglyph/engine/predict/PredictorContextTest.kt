package nextglyph.engine.predict

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramFixtures
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramModelBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.ByteBuffer

/** Contexte de la correction (issue #55, §5.5) : formes confondues décidées par le mot précédent, mots techniques. */
class PredictorContextTest {

    private val french = listOf(
        "il" to 5000L, "va" to 800L, "avec" to 900L, "elle" to 4000L, "ville" to 300L, "je" to 6000L,
        "a" to 5000L, "à" to 4000L, "ou" to 1000L, "où" to 900L, "la" to 8000L, "là" to 700L, "du" to 3000L, "dû" to 50L,
        "ces" to 600L, "ses" to 900L, "et" to 9000L, "est" to 8000L, "école" to 100L, "l'" to 3000L, "qu'" to 2000L,
        "aujourd'hui" to 200L, "peut-être" to 100L, "bonjour" to 200L, "amis" to 100L,
    )
    private val lexicon = LexiconSet(LexiconFixtures.lexicon(french, "fr"))
    private val english = LexiconSet(LexiconFixtures.lexicon(listOf("the" to 100L, "a" to 100L, "I" to 100L), "en"))
    private val settings = PredictionSettings()

    /** Modèle de mot suivant dont les comptes de confusion sont `(mot précédent, forme, occurrences)`. */
    private fun model(vararg counts: Triple<String, String, Int>): NgramModel {
        val builder = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL, confusionSets = ConfusionConfig.FRENCH_SETS.map { it.forms })
        builder.addSentence(french.map { it.first }.filter { it.first().isLetter() && '\'' !in it && '-' !in it })
        for ((previous, form, count) in counts) builder.addConfusionContext(listOf(previous, form), count)
        return NgramModel.load(ByteBuffer.wrap(builder.build()))
    }

    private val counts = model(
        Triple("il", "a", 990), Triple("il", "à", 10),
        Triple("va", "a", 5), Triple("va", "à", 995),
        Triple("avec", "ses", 400), Triple("avec", "ces", 5),
        Triple("ville", "a", 55), Triple("ville", "à", 45),
        Triple("elle", "ses", 50), Triple("elle", "ces", 50),
        Triple("je", "et", 3), Triple("je", "est", 2),
        Triple("ville", "ou", 40), Triple("ville", "où", 60),
    )

    private fun predictor(
        model: NgramModel? = counts,
        config: PredictionConfig = PredictionConfig(),
        rejections: RejectionStore = InMemoryRejectionStore(),
    ): Predictor = Predictor(mapOf("fr" to lexicon), KeyboardLayouts.AZERTY, config, rejections).also {
        if (model != null) it.useNgrams(mapOf("fr" to model))
    }

    private val fr = predictor()
    private val state = fr.initialLanguageState("fr")

    private fun Predictor.at(text: String, s: PredictionSettings = settings) = predict(text, initialLanguageState("fr"), s)

    @Nested
    inner class HappyPath {
        @Test
        fun `a est remplacé par à quand le mot précédent est presque toujours suivi de à`() {
            val p = fr.at("on va a")

            assertThat(p.case).isEqualTo(SpaceCase.WORD)
            assertThat(p.inserted).isEqualTo("à")
            assertThat(p.spaceLabel).isEqualTo("à")
            assertThat(p.bar.center).isEqualTo(Suggestion("à", highlighted = true))
            assertThat(p.bar.slots).contains(Suggestion("a", isTyped = true))
        }

        @Test
        fun `a reste a quand le mot précédent est presque toujours suivi de a`() {
            val p = fr.at("on il a")

            assertThat(p.inserted).isEqualTo("a")
            assertThat(p.spaceLabel).isNull()
            assertThat(p.bar.center).isEqualTo(Suggestion("a"))
        }

        @Test
        fun `ces est remplacé par ses quand c'est presque toujours ses`() {
            val p = fr.at("il part avec ces")

            assertThat(p.inserted).isEqualTo("ses")
            assertThat(p.case).isEqualTo(SpaceCase.WORD)
        }

        @Test
        fun `une initiale majuscule en milieu de phrase est voulue et n'est jamais changée`() {
            assertThat(fr.at("on va A").inserted).isEqualTo("A")
            assertThat(fr.at("il part avec Ces").inserted).isEqualTo("Ces")
        }

        @Test
        fun `une forme à peu près aussi probable est proposée en premier dans la barre sans être insérée`() {
            val p = fr.at("on ville a")

            assertThat(p.inserted).isEqualTo("a")
            assertThat(p.bar.center).isEqualTo(Suggestion("a"))
            assertThat(p.bar.left).isEqualTo(Suggestion("à"))
        }

        @Test
        fun `une forme de ensemble de mots différents est aussi proposée`() {
            val p = fr.at("on elle ses")

            assertThat(p.inserted).isEqualTo("ses")
            assertThat(p.bar.left).isEqualTo(Suggestion("ces"))
        }

        @Test
        fun `ou est proposé en où quand le mot précédent le suggère sans l'imposer`() {
            val p = fr.at("la ville ou")

            assertThat(p.inserted).isEqualTo("ou")
            assertThat(p.bar.slots.map { it.text }).containsExactly("où", "ou")
        }

        @Test
        fun `la correction se valide, s'annule et est refusée après deux annulations`() {
            val rejections = InMemoryRejectionStore()
            val predictor = predictor(rejections = rejections)
            val language = predictor.initialLanguageState("fr")
            val first = predictor.predict("on va a", language, settings)

            val commit = predictor.commitSpace(first, language, settings)

            assertThat(commit.undo).isEqualTo(UndoToken("a", "à", SpaceCase.WORD))
            assertThat(commit.edit.applyTo("on va a")).isEqualTo("on va à ")
            val token = commit.undo!!
            val undone = predictor.undo("on va à ", token, settings)!!
            assertThat(undone.applyTo("on va à ")).isEqualTo("on va a")
            assertThat(predictor.isRefusalLearned(token)).isFalse()
            predictor.undo("on va à ", token, settings)
            assertThat(predictor.isRefusalLearned(token)).isTrue()
            assertThat(predictor.predict("on va a", language, settings).inserted).isEqualTo("a")
        }

        @Test
        fun `la prudence audacieuse accepte une part que la prudence normale refuse`() {
            // Une part de 0,975 sur 40 occurrences : entre le seuil audacieux (0,97) et le seuil normal (0,98).
            val model = model(Triple("elle", "a", 1), Triple("elle", "à", 39))

            val normal = predictor(model).at("on elle a", PredictionSettings(prudence = Prudence.NORMAL))
            val bold = predictor(model).at("on elle a", PredictionSettings(prudence = Prudence.BOLD))

            assertThat(normal.inserted).isEqualTo("a")
            assertThat(bold.inserted).isEqualTo("à")
        }

        @Test
        fun `l'élision précédente laisse le mot suivant lire son contexte`() {
            val p = fr.at("on dit qu'il va a")

            assertThat(p.inserted).isEqualTo("à")
        }

        @Test
        fun `un sigle n'est jamais remplacé ni restitué`() {
            val p = fr.at("il dit ECOLE")

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.inserted).isEqualTo("ECOLE")
            assertThat(p.spaceLabel).isNull()
        }

        @ParameterizedTest(name = "« {0} » reste tel quel")
        @CsvSource(
            "il écrit jean@ecole",
            "voir /ecole",
            "mon_ecole",
            "voir www.ecole",
            "voir 3.ecole",
            "voir @ecole",
        )
        fun `un mot collé à un signe technique est inséré tel quel`(text: String) {
            val p = fr.at(text)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.inserted).isEqualTo(p.word)
        }

        @Test
        fun `un mot après un point suivi d'une espace est restitué comme d'habitude`() {
            assertThat(fr.at("fin. ecole").inserted).isEqualTo("école")
            assertThat(fr.at("fin... ecole").inserted).isEqualTo("école")
        }

        @Test
        fun `un mot contenant un chiffre n'est jamais un mot en cours`() {
            assertThat(fr.at("voir b2b").case).isEqualTo(SpaceCase.NO_WORD)
            assertThat(fr.at("voir A320").case).isEqualTo(SpaceCase.NO_WORD)
            assertThat(fr.at("voir x86").case).isEqualTo(SpaceCase.NO_WORD)
        }

        @Test
        fun `la protection peut être désactivée dans la configuration`() {
            val open = predictor(config = PredictionConfig(confusion = ConfusionConfig(protectTechnical = false)))

            assertThat(open.at("il dit ECOLE").inserted).isEqualTo("ÉCOLE")
            assertThat(open.at("il écrit jean@ecole").inserted).isEqualTo("école")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `sans mot précédent rien ne change`() {
            assertThat(fr.at("a").inserted).isEqualTo("a")
            assertThat(fr.at("va. a").inserted).isEqualTo("a")
            assertThat(fr.at("va, a").inserted).isEqualTo("a")
        }

        @Test
        fun `un mot précédent inconnu du modèle ne change rien`() {
            assertThat(fr.at("on zzz a").inserted).isEqualTo("a")
            assertThat(fr.at("on amis a").inserted).isEqualTo("a")
        }

        @Test
        fun `un accent tapé n'est jamais changé`() {
            val p = fr.at("on il à")

            assertThat(p.inserted).isEqualTo("à")
            assertThat(p.spaceLabel).isNull()
        }

        @Test
        fun `sans modèle de mot suivant aucune confusion n'est corrigée`() {
            val bare = predictor(model = null)

            assertThat(bare.at("on va a").inserted).isEqualTo("a")
        }

        @Test
        fun `un modèle sans les ensembles ne corrige rien`() {
            val other = NgramModel.load(ByteBuffer.wrap(NgramFixtures.bytes()))

            assertThat(predictor(other).at("on va a").inserted).isEqualTo("a")
        }

        @Test
        fun `la validation désactivée garde le texte tapé et propose quand même la forme`() {
            val p = fr.at("il part avec ces", PredictionSettings(autocommit = false))

            assertThat(p.inserted).isEqualTo("ces")
            assertThat(p.bar.center).isEqualTo(Suggestion("ces"))
            assertThat(p.bar.left).isEqualTo(Suggestion("ses"))
        }

        @Test
        fun `un champ sans suggestions n'a ni correction ni barre`() {
            val p = fr.at("on va a", PredictionSettings(noSuggestions = true))

            assertThat(p.case).isEqualTo(SpaceCase.DISABLED)
        }

        @Test
        fun `un ensemble non automatique est seulement proposé`() {
            val sets = listOf(ConfusionSet(listOf("a", "à"), auto = false))
            val manual = predictor(config = PredictionConfig(confusion = ConfusionConfig(sets = mapOf("fr" to sets))))

            val p = manual.at("on va a")

            assertThat(p.inserted).isEqualTo("a")
            assertThat(p.bar.left).isEqualTo(Suggestion("à"))
        }

        @Test
        fun `une correction refusée deux fois n'est plus appliquée`() {
            val rejections = InMemoryRejectionStore()
            rejections.recordRejection("a", "à")
            rejections.recordRejection("a", "à")

            assertThat(predictor(rejections = rejections).at("on va a").inserted).isEqualTo("a")
        }

        @Test
        fun `un mot précédent collé à un trait d'union, un chiffre ou une apostrophe non élidée n'est pas lu`() {
            assertThat(fr.at("on peut-va a").inserted).isEqualTo("a")
            assertThat(fr.at("on 2va a").inserted).isEqualTo("a")
            assertThat(fr.at("on aujourd'va a").inserted).isEqualTo("a")
            assertThat(fr.at("on mail@va a").inserted).isEqualTo("a")
        }

        @Test
        fun `une phrase en anglais n'applique pas les confusions françaises`() {
            val bilingual = Predictor(mapOf("fr" to lexicon, "en" to english), KeyboardLayouts.AZERTY)
            bilingual.useNgrams(mapOf("fr" to counts))
            val language = bilingual.initialLanguageState("en")

            assertThat(bilingual.predict("on va a", language, settings).inserted).isEqualTo("a")
        }

        @Test
        fun `un mot de la fenêtre lue tronqué n'est pas lu`() {
            val text = "x".repeat(70) + "va a"

            assertThat(fr.at(text).inserted).isEqualTo("a")
        }

        @Test
        fun `un acronyme précédent n'est jamais joint au mot suivant`() {
            val p = fr.at("SNCF ecol")

            assertThat(p.case).isNotEqualTo(SpaceCase.NO_WORD)
            assertThat(p.span).isEqualTo("ecol")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une part exactement au seuil est acceptée`() {
            // 49 / 50 = 0,98, seuil de la prudence normale, 50 occurrences (seuil 30).
            val model = model(Triple("elle", "a", 1), Triple("elle", "à", 49))

            assertThat(predictor(model).at("on elle a").inserted).isEqualTo("à")
        }

        @Test
        fun `une part juste sous le seuil est refusée`() {
            val model = model(Triple("elle", "a", 2), Triple("elle", "à", 48))

            assertThat(predictor(model).at("on elle a").inserted).isEqualTo("a")
        }

        @Test
        fun `un nombre d'occurrences exactement au seuil est accepté, un de moins est refusé`() {
            val atThreshold = model(Triple("elle", "à", Prudence.NORMAL.confusionCount))
            val below = model(Triple("elle", "à", Prudence.NORMAL.confusionCount - 1))

            assertThat(predictor(atThreshold).at("on elle a").inserted).isEqualTo("à")
            assertThat(predictor(below).at("on elle a").inserted).isEqualTo("a")
        }

        @Test
        fun `la proposition exige au moins minOfferCount occurrences et la part offerShare`() {
            val minimum = ConfusionConfig().minOfferCount
            assertThat(minimum).isEqualTo(5)
            val enough = model(Triple("elle", "ses", 2), Triple("elle", "ces", 3))
            val few = model(Triple("elle", "ses", 2), Triple("elle", "ces", 2))
            val under = model(Triple("elle", "ses", 3), Triple("elle", "ces", 2))

            assertThat(predictor(enough).at("on elle ses").bar.left).isEqualTo(Suggestion("ces"))
            assertThat(predictor(few).at("on elle ses").bar.slots.map { it.text }).containsExactly("ses")
            assertThat(predictor(under).at("on elle ses").bar.slots.map { it.text }).containsExactly("ses")
        }

        @Test
        fun `les trois prudences ont des seuils de plus en plus souples`() {
            assertThat(Prudence.CAUTIOUS.confusionShare).isGreaterThan(Prudence.NORMAL.confusionShare)
            assertThat(Prudence.NORMAL.confusionShare).isGreaterThan(Prudence.BOLD.confusionShare)
            assertThat(Prudence.CAUTIOUS.confusionCount).isGreaterThan(Prudence.NORMAL.confusionCount)
            assertThat(Prudence.NORMAL.confusionCount).isGreaterThan(Prudence.BOLD.confusionCount)
        }

        @Test
        fun `un seul mot de deux lettres majuscules est un sigle, une seule majuscule n'en est pas un`() {
            assertThat(fr.at("il dit OU").case).isEqualTo(SpaceCase.LITERAL)
            assertThat(fr.at("il dit Ou").case).isEqualTo(SpaceCase.WORD)
            assertThat(fr.at("il dit A").case).isNotEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `un point en tout début de texte ou après un autre point ne rend pas le mot technique`() {
            assertThat(fr.at(".ecole").inserted).isEqualTo("école")
            assertThat(fr.at("a.ecole").case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `un texte indisponible ne donne rien`() {
            val p = fr.predict(null, state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.NO_WORD)
        }

        @Test
        fun `le mot précédent est lu juste avant une seule espace`() {
            assertThat(fr.at("on va  a").inserted).isEqualTo("a")
            assertThat(fr.at("on va a").inserted).isEqualTo("à")
        }
    }
}
