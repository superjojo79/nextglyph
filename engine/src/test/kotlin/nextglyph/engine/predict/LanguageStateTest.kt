package nextglyph.engine.predict

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LanguageStateTest {

    private val start = LanguageState.initial(listOf("fr", "en"), "fr")
    private val english = doubleArrayOf(0.0, 1.0)
    private val french = doubleArrayOf(1.0, 0.0)

    @Nested
    inner class HappyPath {
        @Test
        fun `départ - langue préférée à 0,75`() {
            assertThat(start.weights).containsExactly(0.75, 0.25)
            assertThat(start.dominant).isEqualTo("fr")
            assertThat(start.sentenceLanguage).isEqualTo("fr")
            assertThat(start.locked).isNull()
        }

        @Test
        fun `chaque mot apporte son indice - π = (Σ a + π0) sur (Σ + 1)`() {
            val one = start.observe(english)
            val two = one.observe(english)

            assertThat(one.weight("en")).isCloseTo(1.25 / 2, within(1e-12))
            assertThat(two.weight("en")).isCloseTo(2.25 / 3, within(1e-12))
            assertThat(two.dominant).isEqualTo("en")
        }

        @Test
        fun `fenêtre glissante de 4 mots`() {
            var state = start
            repeat(4) { state = state.observe(english) }
            val full = state.weight("en")

            state = state.observe(french)

            assertThat(full).isCloseTo(4.25 / 5, within(1e-12))
            assertThat(state.weight("en")).isCloseTo(3.25 / 5, within(1e-12))
        }

        @Test
        fun `langue de la phrase - mots depuis la dernière ponctuation finale`() {
            var state = start
            repeat(4) { state = state.observe(english) }
            state = state.endSentence()

            val newSentence = state.observe(french)

            assertThat(state.sentenceLanguage).isEqualTo("en")
            // Phrase : français 1 ; a priori : π au début de la phrase (en 0,85).
            assertThat(newSentence.sentenceLanguage).isEqualTo("fr")
            assertThat(newSentence.dominant).isEqualTo("en")
        }

        @Test
        fun `verrouillage puis retour au mode automatique`() {
            val locked = start.observe(english).lock("fr")

            assertThat(locked.weights).containsExactly(1.0, 0.0)
            assertThat(locked.sentenceLanguage).isEqualTo("fr")
            assertThat(locked.observe(english).observe(english).dominant).isEqualTo("fr")
            assertThat(locked.lock(null).weight("en")).isCloseTo(1.25 / 2, within(1e-12))
            assertThat(locked.toString()).contains("verrou=fr")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette des paramètres invalides`() {
            assertThatThrownBy { LanguageState.initial(emptyList(), "fr") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LanguageState.initial(listOf("fr", "fr"), "fr") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LanguageState.initial(listOf("fr"), "en") }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("« en »")
            assertThatThrownBy { LanguageState.initial(listOf("fr"), "fr", window = 0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LanguageState.initial(listOf("fr"), "fr", prior = 0.0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { LanguageState.initial(listOf("fr"), "fr", preferredShare = 1.5) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejette un indice mal formé et une langue inactive`() {
            assertThatThrownBy { start.observe(doubleArrayOf(1.0)) }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Un indice par langue")
            assertThatThrownBy { start.observe(doubleArrayOf(-1.0, 2.0)) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { start.observe(doubleArrayOf(Double.NaN, 0.0)) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { start.lock("de") }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `poids d'une langue inactive - zéro`() {
            assertThat(start.weight("de")).isZero()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une seule langue - π = 1`() {
            val single = LanguageState.initial(listOf("en"), "en")

            assertThat(single.weights).containsExactly(1.0)
            assertThat(single.observe(doubleArrayOf(0.0)).weights).containsExactly(1.0)
        }

        @Test
        fun `mot inconnu - n'apporte rien mais occupe la fenêtre`() {
            var state = start.observe(english)
            repeat(4) { state = state.observe(doubleArrayOf(0.0, 0.0)) }

            assertThat(state.weights).containsExactly(0.75, 0.25)
            assertThat(state.sentenceLanguage).isEqualTo("en")
        }

        @Test
        fun `égalité - la langue préférée l'emporte, quelle que soit sa place`() {
            val enFirst = LanguageState.initial(listOf("en", "fr"), "fr", preferredShare = 0.5)
            val three = LanguageState.initial(listOf("en", "de", "fr"), "fr", preferredShare = 0.5)

            assertThat(enFirst.dominant).isEqualTo("en")
            assertThat(three.weights).containsExactly(0.25, 0.25, 0.5)
            assertThat(LanguageState.initial(listOf("en", "fr"), "fr", preferredShare = 0.6).dominant).isEqualTo("fr")
            assertThat(LanguageState.initial(listOf("en", "fr"), "fr", preferredShare = 0.6).observe(doubleArrayOf(0.6, 0.4)).dominant)
                .isEqualTo("fr")
        }

        @Test
        fun `fenêtre d'un mot`() {
            val state = LanguageState.initial(listOf("fr", "en"), "fr", window = 1).observe(english).observe(french)

            assertThat(state.weight("fr")).isCloseTo(1.75 / 2, within(1e-12))
        }
    }
}
