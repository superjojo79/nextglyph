package nextglyph.ime.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KeyboardActivationTest {

    private val ours = "io.github.superjojo79.nextglyph"

    @Nested
    inner class HappyPath {
        @Test
        fun `activé quand notre paquet figure parmi les méthodes de saisie activées`() {
            assertThat(KeyboardActivation.isEnabled(listOf("com.autre", ours), ours)).isTrue()
        }

        @Test
        fun `non activé quand il n'y figure pas`() {
            assertThat(KeyboardActivation.isEnabled(listOf("com.autre"), ours)).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un paquet au nom voisin ne compte pas`() {
            assertThat(KeyboardActivation.isEnabled(listOf("$ours.fork", "x$ours"), ours)).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `aucune méthode activée`() {
            assertThat(KeyboardActivation.isEnabled(emptyList(), ours)).isFalse()
        }

        @Test
        fun `une seule méthode activée, la nôtre`() {
            assertThat(KeyboardActivation.isEnabled(setOf(ours), ours)).isTrue()
        }
    }
}
