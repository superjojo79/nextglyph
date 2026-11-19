package nextglyph.engine.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class WordPrefixTest {

    @Nested
    inner class HappyPath {

        @ParameterizedTest(name = "« {0} » → {1}")
        @CsvSource(
            "bonjour je su, su",
            "Bonjour, bonjour",
            "cœur, coeur",
            "peut-, peut-",
            "peut-êt, peut-et",
            "Élève, eleve",
        )
        fun `calcule le préfixe normalisé du dernier mot`(before: String, expected: String) {
            assertThat(WordPrefix.of(before)).isEqualTo(expected)
        }

        @Test
        fun `une espace avant le curseur commence un mot`() {
            assertThat(WordPrefix.of("bonjour ")).isEqualTo("")
        }

        @Test
        fun `la ponctuation commence un mot`() {
            for (text in listOf("fin.", "oui,", "quoi ?", "(", "a\nb\n", "« ", "ok!?")) assertThat(WordPrefix.of(text)).isEqualTo("")
        }

        @Test
        fun `l'apostrophe anglaise fait partie du mot`() {
            assertThat(WordPrefix.of("I don'", language = "en")).isEqualTo("don'")
            assertThat(WordPrefix.of("don't", language = "en")).isEqualTo("don't")
            assertThat(WordPrefix.of("it’s", language = "en")).isEqualTo("it's")
        }

        @Test
        fun `une élision française est un mot à part entière`() {
            assertThat(WordPrefix.of("l'", language = "fr")).isEqualTo("")
            assertThat(WordPrefix.of("Qu'", language = "fr")).isEqualTo("")
            assertThat(WordPrefix.of("L’éc", language = "fr")).isEqualTo("ec")
            assertThat(WordPrefix.of("jusqu'à", language = "fr")).isEqualTo("a")
            assertThat(WordPrefix.of("d'aujourd'", language = "fr")).isEqualTo("aujourd'")
            assertThat(WordPrefix.of("qu'l'a", language = "fr")).isEqualTo("a")
        }

        @Test
        fun `une apostrophe qui n'est pas une élision reste dans le mot français`() {
            assertThat(WordPrefix.of("aujourd'hui", language = "fr")).isEqualTo("aujourd'hui")
            assertThat(WordPrefix.of("all'", language = "fr")).isEqualTo("all'")
        }

        @Test
        fun `une élision n'est pas séparée hors du français`() {
            assertThat(WordPrefix.of("l'", language = "en")).isEqualTo("l'")
            assertThat(WordPrefix.of("l'éc")).isEqualTo("l'ec")
        }

        @Test
        fun `une sélection sera remplacée, le prochain mot commence`() {
            assertThat(WordPrefix.of("bonjour", selectionLength = 3)).isEqualTo("")
        }

        @Test
        fun `le trait d'union insécable est ramené au trait d'union`() {
            assertThat(WordPrefix.of("peut‑")).isEqualTo("peut-")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un texte indisponible laisse le clavier neutre`() {
            assertThat(WordPrefix.of(null)).isNull()
            assertThat(WordPrefix.of(null, selectionLength = 4, language = "fr")).isNull()
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["abc1", "12", "x2y", "3D", "v1"])
        fun `un mot contenant un chiffre n'est pas un mot du lexique`(before: String) {
            assertThat(WordPrefix.of(before)).isNull()
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["привет", "ß", "日本", "ǆ"])
        fun `un caractère hors de l'alphabet du lexique laisse le clavier neutre`(before: String) {
            assertThat(WordPrefix.of(before)).isNull()
        }

        @Test
        fun `un signe diacritique seul n'est pas un mot`() {
            assertThat(WordPrefix.of("́")).isNull()
        }

        @Test
        fun `un mot de plus de 64 caractères n'est pas un mot`() {
            assertThat(WordPrefix.of("a".repeat(65))).isNull()
            assertThat(WordPrefix.of("x " + "a".repeat(200))).isNull()
        }

        @Test
        fun `un chiffre avant l'espace n'empêche pas le mot suivant`() {
            assertThat(WordPrefix.of("12 ab")).isEqualTo("ab")
            assertThat(WordPrefix.of("naïve€x")).isEqualTo("x")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un champ vide commence un mot`() {
            assertThat(WordPrefix.of("")).isEqualTo("")
        }

        @Test
        fun `64 caractères est le maximum accepté`() {
            assertThat(WordPrefix.of("a".repeat(64))).isEqualTo("a".repeat(64))
        }

        @Test
        fun `la fenêtre de lecture couvre un mot maximal et un séparateur`() {
            assertThat(WordPrefix.LOOKBACK).isEqualTo(66)
            val window = (" " + "a".repeat(64) + "b").takeLast(WordPrefix.LOOKBACK)
            assertThat(WordPrefix.of(window)).isNull()
        }

        @Test
        fun `un trait d'union ou une apostrophe en tête de mot ne comptent pas`() {
            assertThat(WordPrefix.of("-")).isEqualTo("")
            assertThat(WordPrefix.of("'a")).isEqualTo("a")
            assertThat(WordPrefix.of("--a")).isEqualTo("a")
            assertThat(WordPrefix.of("a--")).isEqualTo("")
            assertThat(WordPrefix.of("a b-")).isEqualTo("b-")
            assertThat(WordPrefix.of("« 'x")).isEqualTo("x")
        }

        @Test
        fun `un mot près d'un emoji`() {
            assertThat(WordPrefix.of("😀ab")).isEqualTo("ab")
            assertThat(WordPrefix.of("ab😀")).isEqualTo("")
        }

        @Test
        fun `une sélection d'un seul caractère suffit, pas une sélection vide ni négative`() {
            assertThat(WordPrefix.of("ab", selectionLength = 1)).isEqualTo("")
            assertThat(WordPrefix.of("ab", selectionLength = 0)).isEqualTo("ab")
            assertThat(WordPrefix.of("ab", selectionLength = -1)).isEqualTo("ab")
        }

        @Test
        fun `une seule lettre`() {
            assertThat(WordPrefix.of("A")).isEqualTo("a")
        }

        @Test
        fun `accepte tout CharSequence`() {
            assertThat(WordPrefix.of(StringBuilder("un mot"))).isEqualTo("mot")
        }

        @Test
        fun `la langue par défaut ne sépare pas les élisions`() {
            assertThat(WordPrefix.of("qu'")).isEqualTo("qu'")
            assertThat(WordPrefix.of("qu'", language = "FR")).isEqualTo("qu'")
        }
    }
}
