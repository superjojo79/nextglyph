package nextglyph.tools.ngrams

import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.WordEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NgramVocabularyTest {

    private fun entries(vararg forms: String) = forms.map { WordEntry(it, 10) }

    private val french = NgramVocabulary(Language.FR, entries("je", "école", "Paris", "l'", "I"))
    private val english = NgramVocabulary(Language.EN, entries("the", "I", "I'm", "Tom", "it's", "iPhone"))

    @Nested
    inner class HappyPath {
        @Test
        fun `un mot est ramené à sa forme minuscule de la liste`() {
            assertThat(french.display("Je")).isEqualTo("je")
            assertThat(french.display("JE")).isEqualTo("je")
            assertThat(french.display("École")).isEqualTo("école")
            assertThat(french.display("l'")).isEqualTo("l'")
        }

        @Test
        fun `un mot qui n'existe qu'avec une majuscule garde sa forme`() {
            assertThat(french.display("Paris")).isEqualTo("Paris")
            assertThat(english.display("Tom")).isEqualTo("Tom")
            assertThat(english.display("I")).isEqualTo("I")
            assertThat(english.display("I'm")).isEqualTo("I'm")
        }

        @Test
        fun `un mot absent de la liste est inconnu`() {
            assertThat(french.display("zorglub")).isNull()
            assertThat(french.display("paris")).isNull()
            assertThat(french.display("")).isNull()
        }

        @Test
        fun `seuls les mots en minuscules sont proposables, plus I et ses contractions en anglais`() {
            assertThat(french.isPredictable("je")).isTrue()
            assertThat(french.isPredictable("l'")).isTrue()
            assertThat(french.isPredictable("Paris")).isFalse()
            assertThat(english.isPredictable("the")).isTrue()
            assertThat(english.isPredictable("it's")).isTrue()
            assertThat(english.isPredictable("I")).isTrue()
            assertThat(english.isPredictable("I'm")).isTrue()
            assertThat(english.isPredictable("Tom")).isFalse()
            assertThat(english.isPredictable("iPhone")).isFalse()
        }

        @Test
        fun `le I n'est une exception qu'en anglais`() {
            assertThat(french.isPredictable("I")).isFalse()
            assertThat(french.isPredictable("I'm")).isFalse()
        }

        @Test
        fun `une liste de mots est convertie, les inconnus sont marqués`() {
            assertThat(french.forms(listOf("Je", "zorglub", "Paris"))).containsExactly("je", NgramVocabulary.UNKNOWN, "Paris")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `le marqueur de mot inconnu n'est pas une clé de lexique`() {
            assertThat(french.display(NgramVocabulary.UNKNOWN)).isNull()
            assertThat(NgramVocabulary.UNKNOWN.all { it in 'a'..'z' || it == '\'' || it == '-' }).isFalse()
        }

        @Test
        fun `une liste vide ne connaît aucun mot`() {
            val empty = NgramVocabulary(Language.FR, emptyList())

            assertThat(empty.display("je")).isNull()
            assertThat(empty.forms(listOf("je"))).containsExactly(NgramVocabulary.UNKNOWN)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `aucun mot ne donne aucune forme`() {
            assertThat(french.forms(emptyList())).isEmpty()
        }

        @Test
        fun `la forme minuscule prime sur la forme du corpus quand les deux existent`() {
            val both = NgramVocabulary(Language.EN, entries("will", "Will"))

            assertThat(both.display("Will")).isEqualTo("will")
            assertThat(both.display("will")).isEqualTo("will")
        }

        @Test
        fun `la casse est ramenée sans dépendre de la locale`() {
            val turkish = NgramVocabulary(Language.EN, entries("ısı", "iç"))
            val previous = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))

                assertThat(turkish.display("IÇ")).isEqualTo("iç")
                assertThat(NgramVocabulary(Language.EN, entries("it")).display("IT")).isEqualTo("it")
            } finally {
                java.util.Locale.setDefault(previous)
            }
        }
    }
}
