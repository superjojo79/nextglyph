package nextglyph.engine.session

import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.Predictor
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Position des frappes transmise à la prédiction (issue #53). */
class InputSessionTapsTest {

    private val predictor = Predictor(
        mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("rose" to 100L, "dose" to 100L, "ami" to 50L, "lami" to 1L)))),
        KeyboardLayouts.QWERTY,
    )

    private val boundary = TapOffset(0.5, 0.0)
    private val centered = TapOffset(0.0, 0.0)

    private fun session(): InputSession {
        val session = InputSession(FakeEditor(""))
        session.setPredictor(predictor, "fr")
        session.onStartInput(SessionField(InputProfileResolver.resolve(C.TYPE_CLASS_TEXT, 0, null), EnterBehavior.EnterKey), 0, 0)
        return session
    }

    private fun InputSession.type(text: String, first: TapOffset?) {
        text.forEachIndexed { i, c -> onLetter(c.toString(), offset = if (i == 0) first else centered.takeIf { first != null }) }
    }

    private fun InputSession.best(): String = prediction!!.candidates.first().key

    @Nested
    inner class HappyPath {
        @Test
        fun `une frappe à la frontière donne une autre correction qu'une position inconnue`() {
            val unknown = session().apply { type("eose", null) }
            val onBoundary = session().apply { type("eose", boundary) }

            assertThat(unknown.best()).isEqualTo("dose")
            assertThat(onBoundary.best()).isEqualTo("rose")
        }

        @Test
        fun `retour arrière - la dernière frappe est retirée`() {
            val session = session()
            session.type("eosz", boundary)

            session.onBackspace()
            session.onLetter("e", offset = centered)

            assertThat(session.prediction!!.word).isEqualTo("eose")
            assertThat(session.best()).isEqualTo("rose")
        }

        @Test
        fun `apostrophe dans le mot - frappe inconnue, les suivantes restent alignées`() {
            val session = session()
            session.onLetter("l", offset = centered)
            session.onCharacter("'")
            session.onLetter("e", offset = boundary)
            session.onLetter("o", offset = centered)
            session.onLetter("s", offset = centered)
            session.onLetter("e", offset = centered)

            // L'élision est retirée du mot en cours : « eose » reste aligné sur ses quatre dernières frappes.
            assertThat(session.prediction!!.word).isEqualTo("eose")
            assertThat(session.best()).isEqualTo("rose")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `lettre accentuée ou sans position - frappe inconnue`() {
            val session = session()
            session.onLetter("é", offset = boundary)
            session.onLetter("o", offset = null)
            session.onLetter("s", offset = centered)
            session.onLetter("e", offset = centered)

            assertThat(session.prediction!!.word).isEqualTo("éose")
            assertThat(session.best()).isEqualTo("dose")
        }

        @Test
        fun `les frappes d'un mot validé ne servent pas au mot suivant`() {
            val session = session()
            session.type("eose", boundary)
            session.onSpace()

            session.type("eose", null)

            assertThat(session.best()).isEqualTo("dose")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `mot de plus de 64 lettres - les plus anciennes frappes sont oubliées sans erreur`() {
            val session = session()

            repeat(70) { session.onLetter("e", offset = centered) }
            session.type("eose", boundary)

            assertThat(session.prediction).isNotNull()
        }

        @Test
        fun `retour arrière sur le seul caractère du mot`() {
            val session = session()
            session.onLetter("e", offset = boundary)

            session.onBackspace()
            session.type("eose", null)

            assertThat(session.best()).isEqualTo("dose")
        }
    }
}
