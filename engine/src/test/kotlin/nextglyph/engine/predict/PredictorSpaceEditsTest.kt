package nextglyph.engine.predict

import nextglyph.engine.input.EditOperation
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Erreurs d'espace (issue #52, §5.5) : espace sur une touche voisine, mots collés, espace en trop. */
class PredictorSpaceEditsTest {

    private companion object {
        val AMBIGUOUS_MODEL = NgramFixtures.model(listOf("abc de" to 5, "abc zz" to 5, "ab cde" to 1, "ab zz" to 9))
    }

    private val settings = PredictionSettings()

    /** `de` pèse presque toute la masse : les autres mots ont des probabilités réalistes (`je` : 0,001). */
    private val french = listOf(
        "de" to 100_000_000L,
        "je" to 100_000L,
        "suis" to 50_000L,
        "bonjour" to 40_000L,
        "monsieur" to 10_000L,
        "ordinateur" to 5_000L,
        "bon" to 8_000L,
        "jour" to 8_000L,
        "ordi" to 1L,
        "a" to 5_000_000L,
        "le" to 200_000L,
        "chat" to 3_000L,
    )
    private val frenchSet = LexiconSet(LexiconFixtures.lexicon(french, "fr"))

    private fun predictor(
        config: SpaceEditConfig = SpaceEditConfig(),
        rejections: RejectionStore = InMemoryRejectionStore(),
        blocked: (String) -> Boolean = { false },
    ) = Predictor(
        mapOf("fr" to frenchSet), KeyboardLayouts.AZERTY, PredictionConfig(spaceEdits = config), rejections, blockedAutocommit = blocked,
    )

    private val plain = predictor()
    private val state = plain.initialLanguageState("fr")

    /** Frappes au centre de leur touche, sauf aux positions de [overrides]. */
    private fun taps(word: String, overrides: Map<Int, TypedTap> = emptyMap()): WordTaps =
        WordTaps(word.mapIndexed { i, c -> overrides[i] ?: TypedTap(c, 0.0, 0.0) })

    private fun low(word: String, index: Int, dy: Double = 0.4) = taps(word, mapOf(index to TypedTap(word[index], 0.0, dy)))

    /** `abcde` se coupe en `ab cde` ou `abc de` : seuls les bigrammes les départagent. */
    private fun ambiguous() = Predictor(
        mapOf(
            "fr" to LexiconSet(
                LexiconFixtures.lexicon(listOf("zz" to 100_000_000L, "ab" to 1000L, "cde" to 1000L, "abc" to 1000L, "de" to 1000L), "fr"),
            ),
        ),
        KeyboardLayouts.AZERTY,
        PredictionConfig(spaceEdits = SpaceEditConfig(minScore = 1e-12)),
    )

    private fun probability(word: String): Double =
        frenchSet.displayForms(word).sumOf { it.frequency.toDouble() } / frenchSet.totalMass

