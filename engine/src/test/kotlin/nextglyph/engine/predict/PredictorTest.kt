package nextglyph.engine.predict

import nextglyph.engine.input.EditOperation
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PredictorTest {

    private val settings = PredictionSettings()
    private val fr = PredictFixtures.frenchPredictor()
    private val frState = fr.initialLanguageState("fr")
    private val en = PredictFixtures.englishPredictor()
    private val enState = en.initialLanguageState("en")

    private fun Predictor.at(text: String, state: LanguageState, s: PredictionSettings = settings) = predict(text, state, s)

    @Nested
    inner class HappyPath {

        @ParameterizedTest(name = "« {0} » → « {1} » ({2})")
        @CsvSource(
            "bonj, bonjour, DOMINANT",
            "bonjoir, bonjour, DOMINANT",
            "cest, c'est, DOMINANT",
            "jai, j'ai, DOMINANT",
            "lecole, l'école, DOMINANT",
            "Lecole, L'école, DOMINANT",
            "peutetre, peut-être, DOMINANT",
            "ecole, école, WORD",
            "Ecole, École, WORD",
            "ECOLE, ÉCOLE, WORD",
            "a, a, WORD",
            "ou, ou, WORD",
            "paris, paris, WORD",
            "Paris, Paris, WORD",
            "dont, dont, WORD",
            "xyzw, xyzw, LITERAL",
        )
        fun `validation par espace en français`(typed: String, inserted: String, case: SpaceCase) {
            val prediction = fr.at("Il dit $typed", frState)

            assertThat(prediction.word).isEqualTo(typed)
            assertThat(prediction.case).isEqualTo(case)
            assertThat(prediction.inserted).isEqualTo(inserted)
            assertThat(prediction.spaceLabel).isEqualTo(if (inserted == typed) null else inserted)
        }

        @ParameterizedTest(name = "« {0} » → « {1} » ({2})")
        @CsvSource(
            "dont, don't, DOMINANT",
            "im, I'm, DOMINANT",
            "i, I, WORD",
            "its, its, WORD",
            "well, well, WORD",
            "monday, Monday, WORD",
            "hel, hello, LITERAL",
        )
        fun `validation par espace en anglais`(typed: String, inserted: String, case: SpaceCase) {
            val prediction = en.at("so $typed", enState)

            assertThat(prediction.case).isEqualTo(case)
            assertThat(prediction.inserted).isEqualTo(if (case == SpaceCase.LITERAL) typed else inserted)
        }

        @Test
        fun `la validation remplace le mot et ajoute une espace automatique annulable`() {
            val prediction = fr.at("Il dit bonj", frState)

            val commit = fr.commitSpace(prediction, frState, settings)

            assertThat(commit.edit.operations).containsExactly(EditOperation.InsertText("our "))
            assertThat(commit.edit.endsWithAutoSpace).isTrue()
            assertThat(commit.edit.applyTo("Il dit bonj")).isEqualTo("Il dit bonjour ")
            assertThat(commit.undo).isEqualTo(UndoToken("bonj", "bonjour"))
            val undo = fr.undo("Il dit bonjour ", commit.undo!!, settings)
            assertThat(undo!!.applyTo("Il dit bonjour ")).isEqualTo("Il dit bonj")
            assertThat(undo.endsWithAutoSpace).isFalse()
        }

        @Test
        fun `un mot gardé tel quel n'a pas de jeton d'annulation`() {
            val commit = fr.commitSpace(fr.at("le", frState), frState, settings)

            assertThat(commit.edit.operations).containsExactly(EditOperation.InsertText(" "))
            assertThat(commit.edit.endsWithAutoSpace).isTrue()
            assertThat(commit.undo).isNull()
        }

        @Test
        fun `une correction efface puis réécrit à partir du début commun`() {
            val commit = fr.commitSpace(fr.at("bonjoir", frState), frState, settings)

            assertThat(commit.edit.operations).containsExactly(EditOperation.DeleteBeforeCursor(2), EditOperation.InsertText("ur "))
        }

        @Test
        fun `l'apprentissage du refus bloque la correction après rejectLimit annulations`() {
            val store = InMemoryRejectionStore()
            val predictor = PredictFixtures.frenchPredictor(store)
            val state = predictor.initialLanguageState("fr")
            repeat(2) {
                val commit = predictor.commitSpace(predictor.at("cest", state), state, settings)
                assertThat(predictor.at("cest", state).case).isEqualTo(SpaceCase.DOMINANT)
                predictor.undo("c'est ", commit.undo!!, settings)
            }

            val after = predictor.at("cest", state)

            assertThat(store.rejections("cest", "c'est")).isEqualTo(2)
            assertThat(after.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(after.inserted).isEqualTo("cest")
            assertThat(after.bar.slots.map { it.text }).contains("c'est")
        }

        @Test
        fun `le refus d'une restitution d'accent la supprime aussi`() {
            val store = InMemoryRejectionStore()
            repeat(2) { store.recordRejection("ecole", "école") }

            val prediction = PredictFixtures.frenchPredictor(store).at("ecole", frState)

            assertThat(prediction.case).isEqualTo(SpaceCase.WORD)
            assertThat(prediction.inserted).isEqualTo("ecole")
        }

        @Test
        fun `barre - candidat dominant surligné au centre, texte tapé à gauche`() {
            val bar = fr.at("bonjoir", frState).bar

            assertThat(bar.center).isEqualTo(Suggestion("bonjour", highlighted = true))
            assertThat(bar.left).isEqualTo(Suggestion("bonjoir", isTyped = true))
        }

        @Test
        fun `barre - un mot ambigu propose ses variantes sans les insérer`() {
            val prediction = fr.at("a", frState)

            assertThat(prediction.bar.center).isEqualTo(Suggestion("a"))
            assertThat(prediction.bar.slots.map { it.text }).contains("à")
            assertThat(en.at("its", enState).bar.slots.map { it.text }).contains("it's")
            assertThat(en.at("well", enState).bar.slots.map { it.text }).contains("we'll")
        }

        @Test
        fun `barre - un mot qui cache une élision la propose`() {
            val prediction = fr.at("Lami", frState)

            assertThat(prediction.case).isEqualTo(SpaceCase.WORD)
            assertThat(prediction.inserted).isEqualTo("Lami")
            assertThat(prediction.bar.slots.map { it.text }).contains("L'ami")
            assertThat(prediction.sentenceLanguage).isEqualTo("fr")
            assertThat(prediction.key).isEqualTo("lami")
        }

        @Test
        fun `cache - mêmes résultats, taille bornée`() {
            val cached = PredictFixtures.bilingualPredictor(cacheSize = 2)
            val plain = PredictFixtures.bilingualPredictor()
            val state = cached.initialLanguageState("fr")

            for (word in listOf("bonj", "cest", "bonj", "dont", "bonj", "cest")) {
                assertThat(cached.at(word, state).candidates).isEqualTo(plain.at(word, state).candidates)
            }

            assertThat(cached.cachedTexts()).containsExactly(2, 2)
            assertThat(plain.cachedTexts()).isEmpty()
            assertThat(PredictFixtures.bilingualPredictor(cacheSize = 1).apply { at("le", state) }.cachedTexts()).containsExactly(1, 1)
        }

        @Test
        fun `bilingue - une élision observée compte pour le français`() {
            val both = PredictFixtures.bilingualPredictor()
            val start = both.initialLanguageState("en")

            val state = both.observe(start, "l'ami")

            // l' et ami : deux mots purement français.
            assertThat(state.weight("fr")).isCloseTo((2 + 0.25) / 3, within(1e-9))
            assertThat(both.observe(start, "zzz").weight("fr")).isCloseTo(0.25, within(1e-9))
            assertThat(both.observe(start, "l'").weight("fr")).isCloseTo(1.25 / 2, within(1e-9))
            assertThat(both.languages).containsExactly("fr", "en")
        }

        @Test
        fun `barre - mot restitué surligné et texte tapé proposé`() {
            val bar = fr.at("ecole", frState).bar

            assertThat(bar.center).isEqualTo(Suggestion("école", highlighted = true))
            assertThat(bar.left).isEqualTo(Suggestion("ecole", isTyped = true))
            assertThat(bar.right).isEqualTo(Suggestion("écoles"))
        }

        @Test
        fun `barre - sans candidat dominant, les candidats sont proposés sans surlignage`() {
            val bar = en.at("hel", enState).bar

            assertThat(bar.center).isEqualTo(Suggestion("hello"))
            assertThat(bar.left).isEqualTo(Suggestion("help"))
            assertThat(bar.slots).noneMatch { it.highlighted }
        }

        @Test
        fun `prudence - seuils minProb 0,8 0,7 0,6`() {
            val cats = PredictFixtures.catPredictor()
            val state = cats.initialLanguageState("fr")

            val p = cats.at("ch", state).candidates

            assertThat(p[0].probability).isCloseTo(75.0 / 100.2, within(1e-5))
            assertThat(p[1].probability).isCloseTo(25.0 / 100.2, within(1e-5))
            assertThat(cats.at("ch", state, PredictionSettings(prudence = Prudence.CAUTIOUS)).case).isEqualTo(SpaceCase.LITERAL)
            assertThat(cats.at("ch", state, PredictionSettings(prudence = Prudence.NORMAL)).case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(cats.at("ch", state, PredictionSettings(prudence = Prudence.BOLD)).case).isEqualTo(SpaceCase.DOMINANT)
        }

        @Test
        fun `bilingue - la casse de i suit la langue de la phrase`() {
            val both = PredictFixtures.bilingualPredictor()
            val start = both.initialLanguageState("fr")
            val english = both.observe(both.observe(start, "the"), "hello")

            assertThat(both.at("i", start).inserted).isEqualTo("i")
            assertThat(english.sentenceLanguage).isEqualTo("en")
            assertThat(both.at("i", english).inserted).isEqualTo("I")
        }

        @Test
        fun `bilingue - un mot anglais sans accent n'est jamais accentué`() {
            val both = PredictFixtures.bilingualPredictor()

            val prediction = both.at("resume", both.initialLanguageState("fr"))

            assertThat(fr.at("resume", frState).inserted).isEqualTo("résumé")
            assertThat(prediction.case).isEqualTo(SpaceCase.WORD)
            assertThat(prediction.inserted).isEqualTo("resume")
            assertThat(prediction.bar.slots.map { it.text }).contains("résumé")
        }

        @Test
        fun `bilingue - dont est un mot français, don't reste proposé`() {
            val both = PredictFixtures.bilingualPredictor()

            val prediction = both.at("dont", both.initialLanguageState("fr"))
            val locked = both.at("dont", both.initialLanguageState("fr").lock("en"))

            assertThat(prediction.case).isEqualTo(SpaceCase.WORD)
            assertThat(prediction.bar.slots.map { it.text }).contains("don't")
            assertThat(locked.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(locked.inserted).isEqualTo("don't")
        }

        @Test
        fun `bilingue - les candidats des deux langues sont mélangés selon π`() {
            val both = PredictFixtures.bilingualPredictor(cacheSize = 8)
            val state = both.initialLanguageState("fr")

            val prediction = both.at("he", state)

            // P(w) = Σ π_L score_L(w) / Σ π_L total_L : l'anglais connaît « he » (hello 100, help 80) ;
            // le français n'apporte que des corrections, qui diluent un peu P(hello) sous 100 / 180.
            assertThat(prediction.candidates.map { it.key }).startsWith("hello", "help")
            assertThat(prediction.candidates[0].probability).isBetween(0.5, 100.0 / 180.0)
            assertThat(both.at("he", state).candidates).isEqualTo(prediction.candidates)
        }

        @Test
        fun `distribution mélangée pour l'adaptation`() {
            val both = PredictFixtures.bilingualPredictor()
            val state = both.initialLanguageState("fr")

            val d = both.distribution("", state)
            val onlyEnglish = both.distribution("th", state)

            assertThat(d.isUnknownPrefix).isFalse()
            assertThat(d.probability('t')).isCloseTo(0.25 * 2000.0 / 4322.0, within(1e-5))
            assertThat(onlyEnglish.probability('e')).isEqualTo(1.0)
            assertThat(both.distribution("zz", state).isUnknownPrefix).isTrue()
        }

        @Test
        fun `observe - une élision compte pour elle et pour son mot`() {
            val evidence = fr.evidence("l'")

            val state = fr.observe(frState, "l'école")

            assertThat(evidence).containsExactly(1.0)
            assertThat(state.weights).containsExactly(1.0)
            assertThat(PredictFixtures.bilingualPredictor().evidence("zzz")).containsExactly(0.0, 0.0)
        }

        @Test
        fun `validation avant ponctuation et tap sur une suggestion`() {
            val prediction = fr.at("bonj", frState)

            val before = fr.commitBeforePunctuation(prediction, frState, settings)
            val picked = fr.pickSuggestion(prediction, Suggestion("bonne"), frState, settings)

            assertThat(before.edit.applyTo("bonj")).isEqualTo("bonjour")
            assertThat(before.edit.endsWithAutoSpace).isFalse()
            assertThat(before.undo).isNull()
            assertThat(picked.edit.applyTo("bonj")).isEqualTo("bonne ")
            assertThat(picked.edit.endsWithAutoSpace).isTrue()
            assertThat(picked.undo).isNull()
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette un état de langue d'un autre prédicteur`() {
            assertThatThrownBy { fr.at("bonj", enState) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("[en]")
        }

        @Test
        fun `toutes les entrées vérifient l'état de langue`() {
            val bilingual = PredictFixtures.bilingualPredictor()

            assertThatThrownBy { fr.decide("a", "a", enState, settings) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { fr.observe(enState, "a") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { fr.distribution("a", enState) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { bilingual.predict("a", bilingual.initialLanguageState("fr").let { LanguageState.initial(listOf("en", "fr"), "fr") }, settings) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejette un lexique d'une autre langue que son code`() {
            assertThatThrownBy { Predictor(mapOf("en" to PredictFixtures.french()), KeyboardLayouts.AZERTY) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("« en »")
        }

        @Test
        fun `rejette aucune langue et un cache négatif`() {
            assertThatThrownBy { Predictor(emptyMap(), KeyboardLayouts.AZERTY) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { Predictor(mapOf("fr" to PredictFixtures.french()), KeyboardLayouts.AZERTY, cacheSize = -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `l'annulation ne s'applique pas si le texte a changé`() {
            val token = UndoToken("bonj", "bonjour")

            assertThat(fr.undo("bonjour, ", token, settings)).isNull()
            assertThat(fr.undo(null, token, settings)).isNull()
        }

        @Test
        fun `incognito - aucune annulation n'est comptée`() {
            val store = InMemoryRejectionStore()
            val predictor = PredictFixtures.frenchPredictor(store)

            predictor.undo("c'est ", UndoToken("cest", "c'est"), PredictionSettings(incognito = true))

            assertThat(store.rejections("cest", "c'est")).isZero()
        }

        @Test
        fun `suggestions désactivées - ni barre ni validation`() {
            for (s in listOf(
                PredictionSettings(noSuggestions = true),
                PredictionSettings(profile = FieldProfile.PASSWORD),
                PredictionSettings(profile = FieldProfile.EMAIL),
            )) {
                val prediction = fr.at("bonj", frState, s)
                val commit = fr.commitSpace(prediction, frState, s)

                assertThat(prediction.case).isEqualTo(SpaceCase.DISABLED)
                assertThat(prediction.bar).isEqualTo(SuggestionBar.EMPTY)
                assertThat(commit.edit.operations).containsExactly(EditOperation.InsertText(" "))
                assertThat(commit.edit.endsWithAutoSpace).isFalse()
                assertThat(commit.language).isSameAs(frState)
                assertThat(fr.commitBeforePunctuation(prediction, frState, s).edit.isEmpty).isTrue()
            }
        }

        @Test
        fun `validation par espace désactivée par l'utilisateur - barre gardée, texte inséré tel quel`() {
            val off = PredictionSettings(autocommit = false)

            val prediction = fr.at("bonj", frState, off)

            assertThat(prediction.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(prediction.inserted).isEqualTo("bonj")
            assertThat(prediction.bar.center?.text).isEqualTo("bonjour")
            assertThat(fr.at("ecole", frState, off).inserted).isEqualTo("ecole")
        }

        @Test
        fun `mot hors de l'alphabet du lexique - inséré tel quel`() {
            val prediction = fr.at("straße", frState)

            assertThat(prediction.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(prediction.key).isEmpty()
            assertThat(prediction.bar).isEqualTo(SuggestionBar.EMPTY)
        }

        @Test
        fun `mot de passe - rien n'est observé`() {
            val state = fr.observe(frState, "école", PredictionSettings(profile = FieldProfile.PASSWORD))

            assertThat(state).isSameAs(frState)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `pas de mot en cours - espace simple`() {
            for (text in listOf("", "Bonjour ", "l'")) {
                val prediction = fr.at(text, frState)

                assertThat(prediction.case).isEqualTo(SpaceCase.NO_WORD)
                assertThat(prediction.spaceLabel).isNull()
                assertThat(fr.commitSpace(prediction, frState, settings).edit.endsWithAutoSpace).isFalse()
            }
            assertThat(fr.predict(null, frState, settings).case).isEqualTo(SpaceCase.NO_WORD)
        }

        @Test
        fun `élision tapée avec apostrophe - seul le mot élidé est remplacé`() {
            val prediction = fr.at("l'ecole", frState)

            assertThat(prediction.word).isEqualTo("ecole")
            assertThat(fr.commitSpace(prediction, frState, settings).edit.applyTo("l'ecole")).isEqualTo("l'école ")
        }

        @Test
        fun `minRatio - candidat probable mais pas assez dominant`() {
            val cats = PredictFixtures.catPredictor(chat = 62L, chien = 38L)

            val prediction = cats.at("ch", cats.initialLanguageState("fr"), PredictionSettings(prudence = Prudence.BOLD))

            assertThat(prediction.candidates[0].probability).isGreaterThan(0.6)
            assertThat(prediction.case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `rejectLimit - une seule annulation ne suffit pas`() {
            val store = InMemoryRejectionStore()
            store.recordRejection("cest", "c'est")

            assertThat(PredictFixtures.frenchPredictor(store).at("cest", frState).case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(PredictFixtures.frenchPredictor(store, PredictionConfig(rejectLimit = 1)).at("cest", frState).case)
                .isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `forme forcée avec accent - jamais modifiée`() {
            assertThat(fr.at("écolé", frState).inserted).isEqualTo("écolé")
            assertThat(fr.at("école", frState).spaceLabel).isNull()
        }

        @Test
        fun `apostrophe typographique tapée - pas de remplacement`() {
            val prediction = en.at("don’t", enState)

            assertThat(prediction.case).isEqualTo(SpaceCase.WORD)
            assertThat(prediction.inserted).isEqualTo("don’t")
        }

        @Test
        fun `langue de poids nul - ses mots ne comptent pas`() {
            val both = PredictFixtures.bilingualPredictor()

            val prediction = both.at("the", both.initialLanguageState("fr").lock("fr"))

            assertThat(prediction.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(prediction.candidates).isEmpty()
        }

        @Test
        fun `une seule langue sans lexique d'élisions - pas de candidat élidé`() {
            val plain = Predictor(
                mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("ai" to 10L, "jaillir" to 1L)))),
                KeyboardLayouts.AZERTY,
            )

            val prediction = plain.at("jai", plain.initialLanguageState("fr"))

            assertThat(prediction.candidates.map { it.key }).doesNotContain("j'ai")
        }
    }
}
