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

    private val keyTap = TapOffset(-0.1, 0.05)

    private fun keys(vararg kinds: KeyKind) = kinds.forEach { learning.onKeyTap(it, keyTap, text) }

    private fun typeWord(word: String) = word.forEach { learning.onLetter(it.toString(), tap, text) }

    @Nested
    inner class HappyPath {

        @Test
        fun `une frappe d'espace suivie d'une lettre est apprise`() {
            keys(KeyKind.SPACE)
            assertThat(data.spatial.pendingKey).isEqualTo(KeyKind.SPACE)
            assertThat(data.spatial.sampleCount(KeyKind.SPACE)).isZero()

            learning.onLetter("a", tap, text)

            assertThat(data.spatial.pendingKey).isNull()
            assertThat(data.spatial.sampleCount(KeyKind.SPACE)).isEqualTo(1)
        }

        @Test
        fun `une frappe d'entrée suivie d'un caractère est apprise`() {
            keys(KeyKind.ENTER, KeyKind.CHARACTER)

            assertThat(data.spatial.sampleCount(KeyKind.ENTER)).isEqualTo(1)
        }

        @Test
        fun `un retour arrière suivi d'un retour arrière est appris`() {
            keys(KeyKind.BACKSPACE, KeyKind.BACKSPACE)

            assertThat(data.spatial.sampleCount(KeyKind.BACKSPACE)).isEqualTo(1)
            assertThat(data.spatial.pendingKey).isEqualTo(KeyKind.BACKSPACE)
        }

        @Test
        fun `une frappe de symboles suivie d'un caractère est apprise`() {
            keys(KeyKind.SYMBOLS, KeyKind.CHARACTER)

            assertThat(data.spatial.sampleCount(KeyKind.SYMBOLS)).isEqualTo(1)
        }

        @Test
        fun `une frappe de symboles suivie d'une autre touche de fonction est apprise`() {
            keys(KeyKind.SYMBOLS, KeyKind.SPACE)

            assertThat(data.spatial.sampleCount(KeyKind.SYMBOLS)).isEqualTo(1)
        }

        @Test
        fun `une frappe d'espace suivie d'un symboles est apprise`() {
            keys(KeyKind.SPACE, KeyKind.SYMBOLS, KeyKind.LETTERS)

            assertThat(data.spatial.sampleCount(KeyKind.SPACE)).isEqualTo(1)
        }

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

        @Test
        fun `annulation d'une correction refusée assez - le texte tapé est adopté dans la langue d'avant la validation`() {
            val correction = Prediction("zorglub", "zorglub", SpaceCase.DOMINANT, "zorglubs", emptyList(), SuggestionBar.EMPTY, "fr")
            typeWord("zorglub")
            learning.onValidated(correction, LanguageState.initial(listOf("fr", "en"), "en"), text, false)

            val outcome = learning.onUndo("zorglub", text)

            assertThat(outcome).isEqualTo(LearnOutcome.PROMOTED)
            assertThat(data.personal.contains("zorglub", "en")).isTrue()
            assertThat(data.personal.contains("zorglub", "fr")).isFalse()
            assertThat(data.spatial.stagedCount).isZero()
        }

        @Test
        fun `adoption après une validation en début de phrase - initiale en minuscule`() {
            val correction = Prediction("Zorglub", "zorglub", SpaceCase.DOMINANT, "Zorglubs", emptyList(), SuggestionBar.EMPTY, "fr")
            learning.onValidated(correction, language, text, true)

            learning.onUndo("Zorglub", text)

            assertThat(data.personal.words("fr").map { it.form }).containsExactly("zorglub")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un retour arrière après un espace écarte l'espace`() {
            keys(KeyKind.SPACE, KeyKind.BACKSPACE)

            assertThat(data.spatial.sampleCount(KeyKind.SPACE)).isZero()
            assertThat(data.spatial.pendingKey).isEqualTo(KeyKind.BACKSPACE)
        }

        @Test
        fun `un retour arrière après une entrée écarte l'entrée`() {
            keys(KeyKind.ENTER, KeyKind.BACKSPACE)

            assertThat(data.spatial.sampleCount(KeyKind.ENTER)).isZero()
        }

        @Test
        fun `un retour arrière isolé suivi d'une lettre n'est pas appris`() {
            keys(KeyKind.BACKSPACE)

            learning.onLetter("m", tap, text)

            assertThat(data.spatial.sampleCount(KeyKind.BACKSPACE)).isZero()
            assertThat(data.spatial.pendingKey).isNull()
        }

        @Test
        fun `un retour arrière suivi d'un espace ou d'un symboles n'est pas appris`() {
            keys(KeyKind.BACKSPACE, KeyKind.SPACE)
            keys(KeyKind.BACKSPACE, KeyKind.SYMBOLS)

            assertThat(data.spatial.sampleCount(KeyKind.BACKSPACE)).isZero()
        }

        @Test
        fun `un symboles suivi de ABC n'est pas appris`() {
            keys(KeyKind.SYMBOLS, KeyKind.LETTERS)

            assertThat(data.spatial.sampleCount(KeyKind.SYMBOLS)).isZero()
        }

        @Test
        fun `un symboles suivi d'un retour arrière n'est pas appris`() {
            keys(KeyKind.SYMBOLS, KeyKind.BACKSPACE)

            assertThat(data.spatial.sampleCount(KeyKind.SYMBOLS)).isZero()
        }

        @Test
        fun `un mot abandonné écarte la frappe de touche en attente`() {
            keys(KeyKind.SPACE)

            learning.onWordAbandoned()
            learning.onLetter("a", tap, text)

            assertThat(data.spatial.pendingKey).isNull()
            assertThat(data.spatial.sampleCount(KeyKind.SPACE)).isZero()
        }

        @Test
        fun `une frappe sans position ou sur une touche non apprise n'est pas mise en attente`() {
            learning.onKeyTap(KeyKind.SPACE, null, text)
            assertThat(data.spatial.pendingKey).isNull()

            learning.onKeyTap(KeyKind.CHARACTER, keyTap, text)
            learning.onKeyTap(KeyKind.LETTERS, keyTap, text)
            assertThat(data.spatial.pendingKey).isNull()
        }

        @Test
        fun `un champ sans apprentissage des frappes ne met rien en attente`() {
            val incognito = PredictionSettings(profile = FieldProfile.PASSWORD)

            learning.onKeyTap(KeyKind.SPACE, keyTap, incognito)

            assertThat(data.spatial.pendingKey).isNull()
        }

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
        fun `chaque touche de fonction apprise a un décalage relatif à sa touche au repos`() {
            for (kind in nextglyph.engine.learn.SpatialModel.LEARNED_KEYS) {
                val rect = KeyRect(1, 2, Key(kind, "x", 1f), 100f, 50f, 300f, 150f)

                val offset = TapOffset.of(150f, 125f, rect)

                assertThat(offset).describedAs(kind.name).isNotNull()
                assertThat(offset!!.dx).isCloseTo(-0.25, within(1e-9))
                assertThat(offset.dy).isCloseTo(0.25, within(1e-9))
            }
        }

        @Test
        fun `une touche qui n'est ni une lettre ni une touche de fonction apprise n'a pas de décalage`() {
            val rect = KeyRect(0, 0, Key(KeyKind.CHARACTER, "1", 1f), 0f, 0f, 10f, 10f)

            assertThat(TapOffset.of(5f, 5f, rect)).isNull()
        }

        @Test
        fun `annuler sans validation ne fait rien`() {
            typeWord("ab")

            learning.onUndo()

            assertThat(data.spatial.stagedCount).isEqualTo(2)
        }

        @Test
        fun `annulation sans mot refusé - rien n'est adopté et le mot en attente est oublié`() {
            learning.onValidated(literal("zorglub"), language, text, false)

            val outcome = learning.onUndo()

            assertThat(outcome).isNull()
            assertThat(data.personal.size).isZero()
            assertThat(data.personal.pendingSize).isZero()
        }

        @Test
        fun `annulation sans apprentissage autorisé - rien n'est adopté`() {
            val incognito = PredictionSettings(incognito = true)
            learning.onValidated(literal("zorglub"), language, text, false)

            val outcome = learning.onUndo("zorglub", incognito)

            assertThat(outcome).isEqualTo(LearnOutcome.IGNORED)
            assertThat(data.personal.size).isZero()
        }

        @Test
        fun `annulation sans validation en cours - rien n'est adopté`() {
            assertThat(learning.onUndo("zorglub", text)).isNull()
            assertThat(learning.onUndo("zorglub", null)).isNull()
            assertThat(data.personal.size).isZero()
        }

        @Test
        fun `mot refusé sans réglages - rien n'est adopté`() {
            learning.onValidated(literal("zorglub"), language, text, false)

            assertThat(learning.onUndo("zorglub", null)).isNull()
            assertThat(data.personal.size).isZero()
        }

        @Test
        fun `adoption hors début de phrase - la majuscule du mot est conservée`() {
            val correction = Prediction("Zorglub", "zorglub", SpaceCase.DOMINANT, "Zorglubs", emptyList(), SuggestionBar.EMPTY, "fr")
            learning.onValidated(correction, language, text, false)

            learning.onUndo("Zorglub", text)

            assertThat(data.personal.words("fr").map { it.form }).containsExactly("Zorglub")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une frappe de touche en attente est remplacée par la suivante`() {
            keys(KeyKind.SPACE, KeyKind.ENTER)

            assertThat(data.spatial.pendingKey).isEqualTo(KeyKind.ENTER)
            assertThat(data.spatial.sampleCount(KeyKind.SPACE)).isEqualTo(1)
            assertThat(data.spatial.totalSamples).isEqualTo(1)
        }

        @Test
        fun `une frappe de touche en attente à la sortie du champ n'est jamais intégrée`() {
            keys(KeyKind.SPACE)

            learning.onWordAbandoned()

            assertThat(data.spatial.totalSamples).isZero()
        }

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

        @Test
        fun `adopter deux fois le même refus compte le mot sans le dupliquer`() {
            val correction = Prediction("zorglub", "zorglub", SpaceCase.DOMINANT, "zorglubs", emptyList(), SuggestionBar.EMPTY, "fr")
            repeat(2) {
                learning.onValidated(correction, language, text, false)
                learning.onUndo("zorglub", text)
            }

            assertThat(data.personal.words("fr")).containsExactly(nextglyph.engine.learn.PersonalWord("fr", "zorglub", 2))
        }
    }
}