    @Nested
    inner class HappyPath {
        @Test
        fun `deux mots collés sont séparés par l'espace`() {
            val p = plain.predict("Il dit jesuis", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(p.inserted).isEqualTo("je suis")
            assertThat(p.span).isEqualTo("jesuis")
            assertThat(p.spaceLabel).isEqualTo("je suis")
            assertThat(p.candidates.first().key).isEqualTo("je suis")
        }

        @Test
        fun `la casse tapée est conservée sur le premier mot`() {
            val p = plain.predict("Jesuis", state, settings)

            assertThat(p.inserted).isEqualTo("Je suis")
        }

        @Test
        fun `un mot tapé tout en majuscules donne deux mots en majuscules`() {
            val p = plain.predict("JESUIS", state, settings)

            assertThat(p.inserted).isEqualTo("JE SUIS")
        }

        @Test
        fun `une espace tapée sur n, dans le bas de la touche, est corrigée`() {
            val p = plain.predict("Il dit bonjournmonsieur", state, settings, low("bonjournmonsieur", 7))

            assertThat(p.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(p.inserted).isEqualTo("bonjour monsieur")
            assertThat(p.span).isEqualTo("bonjournmonsieur")
        }

        @Test
        fun `les lettres b, v et c voisines de la barre d'espace sont corrigées comme n`() {
            for (letter in "bvc") {
                val typed = "bonjour${letter}monsieur"

                val p = plain.predict(typed, state, settings, low(typed, 7))

                assertThat(p.inserted).describedAs("lettre $letter").isEqualTo("bonjour monsieur")
            }
        }

        @Test
        fun `une espace en trop entre deux fragments inconnus est supprimée`() {
            val p = plain.predict("Il dit ordina teur", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(p.inserted).isEqualTo("ordinateur")
            assertThat(p.word).isEqualTo("teur")
            assertThat(p.span).isEqualTo("ordina teur")
            assertThat(p.bar.slots.map { it.text }).containsExactly("ordina teur", "ordinateur")
            assertThat(p.bar.center?.highlighted).isTrue()
            assertThat(p.bar.left?.isTyped).isTrue()
        }

        @Test
        fun `un fragment très rare mais connu compte comme une faute`() {
            val p = plain.predict("ordi nateur", state, settings)

            assertThat(p.inserted).isEqualTo("ordinateur")
            assertThat(p.span).isEqualTo("ordi nateur")
        }

        @Test
        fun `un second fragment très rare est absorbé par l'espace en trop`() {
            val rare = LexiconSet(LexiconFixtures.lexicon(french + ("nateur" to 1L), "fr"))
            val predictor = Predictor(mapOf("fr" to rare), KeyboardLayouts.AZERTY)

            val p = predictor.predict("ordi nateur", predictor.initialLanguageState("fr"), settings)

            assertThat(p.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(p.inserted).isEqualTo("ordinateur")
        }

        @Test
        fun `la validation d'une coupure remplace le mot et ajoute l'espace automatique`() {
            val p = plain.predict("Il dit jesuis", state, settings)

            val commit = plain.commitSpace(p, state, settings)

            assertThat(commit.edit.applyTo("Il dit jesuis")).isEqualTo("Il dit je suis ")
            assertThat(commit.edit.endsWithAutoSpace).isTrue()
            assertThat(commit.undo).isEqualTo(UndoToken("jesuis", "je suis", SpaceCase.DOMINANT))
        }

        @Test
        fun `la validation d'une espace en trop remplace aussi le mot précédent`() {
            val p = plain.predict("Il dit ordina teur", state, settings)

            val commit = plain.commitSpace(p, state, settings)

            assertThat(commit.edit.applyTo("Il dit ordina teur")).isEqualTo("Il dit ordinateur ")
            assertThat(commit.undo).isEqualTo(UndoToken("ordina teur", "ordinateur", SpaceCase.DOMINANT))
        }

        @Test
        fun `la validation avant une ponctuation applique la correction sans espace`() {
            val p = plain.predict("Il dit ordina teur", state, settings)

            val commit = plain.commitBeforePunctuation(p, state, settings)

            assertThat(commit.edit.applyTo("Il dit ordina teur")).isEqualTo("Il dit ordinateur")
            assertThat(commit.undo).isNull()
        }

        @Test
        fun `l'annulation d'une coupure restitue exactement le texte tapé`() {
            val p = plain.predict("Il dit jesuis", state, settings)
            val commit = plain.commitSpace(p, state, settings)

            val undone = plain.undo("Il dit je suis ", commit.undo!!, settings)

            assertThat(undone!!.applyTo("Il dit je suis ")).isEqualTo("Il dit jesuis")
        }

        @Test
        fun `l'annulation d'une espace en trop restitue les deux fragments et leur espace`() {
            val p = plain.predict("Il dit ordina teur", state, settings)
            val commit = plain.commitSpace(p, state, settings)

            val undone = plain.undo("Il dit ordinateur ", commit.undo!!, settings)

            assertThat(undone!!.applyTo("Il dit ordinateur ")).isEqualTo("Il dit ordina teur")
        }

        @Test
        fun `l'annulation d'une espace tapée sur n restitue la lettre`() {
            val p = plain.predict("bonjournmonsieur", state, settings, low("bonjournmonsieur", 7))
            val commit = plain.commitSpace(p, state, settings)

            val undone = plain.undo("bonjour monsieur ", commit.undo!!, settings)

            assertThat(undone!!.applyTo("bonjour monsieur ")).isEqualTo("bonjournmonsieur")
        }

        @Test
        fun `un tap sur une espace en trop supprimée remplace aussi le mot précédent`() {
            val p = Prediction(
                "teur", "teur", SpaceCase.LITERAL, "teur",
                listOf(Candidate("ordina teur", "ordinateur", 0.5, "ordina teur")), SuggestionBar.EMPTY, "fr", "teur",
            )

            val commit = plain.pickSuggestion(p, Suggestion("ordinateur"), state, settings)

            assertThat(commit.edit.applyTo("Il dit ordina teur")).isEqualTo("Il dit ordinateur ")
        }

        @Test
        fun `un tap sur une coupure remplace seulement le mot en cours`() {
            val p = Prediction(
                "jesuis", "jesuis", SpaceCase.LITERAL, "jesuis",
                listOf(Candidate("je suis", "je suis", 0.5)), SuggestionBar.EMPTY, "fr",
            )

            val commit = plain.pickSuggestion(p, Suggestion("je suis"), state, settings)

            assertThat(commit.edit.applyTo("Il dit jesuis")).isEqualTo("Il dit je suis ")
        }

        @Test
        fun `garder le texte tapé après une espace en trop supprimée remet les deux fragments`() {
            val p = plain.predict("Il dit ordina teur", state, settings)

            val commit = plain.pickSuggestion(p, p.bar.left!!, state, settings)

            assertThat(commit.edit.applyTo("Il dit ordina teur")).isEqualTo("Il dit ordina teur ")
        }

        @Test
        fun `le bigramme départage deux coupures`() {
            val withModel = ambiguous()
            val s = withModel.initialLanguageState("fr")
            assertThat(withModel.predict("abcde", s, settings).case).isEqualTo(SpaceCase.LITERAL)

            withModel.useNgrams(mapOf("fr" to AMBIGUOUS_MODEL))

            val p = withModel.predict("abcde", s, settings)
            assertThat(p.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(p.inserted).isEqualTo("abc de")
        }

        @Test
        fun `un modèle retiré rend les unigrammes`() {
            val predictor = ambiguous()
            predictor.useNgrams(mapOf("fr" to AMBIGUOUS_MODEL))

            predictor.useNgrams(emptyMap())

            assertThat(predictor.predict("abcde", predictor.initialLanguageState("fr"), settings).case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `les deux mots d'une coupure comptent pour le score de langue`() {
            val bilingual = Predictor(
                mapOf(
                    "fr" to frenchSet,
                    "en" to LexiconSet(LexiconFixtures.lexicon(listOf("the" to 20_000L, "cat" to 10_000L, "de" to 100_000_000L), "en")),
                ),
                KeyboardLayouts.AZERTY,
            )
            val s = bilingual.initialLanguageState("fr")
            val p = bilingual.predict("thecat", s, settings)

            val commit = bilingual.commitSpace(p, s, settings)

            assertThat(p.inserted).isEqualTo("the cat")
            assertThat(commit.language.weight("en")).isGreaterThan(s.weight("en"))
        }

        @Test
        fun `une correction annulée assez souvent n'est plus appliquée`() {
            val rejections = InMemoryRejectionStore()
            val predictor = predictor(rejections = rejections)
            val token = UndoToken("jesuis", "je suis", SpaceCase.DOMINANT)
            assertThat(predictor.isRefusalLearned(token)).isFalse()

            repeat(2) { predictor.undo("je suis ", token, settings) }

            assertThat(predictor.isRefusalLearned(token)).isTrue()
            val p = predictor.predict("jesuis", state, settings)
            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.inserted).isEqualTo("jesuis")
            assertThat(p.candidates.first().text).isEqualTo("je suis")
        }

        @Test
        fun `une espace en trop annulée assez souvent n'est plus supprimée`() {
            val rejections = InMemoryRejectionStore()
            val predictor = predictor(rejections = rejections)
            val token = UndoToken("ordina teur", "ordinateur", SpaceCase.DOMINANT)

            repeat(2) { predictor.undo("ordinateur ", token, settings) }

            val p = predictor.predict("ordina teur", state, settings)
            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.span).isEqualTo("teur")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `sans position de frappe, aucune espace n'est supposée sur n`() {
            val p = plain.predict("bonjournmonsieur", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.inserted).isEqualTo("bonjournmonsieur")
        }

        @Test
        fun `une frappe sur n dans le haut de la touche n'est pas une espace`() {
            val p = plain.predict("bonjournmonsieur", state, settings, low("bonjournmonsieur", 7, dy = 0.1))

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `une lettre qui n'est pas voisine de la barre d'espace n'est jamais une espace`() {
            val typed = "bonjourgmonsieur"

            val p = plain.predict(typed, state, settings, low(typed, 7))

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `une position inconnue pour la lettre n'est pas une espace`() {
            val typed = "bonjournmonsieur"
            val unknown = WordTaps(typed.mapIndexed { i, c -> if (i == 7) null else TypedTap(c, 0.0, 0.0) })

            val p = plain.predict(typed, state, settings, unknown)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `un mot du lexique n'est jamais coupé`() {
            val p = plain.predict("bonjour", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.WORD)
            assertThat(p.candidates.map { it.key }).noneMatch { ' ' in it }
        }

        @Test
        fun `sans validation automatique, aucune correction d'espace n'est proposée`() {
            val p = plain.predict("jesuis", state, PredictionSettings(autocommit = false))

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.candidates.map { it.key }).noneMatch { ' ' in it }
        }

        @Test
        fun `un champ sans suggestion ne coupe rien`() {
            val p = plain.predict("jesuis", state, PredictionSettings(noSuggestions = true))

            assertThat(p.case).isEqualTo(SpaceCase.DISABLED)
        }

        @Test
        fun `un mot bloqué n'est pas inséré par une coupure mais reste proposé`() {
            val blocking = predictor(blocked = { it == "suis" })

            val p = blocking.predict("jesuis", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.candidates.first().text).isEqualTo("je suis")
        }

        @Test
        fun `un mot bloqué n'est pas inséré par une espace supprimée`() {
            val blocking = predictor(blocked = { it == "ordinateur" })

            val p = blocking.predict("ordina teur", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `deux fragments qui ne forment pas de mots ne sont pas coupés`() {
            val p = plain.predict("xyzabc", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.candidates).isEmpty()
        }

        @Test
        fun `un fragment trop rare interdit la coupure`() {
            val p = plain.predict("jeordi", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `une coupure trop improbable n'est pas retenue`() {
            val strict = predictor(SpaceEditConfig(minScore = 0.01))

            val p = strict.predict("jesuis", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.candidates).isEmpty()
        }

        @Test
        fun `un mot précédent connu empêche la suppression de l'espace`() {
            val p = plain.predict("Je teur", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(p.span).isEqualTo("teur")
        }

        @Test
        fun `un second mot connu et courant empêche la suppression de l'espace`() {
            val p = plain.predict("ordina suis", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.WORD)
        }

        @Test
        fun `deux mots courants dont la concaténation est un mot ne sont pas joints`() {
            val p = plain.predict("bon jour", state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.WORD)
            assertThat(p.inserted).isEqualTo("jour")
        }

        @Test
        fun `une ponctuation entre les deux fragments empêche la suppression`() {
            for (separator in listOf(", ", ". ", "' ", "- ")) {
                val p = plain.predict("ordina${separator}teur", state, settings)

                assertThat(p.span).describedAs(separator).isEqualTo("teur")
            }
        }

        @Test
        fun `une espace insécable, une double espace ou un saut de ligne ne sont pas une espace en trop`() {
            for (separator in listOf(" ", "  ", "\n", " ")) {
                val p = plain.predict("ordina${separator}teur", state, settings)

                assertThat(p.case).describedAs("« $separator »").isEqualTo(SpaceCase.LITERAL)
            }
        }

        @Test
        fun `un mot précédent collé à un chiffre ou à un trait d'union n'est pas un fragment`() {
            for (head in listOf("12ordina", "peut-ordina", "l'ordina")) {
                val p = plain.predict("$head teur", state, settings)

                assertThat(p.span).describedAs(head).isEqualTo("teur")
            }
        }

        @Test
        fun `sans mot précédent, le premier mot du champ n'est pas joint`() {
            val p = plain.predict("teur", state, settings)

            assertThat(p.span).isEqualTo("teur")
        }

        @Test
        fun `un texte indisponible ne donne aucun mot précédent`() {
            val p = plain.predict(null, state, settings)

            assertThat(p.case).isEqualTo(SpaceCase.NO_WORD)
            assertThat(p.span).isEmpty()
        }

        @Test
        fun `un jeton d'annulation périmé ne s'applique pas`() {
            val token = UndoToken("ordina teur", "ordinateur", SpaceCase.DOMINANT)

            assertThat(plain.undo("ordinateur", token, settings)).isNull()
            assertThat(plain.undo(null, token, settings)).isNull()
        }

        @Test
        fun `la configuration refuse des valeurs hors limites`() {
            val invalid = listOf<() -> Any>(
                { SpaceEditConfig(spaceBarWeight = 1.1) },
                { SpaceEditConfig(gluedWeight = -0.1) },
                { SpaceEditConfig(joinWeight = 2.0) },
                { SpaceEditConfig(unigramBackoff = 1.5) },
                { SpaceEditConfig(minTapDy = -0.01) },
                { SpaceEditConfig(minPartProbability = 1.5) },
                { SpaceEditConfig(minSingleLetterProbability = -1.0) },
                { SpaceEditConfig(minScore = 2.0) },
                { SpaceEditConfig(rareWordProbability = -0.5) },
                { SpaceEditConfig(minJoinProbability = 1.1) },
            )

            for (build in invalid) {
                org.assertj.core.api.Assertions.assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une frappe à exactement minTapDy est une espace, juste en dessous non`() {
            val typed = "bonjournmonsieur"

            val at = plain.predict(typed, state, settings, low(typed, 7, dy = 0.25))
            val below = plain.predict(typed, state, settings, low(typed, 7, dy = 0.2499))

            assertThat(at.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(below.case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `la lettre voisine en première ou en dernière position n'est pas une espace`() {
            val first = plain.predict("nbonjour", state, settings, low("nbonjour", 0))
            val last = plain.predict("bonjourn", state, settings, low("bonjourn", 7))

            // La lettre en trop est corrigée comme une faute de frappe (§5.5, cas 2), jamais coupée en deux mots.
            assertThat(first.candidates.map { it.key }).noneMatch { ' ' in it }
            assertThat(last.candidates.map { it.key }).noneMatch { ' ' in it }
        }

        @Test
        fun `une seule lettre n'est séparée que si c'est un mot très fréquent`() {
            val pa = probability("a")
            val permissive = predictor(SpaceEditConfig(minSingleLetterProbability = pa))
            val strict = predictor(SpaceEditConfig(minSingleLetterProbability = pa * 1.01))

            assertThat(permissive.predict("ajour", state, settings).inserted).isEqualTo("a jour")
            // Sans la coupure, la lettre en trop est corrigée comme une faute de frappe.
            assertThat(strict.predict("ajour", state, settings).candidates.map { it.key }).noneMatch { ' ' in it }
        }

        @Test
        fun `une coupure au score exactement minScore est retenue, juste en dessous non`() {
            val expected = 0.5 * probability("je") * probability("suis")

            val atLimit = predictor(SpaceEditConfig(minScore = expected))
            val above = predictor(SpaceEditConfig(minScore = expected * 1.0001))

            assertThat(atLimit.predict("jesuis", state, settings).case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(above.predict("jesuis", state, settings).case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `un mot de coupure à exactement minPartProbability est accepté, juste en dessous non`() {
            val limit = probability("suis")

            val atLimit = predictor(SpaceEditConfig(minPartProbability = limit))
            val above = predictor(SpaceEditConfig(minPartProbability = limit * 1.01))

            assertThat(atLimit.predict("jesuis", state, settings).case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(above.predict("jesuis", state, settings).case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `un fragment est très rare sous rareWordProbability, pas à cette valeur`() {
            val limit = probability("bon")
            val rare = predictor(SpaceEditConfig(rareWordProbability = limit * 1.01))
            val common = predictor(SpaceEditConfig(rareWordProbability = limit))

            // « bon » et « jour » sont des mots : la jonction ne se fait que s'ils sont tous deux très rares.
            assertThat(rare.predict("bon jour", state, settings).inserted).isEqualTo("bonjour")
            assertThat(common.predict("bon jour", state, settings).case).isEqualTo(SpaceCase.WORD)
        }

        @Test
        fun `le mot obtenu doit atteindre minJoinProbability`() {
            val limit = probability("ordinateur")

            val atLimit = predictor(SpaceEditConfig(minJoinProbability = limit))
            val above = predictor(SpaceEditConfig(minJoinProbability = limit * 1.01))

            assertThat(atLimit.predict("ordina teur", state, settings).inserted).isEqualTo("ordinateur")
            assertThat(above.predict("ordina teur", state, settings).case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `le poids d'une coupure change son score mais pas le mot proposé`() {
            val light = predictor(SpaceEditConfig(gluedWeight = 0.01))

            val p = light.predict("jesuis", state, settings)

            assertThat(p.candidates.single().text).isEqualTo("je suis")
            assertThat(p.candidates.single().probability).isEqualTo(1.0)
        }

        @Test
        fun `un mot de deux lettres n'a aucune coupure`() {
            val p = plain.predict("jx", state, settings)

            assertThat(p.candidates.map { it.key }).noneMatch { ' ' in it }
        }

        @Test
        fun `la coupure d'un mot qui change de longueur à la normalisation est ignorée`() {
            val p = plain.predict("jesuisœ", state, settings)

            assertThat(p.candidates.map { it.key }).noneMatch { ' ' in it }
        }

        @Test
        fun `le prédicteur n'a besoin d'aucun modèle de mot suivant`() {
            val fresh = Predictor(mapOf("fr" to frenchSet), KeyboardLayouts.AZERTY)

            val p = fresh.predict("jesuis", fresh.initialLanguageState("fr"), settings)

            assertThat(p.inserted).isEqualTo("je suis")
            assertThat(p.span).isEqualTo("jesuis")
            assertThat(commitOperations(fresh, p)).containsExactly(EditOperation.DeleteBeforeCursor(4), EditOperation.InsertText(" suis "))
        }

        private fun commitOperations(predictor: Predictor, p: Prediction) = predictor.commitSpace(p, predictor.initialLanguageState("fr"), settings).edit.operations
    }
}
