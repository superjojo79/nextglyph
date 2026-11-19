package nextglyph.engine.layout

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

class KeyboardLayoutTest {

    private fun rowOf(total: Float) = KeyRow(listOf(Key(KeyKind.SPACE, "", total)))

    @Nested
    inner class HappyPath {
        @Test
        fun `accepte des rangées de 10 u et expose les largeurs au repos`() {
            val layout = KeyboardLayout(LayoutKind.QWERTY, listOf(rowOf(10f), rowOf(4f).let { KeyRow(it.keys + Key(KeyKind.SPACE, "", 5f), 1f) }))

            assertThat(layout.kind).isEqualTo(LayoutKind.QWERTY)
            assertThat(layout.rows).hasSize(2)
            assertThat(layout.restWidths()).containsExactly(listOf(10f), listOf(4f, 5f))
        }

        @ParameterizedTest
        @CsvSource("fr, AZERTY", "FR, AZERTY", "fr-CA, AZERTY", "fr_BE, AZERTY", "en, QWERTY", "en-US, QWERTY", "de, QWERTY")
        fun `choisit la disposition par défaut d'après la langue`(language: String, expected: LayoutKind) {
            assertThat(LayoutKind.defaultForLanguage(language)).isEqualTo(expected)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette une disposition sans rangée`() {
            assertThatThrownBy { KeyboardLayout(LayoutKind.AZERTY, emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("au moins une rangée")
        }

        @Test
        fun `rejette une rangée dont la somme n'est pas 10 u`() {
            assertThatThrownBy { KeyboardLayout(LayoutKind.AZERTY, listOf(rowOf(10f), rowOf(9f))) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("rangée 1")
        }

        @Test
        fun `ne partage pas la liste de l'appelant`() {
            val source = mutableListOf(rowOf(10f))
            val layout = KeyboardLayout(LayoutKind.AZERTY, source)

            source.clear()

            assertThat(layout.rows).hasSize(1)
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = ["frx", "français", "-fr", "  "])
        fun `une langue absente ou différente donne QWERTY`(language: String?) {
            assertThat(LayoutKind.defaultForLanguage(language)).isEqualTo(LayoutKind.QWERTY)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `juste en dessous de la tolérance est accepté, juste au-dessus rejeté`() {
            val tolerance = KeyboardLayout.ROW_TOLERANCE

            assertThat(KeyboardLayout.isRowSum(10f + 0.9f * tolerance)).isTrue()
            assertThat(KeyboardLayout.isRowSum(10f - 0.9f * tolerance)).isTrue()
            assertThat(KeyboardLayout.isRowSum(10f + 1.1f * tolerance)).isFalse()
            assertThat(KeyboardLayout.isRowSum(10f - 1.1f * tolerance)).isFalse()
        }

        @Test
        fun `une seule rangée suffit`() {
            assertThat(KeyboardLayout(LayoutKind.QWERTY, listOf(rowOf(10f))).rows).hasSize(1)
        }

        @Test
        fun `le turc ne fausse pas la comparaison de la langue`() {
            val previous = java.util.Locale.getDefault()
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            try {
                assertThat(LayoutKind.defaultForLanguage("FR")).isEqualTo(LayoutKind.AZERTY)
            } finally {
                java.util.Locale.setDefault(previous)
            }
        }
    }
}
