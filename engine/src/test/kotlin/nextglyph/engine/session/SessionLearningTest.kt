package nextglyph.engine.session

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.learn.LearnOutcome
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.LanguageState
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.SpaceCase
import nextglyph.engine.predict.SuggestionBar
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionLearningTest {

    private val data = LearnedData()
    private val learning = SessionLearning(data)
    private val text = PredictionSettings()
    private val language = LanguageState.initial(listOf("fr", "en"), "fr")
    private val tap = TapOffset(0.1, -0.1)

    private fun literal(word: String) = Prediction(word, word, SpaceCase.LITERAL, word, emptyList(), SuggestionBar.EMPTY, "fr")

    private fun typeWord(word: String) = word.forEach { learning.onLetter(it.toString(), tap, text) }

    @Nested
    inner class HappyPath {

        @Test
        fun `un mot inconnu validé deux fois est promu`() {
            typeWord("zorglub")
            assertThat(learning.onValidated(literal("zorglub"), language, text, false)).isEqualTo(LearnOutcome.PENDING)
            typeWord("zorglub")

            assertThat(learning.onValidated(literal("zorglub"), language, text, false)).isEqualTo(LearnOutcome.PROMOTED)
            assertThat(data.personal.contains("zorglub", "fr")).isTrue()
        }

        @Test
        fun `les frappes d'un mot validé attendent le mot suivant`() {
            typeWord("le")
            learning.onValidated(literal("le"), language, text, false)
            assertThat(data.spatial.isEmpty).isTrue()

            learning.onLetter("a", tap, text)

            assertThat(data.spatial.sampleCount('l')).isEqualTo(1)
            assertThat(data.spatial.stagedCount).isEqualTo(1)
        }

        @Test
        fun `un choix inconnu dans la barre est mis en attente`() {
            assertThat(learning.onSuggestionPicked("zorglub", true, language, text, false)).isEqualTo(LearnOutcome.PENDING)
            assertThat(learning.onSuggestionPicked("zorglub", false, language, text, false)).isEqualTo(LearnOutcome.IGNORED)
        }

        @Test
        fun `fin de mot sans validation - frappes intégrées`() {
            typeWord("ab")

            learning.onWordEnded()

            assertThat(data.spatial.sampleCount('a')).isEqualTo(1)
            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `décalage d'une frappe par rapport à sa touche`() {
            val rect = KeyRect(0, 0, Key(KeyKind.LETTER, "a", 1f), 100f, 0f, 200f, 50f)

            val offset = TapOffset.of(175f, 12.5f, rect)!!

            assertThat(offset.dx).isCloseTo(0.25, within(1e-9))
            assertThat(offset.dy).isCloseTo(-0.25, within(1e-9))
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `annulation - frappes écartées et mot en attente oublié`() {
            typeWord("zorglub")
            learning.onValidated(literal("zorglub"), language, text, false)

            learning.onUndo()
            learning.settle()

            assertThat(data.spatial.isEmpty).isTrue()
            assertThat(data.personal.pendingSize).isZero()
        }

        @Test
        fun `retour arrière juste après la validation l'annule aussi`() {
            typeWord("zorglub")
            learning.onValidated(literal("zorglub"), language, text, false)

            learning.onErase()

            assertThat(data.personal.pendingSize).isZero()
            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `mot abandonné - frappes écartées`() {
            typeWord("ab")

            learning.onWordAbandoned()

            assertThat(data.spatial.isEmpty).isTrue()
            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `mot abandonné après une validation - les frappes validées sont gardées`() {
            typeWord("le")
            learning.onValidated(literal("le"), language, text, false)

            learning.onWordAbandoned()

            assertThat(data.spatial.sampleCount('e')).isEqualTo(1)
        }

        @Test
        fun `mot de passe - ni frappe ni mot`() {
            val password = PredictionSettings(profile = FieldProfile.PASSWORD)
            "abc".forEach { learning.onLetter(it.toString(), tap, password) }

            learning.onValidated(literal("abc"), language, password, false)
            learning.settle()

            assertThat(data.isEmpty).isTrue()
        }

        @Test
        fun `une touche qui n'est pas une lettre n'a pas de décalage`() {
            val rect = KeyRect(0, 0, Key(KeyKind.SPACE, " ", 1f), 0f, 0f, 10f, 10f)

            assertThat(TapOffset.of(5f, 5f, rect)).isNull()
        }

        @Test
        fun `annuler sans validation ne fait rien`() {
            typeWord("ab")

            learning.onUndo()

            assertThat(data.spatial.stagedCount).isEqualTo(2)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `effacer une lettre sans frappe ne retire pas celle de la précédente`() {
            learning.onLetter("a", tap, text)
            learning.onLetter("é", null, text)

            learning.onErase()
            learning.onWordEnded()

            assertThat(data.spatial.sampleCount('a')).isEqualTo(1)
        }

        @Test
        fun `effacer sans aucune lettre ne fait rien`() {
            learning.onErase()

            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `une lettre majuscule ou de plusieurs caractères n'est pas mise en attente`() {
            learning.onLetter("A", tap, text)
            learning.onLetter("ab", tap, text)

            assertThat(data.spatial.stagedCount).isZero()
            learning.onErase()
            learning.onErase()
            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `au-delà de 64 lettres, les plus anciennes ne sont plus suivies`() {
            repeat(70) { learning.onLetter("a", tap, text) }

            repeat(70) { learning.onErase() }

            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `les 64 dernières lettres restent alignées sur leurs frappes`() {
            repeat(64) { learning.onLetter("a", tap, text) }
            repeat(6) { learning.onLetter("é", null, text) }

            repeat(70) { learning.onErase() }
            learning.onWordEnded()

            assertThat(data.spatial.sampleCount('a')).isEqualTo(6)
        }

        @Test
        fun `un choix dans la barre intègre d'abord le mot validé avant lui, puis peut être annulé`() {
            typeWord("le")
            learning.onValidated(literal("le"), language, text, false)

            learning.onSuggestionPicked("zorglub", true, language, text, false)
            learning.onErase()

            assertThat(data.spatial.sampleCount('l')).isEqualTo(1)
            assertThat(data.personal.pendingWords().map { it.form }).containsExactly("le")
        }

        @Test
        fun `après la fin d'un mot, un retour arrière n'annule plus la validation précédente`() {
            learning.onValidated(literal("zorglub"), language, text, false)

            learning.onWordEnded()
            learning.onErase()

            assertThat(data.personal.pendingSize).isEqualTo(1)
        }

        @Test
        fun `deux validations sans lettre entre elles - la première est intégrée`() {
            typeWord("le")
            learning.onValidated(literal("le"), language, text, false)
            learning.onValidated(literal("la"), language, text, false)

            assertThat(data.spatial.sampleCount('l')).isEqualTo(1)
        }
    }
}
