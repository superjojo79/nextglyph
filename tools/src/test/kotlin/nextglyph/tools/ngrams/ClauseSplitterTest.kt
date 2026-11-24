package nextglyph.tools.ngrams

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClauseSplitterTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `une virgule, un point-virgule ou deux-points sépare deux groupes de mots`() {
            assertThat(ClauseSplitter.split("Oui, il va; elle vient: bien")).containsExactly("Oui", " il va", " elle vient", " bien")
        }

        @Test
        fun `parenthèses, guillemets et tirets entourés d'espaces séparent aussi`() {
            val groups = ClauseSplitter.split("Il (dit) « non » - puis \"oui\" – fin").map { it.trim() }.filter { it.isNotEmpty() }

            assertThat(groups).containsExactly("Il", "dit", "non", "puis", "oui", "fin")
        }

        @Test
        fun `un trait d'union ou une apostrophe dans un mot ne sépare pas`() {
            assertThat(ClauseSplitter.split("Peut-être qu'il va")).containsExactly("Peut-être qu'il va")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un texte vide ou fait de signes ne donne aucun groupe`() {
            assertThat(ClauseSplitter.split("")).isEmpty()
            assertThat(ClauseSplitter.split(" , ; ")).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un seul mot reste entier`() {
            assertThat(ClauseSplitter.split("oui")).containsExactly("oui")
        }

        @Test
        fun `un signe en tête ou en queue ne laisse pas de groupe vide`() {
            assertThat(ClauseSplitter.split(",oui,")).containsExactly("oui")
        }
    }
}
