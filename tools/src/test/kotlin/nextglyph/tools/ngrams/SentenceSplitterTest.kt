package nextglyph.tools.ngrams

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SentenceSplitterTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `une ligne est coupée après chaque fin de phrase suivie d'une espace`() {
            assertThat(SentenceSplitter.split("Bonjour. Ça va ? Oui ! Bien… Merci"))
                .containsExactly("Bonjour.", "Ça va ?", "Oui !", "Bien…", "Merci")
        }

        @Test
        fun `une phrase sans ponctuation reste entière`() {
            assertThat(SentenceSplitter.split("Je suis là")).containsExactly("Je suis là")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un texte vide ou blanc ne donne aucune phrase`() {
            assertThat(SentenceSplitter.split("")).isEmpty()
            assertThat(SentenceSplitter.split("   ")).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un point sans espace derrière ne coupe pas`() {
            assertThat(SentenceSplitter.split("Il vaut 3.14 euros")).containsExactly("Il vaut 3.14 euros")
            assertThat(SentenceSplitter.split("a.b")).containsExactly("a.b")
        }

        @Test
        fun `plusieurs espaces entre les phrases`() {
            assertThat(SentenceSplitter.split("A.   B")).containsExactly("A.", "B")
        }

        @Test
        fun `une fin de phrase en fin de ligne`() {
            assertThat(SentenceSplitter.split("Fini.")).containsExactly("Fini.")
            assertThat(SentenceSplitter.split("Fini. ")).containsExactly("Fini.")
        }
    }
}
