package nextglyph.engine.text

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

class TextNormalizerTest {

    @Nested
    inner class HappyPath {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(
            "école, ecole",
            "Élève, eleve",
            "garçon, garcon",
            "naïve, naive",
            "HELLO, hello",
            "peut-être, peut-etre",
        )
        fun `retire les accents et met en minuscules`(input: String, expected: String) {
            assertThat(TextNormalizer.normalize(input)).isEqualTo(expected)
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(
            "cœur, coeur",
            "Œuvre, oeuvre",
            "Lætitia, laetitia",
            "Æsir, aesir",
        )
        fun `développe les ligatures`(input: String, expected: String) {
            assertThat(TextNormalizer.normalize(input)).isEqualTo(expected)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["aujourd’hui", "aujourdʼhui", "aujourd'hui"])
        fun `ramène toutes les apostrophes à l'apostrophe droite`(input: String) {
            assertThat(TextNormalizer.normalize(input)).isEqualTo("aujourd'hui")
        }

        @Test
        fun `laisse inchangé un mot déjà normalisé`() {
            assertThat(TextNormalizer.normalize("don't")).isEqualTo("don't")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette un mot vide`() {
            assertThatIllegalArgumentException()
                .isThrownBy { TextNormalizer.normalize("") }
                .withMessageContaining("vide")
        }

        @ParameterizedTest(name = "[{index}] espace de type {0}")
        @ValueSource(strings = ["a b", " ab", "ab\n", "a\tb", "a b", "a b"])
        fun `rejette un mot contenant un espace, même insécable`(input: String) {
            assertThatIllegalArgumentException()
                .isThrownBy { TextNormalizer.normalize(input) }
                .withMessageContaining("espace")
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = ["́", "́̈"])
        fun `rejette un mot qui ne contient que des signes diacritiques`(input: String) {
            assertThatIllegalArgumentException()
                .isThrownBy { TextNormalizer.normalize(input) }
                .withMessageContaining("diacritiques")
        }
    }

    @Nested
    inner class BoundaryValues {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource("a, a", "É, e", "œ, oe")
        fun `accepte un mot d'un seul caractère`(input: String, expected: String) {
            assertThat(TextNormalizer.normalize(input)).isEqualTo(expected)
        }

        @Test
        fun `accepte un mot de exactement MAX_INPUT_LENGTH caractères`() {
            val word = "a".repeat(TextNormalizer.MAX_INPUT_LENGTH)

            assertThat(TextNormalizer.normalize(word)).isEqualTo(word)
        }

        @Test
        fun `rejette un mot de MAX_INPUT_LENGTH plus un caractères`() {
            val word = "a".repeat(TextNormalizer.MAX_INPUT_LENGTH + 1)

            assertThatIllegalArgumentException()
                .isThrownBy { TextNormalizer.normalize(word) }
                .withMessageContaining("${TextNormalizer.MAX_INPUT_LENGTH}")
        }

        @Test
        fun `le développement des ligatures peut dépasser la longueur maximale en sortie`() {
            val word = "œ".repeat(TextNormalizer.MAX_INPUT_LENGTH)

            assertThat(TextNormalizer.normalize(word))
                .hasSize(TextNormalizer.MAX_INPUT_LENGTH * 2)
        }

        @Test
        fun `traite une lettre suivie d'un accent combinant comme la lettre précomposée`() {
            assertThat(TextNormalizer.normalize("école")).isEqualTo("ecole")
        }

        @Test
        fun `ne dépend pas de la locale par défaut, même turque`() {
            val previous = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"))

                assertThat(TextNormalizer.normalize("INDIGO")).isEqualTo("indigo")
            } finally {
                Locale.setDefault(previous)
            }
        }
    }
}
