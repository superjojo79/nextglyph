package nextglyph.engine.contacts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Lecture du contexte des contacts dans le texte avant le curseur (§6.3). */
class ContactContextReadTest {

    private fun read(text: String?) = ContactContext.read(text)

    @Nested
    inner class HappyPath {

        @Test
        fun `après bonjour, le mot précédent est bonjour`() {
            assertThat(read("Bonjour Gw")).isEqualTo(ContactContext(previousWord = "Bonjour"))
        }

        @Test
        fun `une virgule après le mot précédent est sautée`() {
            assertThat(read("Bonjour, Gw").previousWord).isEqualTo("Bonjour")
            assertThat(read("Bonjour ,Gw").previousWord).isEqualTo("Bonjour")
        }

        @Test
        fun `début de message`() {
            assertThat(read("Gw")).isEqualTo(ContactContext(atMessageStart = true))
            assertThat(read("  \n")).isEqualTo(ContactContext(atMessageStart = true))
        }

        @Test
        fun `un arobase est le mot précédent`() {
            assertThat(read("merci @Gw").previousWord).isEqualTo("@")
            assertThat(read("merci @ ").previousWord).isEqualTo("@")
        }

        @Test
        fun `indices du champ transmis`() {
            val context = ContactContext.read("le Gw", personNameField = true, typedCapital = true)

            assertThat(context).isEqualTo(ContactContext(personNameField = true, previousWord = "le", typedCapital = true))
        }

        @Test
        fun `élision française retirée du mot en cours`() {
            assertThat(ContactContext.read("avec d'Hé", elisionLanguage = "fr").previousWord).isEqualTo("d'")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `texte indisponible - seuls les indices du champ comptent`() {
            assertThat(ContactContext.read(null, personNameField = true, typedCapital = false))
                .isEqualTo(ContactContext(personNameField = true))
            assertThat(read(null).atMessageStart).isFalse()
        }

        @ParameterizedTest
        @CsvSource("'fin. Gw'", "'42 Gw'", "'(Gw'", "'fin,  ,Gw'")
        fun `un autre signe qu'une virgule - pas de mot précédent`(text: String) {
            val context = read(text)

            assertThat(context.previousWord).isNull()
            assertThat(context.atMessageStart).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {

        @ParameterizedTest
        @CsvSource("'Jean-Pierre Gw', Jean-Pierre", "'aujourd’hui Gw', aujourd’hui", "'a b', a", "'salut ', salut")
        fun `mots composés et mots d'une lettre`(text: String, previous: String) {
            assertThat(read(text).previousWord).isEqualTo(previous)
        }

        @Test
        fun `virgule seule en début de texte`() {
            val context = read(", Gw")

            assertThat(context.previousWord).isNull()
            assertThat(context.atMessageStart).isFalse()
        }

        @Test
        fun `texte vide`() {
            assertThat(read("")).isEqualTo(ContactContext(atMessageStart = true))
        }

        @Test
        fun `espace insécable traitée comme un blanc`() {
            assertThat(read("Bonjour Gw").previousWord).isEqualTo("Bonjour")
        }

        @Test
        fun `un trait d'union initial n'est pas un mot`() {
            assertThat(read("- Gw").previousWord).isNull()
        }
    }
}
