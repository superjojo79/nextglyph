package nextglyph.engine.learn

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.predict.PredictFixtures
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.SpaceCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LearnedDataTest {

    private val settings = PredictionSettings()

    private fun LearnedData.fill() {
        repeat(2) { personal.observe("zorglub", "fr", true, settings) }
        personal.observe("blorp", "fr", true, settings)
        rejections.recordRejection("bonj", "bonjour")
        spatial.stage('e', 0.1, 0.1, settings)
        spatial.confirm()
        spatial.stage('r', 0.1, 0.1, settings)
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `un état neuf est vide`() {
            val data = LearnedData()

            assertThat(data.isEmpty).isTrue()
            assertThat(data.revision).isZero()
        }

        @Test
        fun `effacer vide tout l'état appris`() {
            val data = LearnedData()
            data.fill()
            assertThat(data.isEmpty).isFalse()

            data.clear()

            assertThat(data.isEmpty).isTrue()
            assertThat(data.personal.size).isZero()
            assertThat(data.personal.pendingSize).isZero()
            assertThat(data.rejections.size).isZero()
            assertThat(data.spatial.isEmpty).isTrue()
            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `après l'effacement, le prédicteur retrouve exactement son comportement d'origine`() {
            val data = LearnedData()
            data.fill()
            data.rejections.recordRejection("bonj", "bonjour")
            val base = mapOf("fr" to PredictFixtures.french())
            val learned = Predictor(data.personal.sets(base), KeyboardLayouts.AZERTY, rejections = data.rejections)
            val state = learned.initialLanguageState("fr")
            assertThat(learned.predict("Il dit zorg", state, settings).case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(learned.predict("Il dit bonj", state, settings).case).isEqualTo(SpaceCase.LITERAL)

            data.clear()

            val cleared = Predictor(data.personal.sets(base), KeyboardLayouts.AZERTY, rejections = data.rejections)
            val original = PredictFixtures.frenchPredictor()
            for (text in listOf("Il dit zorg", "Il dit bonj", "Il dit ecole", "Il dit lecole")) {
                val a = cleared.predict(text, state, settings)
                val b = original.predict(text, original.initialLanguageState("fr"), settings)
                assertThat(a.case).describedAs(text).isEqualTo(b.case)
                assertThat(a.inserted).describedAs(text).isEqualTo(b.inserted)
            }
        }

        @Test
        fun `la révision cumule celles des trois parties`() {
            val data = LearnedData()
            val start = data.revision

            data.personal.observe("zorglub", "fr", true, settings)
            val afterWord = data.revision
            data.rejections.recordRejection("a", "b")
            val afterRejection = data.revision
            data.spatial.stage('e', 0.0, 0.0, settings)
            data.spatial.confirm()

            assertThat(afterWord).isGreaterThan(start)
            assertThat(afterRejection).isGreaterThan(afterWord)
            assertThat(data.revision).isGreaterThan(afterRejection)
        }

        @Test
        fun `retirer un refus oublie la paire et le mot adopté dans les deux langues`() {
            val data = LearnedData()
            repeat(2) { data.rejections.recordRejection("bonj", "bonjour") }
            data.personal.adopt("bonj", "fr", settings)
            data.personal.adopt("bonj", "en", settings)
            data.personal.adopt("autre", "fr", settings)

            val changed = data.forgetRefusal("Bonj", "bonjour")

            assertThat(changed).isTrue()
            assertThat(data.rejections.rejections("bonj", "bonjour")).isZero()
            assertThat(data.personal.contains("bonj", "fr")).isFalse()
            assertThat(data.personal.contains("bonj", "en")).isFalse()
            assertThat(data.personal.contains("autre", "fr")).isTrue()
        }

        @Test
        fun `retirer un refus rend sa correction au prédicteur`() {
            val data = LearnedData()
            val predictor = PredictFixtures.frenchPredictor(data.rejections)
            val state = predictor.initialLanguageState("fr")
            repeat(2) { data.rejections.recordRejection("bonj", "bonjour") }

            data.forgetRefusal("bonj", "bonjour")

            assertThat(predictor.predict("Il dit bonj", state, settings).case).isEqualTo(SpaceCase.DOMINANT)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `effacer un état déjà vide est sans danger`() {
            val data = LearnedData()

            data.clear()
            data.clear()

            assertThat(data.isEmpty).isTrue()
        }

        @Test
        fun `rien n'est appris en incognito`() {
            val data = LearnedData()
            val incognito = PredictionSettings(incognito = true)

            repeat(3) { data.personal.observe("zorglub", "fr", true, incognito) }
            data.spatial.stage('e', 0.1, 0.1, incognito)
            data.spatial.confirm()

            assertThat(data.isEmpty).isTrue()
        }

        @Test
        fun `retirer un refus inconnu ne change rien`() {
            val data = LearnedData()
            data.fill()
            val revision = data.revision

            assertThat(data.forgetRefusal("inconnu", "autre")).isFalse()

            assertThat(data.revision).isEqualTo(revision)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un seul élément appris suffit à rendre l'état non vide`() {
            for (fill in listOf<(LearnedData) -> Unit>(
                { it.personal.observe("zorglub", "fr", true, settings) },
                { it.rejections.recordRejection("a", "b") },
                { it.spatial.stage('e', 0.0, 0.0, settings); it.spatial.confirm() },
            )) {
                val data = LearnedData()
                fill(data)
                assertThat(data.isEmpty).isFalse()
            }
        }

        @Test
        fun `un mot promu seul suffit aussi`() {
            val data = LearnedData()

            data.personal.importWord("zorglub", "fr", 2)

            assertThat(data.isEmpty).isFalse()
        }

        @Test
        fun `retirer un refus dont seul le mot adopté subsiste change quand même`() {
            val data = LearnedData()
            data.personal.adopt("bonj", "fr", settings)

            assertThat(data.forgetRefusal("bonj", "bonjour")).isTrue()
            assertThat(data.isEmpty).isTrue()
        }

        @Test
        fun `retirer un refus dont seule la paire subsiste change quand même`() {
            val data = LearnedData()
            data.rejections.recordRejection("bonj", "bonjour")

            assertThat(data.forgetRefusal("bonj", "bonjour")).isTrue()
            assertThat(data.isEmpty).isTrue()
        }
    }
}
