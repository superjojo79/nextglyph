package nextglyph.engine.layout

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `une lettre insère son libellé`() {
            val key = Key.letter('e')

            assertThat(key.kind).isEqualTo(KeyKind.LETTER)
            assertThat(key.label).isEqualTo("e")
            assertThat(key.text).isEqualTo("e")
            assertThat(key.restWidth).isEqualTo(1f)
        }

        @Test
        fun `l'espace insère une espace`() {
            assertThat(Key(KeyKind.SPACE, "", 2f).text).isEqualTo(" ")
        }

        @Test
        fun `les touches de fonction n'insèrent pas de texte littéral`() {
            assertThat(Key(KeyKind.SYMBOLS, "?123", 1f).text).isNull()
            assertThat(Key(KeyKind.ENTER, "↵", 1f).text).isNull()
            assertThat(Key(KeyKind.BACKSPACE, "⌫", 1f).text).isNull()
        }

        @Test
        fun `une touche de caractère insère son libellé`() {
            val key = Key.character('€', 0.9f)

            assertThat(key.kind).isEqualTo(KeyKind.CHARACTER)
            assertThat(key.text).isEqualTo("€")
            assertThat(key.restWidth).isEqualTo(0.9f)
        }

        @Test
        fun `majuscule, retour aux lettres, séparateur décimal et emplacement vide n'insèrent pas de texte littéral`() {
            assertThat(Key(KeyKind.SHIFT, "⇧", 1f).text).isNull()
            assertThat(Key(KeyKind.LETTERS, "ABC", 1f).text).isNull()
            assertThat(Key(KeyKind.DECIMAL, ",", 1f).text).isNull()
            assertThat(Key.empty(2.5f).kind).isEqualTo(KeyKind.EMPTY)
            assertThat(Key.empty(2.5f).text).isNull()
        }

        @Test
        fun `la largeur d'une lettre peut être précisée`() {
            assertThat(Key.letter('w', 0.85f).restWidth).isEqualTo(0.85f)
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @ValueSource(floats = [0f, -1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette une largeur non strictement positive ou non finie`(width: Float) {
            assertThatThrownBy { Key(KeyKind.SPACE, "", width) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("largeur")
        }

        @Test
        fun `rejette une touche de caractère sans libellé`() {
            assertThatThrownBy { Key(KeyKind.CHARACTER, "", 1f) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("caractère")
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "ab"])
        fun `rejette un libellé de lettre qui n'a pas exactement un caractère`(label: String) {
            assertThatThrownBy { Key(KeyKind.LETTER, label, 1f) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("lettre")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte la plus petite largeur positive`() {
            assertThat(Key(KeyKind.SPACE, "", Float.MIN_VALUE).restWidth).isGreaterThan(0f)
        }

        @Test
        fun `les touches de fonction acceptent un libellé de plusieurs caractères ou vide`() {
            assertThat(Key(KeyKind.SYMBOLS, "?123", 1f).label).isEqualTo("?123")
            assertThat(Key(KeyKind.SPACE, "", 1f).label).isEmpty()
        }

        @Test
        fun `deux touches identiques sont égales`() {
            assertThat(Key.letter('a')).isEqualTo(Key.letter('a')).isNotEqualTo(Key.letter('a', 0.9f))
        }
    }
}
