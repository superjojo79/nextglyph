package nextglyph.ime.view

import nextglyph.engine.edit.ShiftState
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.session.EnterKeyLabel
import nextglyph.ime.R
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Textes des nœuds d'accessibilité (§13), avec des chaînes simulées « id(argument) ». */
class KeyDescriptionsTest {

    private val names = mapOf(
        R.string.a11y_space to "espace", R.string.a11y_space_inserts to "espace insère", R.string.a11y_backspace to "effacer",
        R.string.a11y_enter to "entrée", R.string.a11y_enter_send to "envoyer", R.string.a11y_enter_search to "rechercher",
        R.string.a11y_enter_go to "aller", R.string.a11y_enter_next to "suivant", R.string.a11y_enter_previous to "précédent",
        R.string.a11y_enter_done to "terminé", R.string.a11y_symbols to "symboles", R.string.a11y_letters to "lettres",
        R.string.a11y_shift to "majuscule", R.string.a11y_shift_on to "activée", R.string.a11y_shift_locked to "verrouillée",
        R.string.a11y_suggestion to "suggestion", R.string.a11y_inserted to "inséré",
    )
    private val descriptions = KeyDescriptions { id, arg -> names.getValue(id) + (arg?.let { "($it)" } ?: "") }

    private fun describe(kind: KeyKind, label: String, shift: ShiftState = ShiftState.OFF, space: String? = null, enter: String = EnterKeyLabel.ENTER) =
        descriptions.describe(Key(kind, label, 1f), label, shift, space, enter)

    @Nested
    inner class HappyPath {

        @Test
        fun `une lettre se lit en capitale quand la majuscule est active`() {
            assertThat(describe(KeyKind.LETTER, "e")).isEqualTo("e")
            assertThat(describe(KeyKind.LETTER, "e", ShiftState.ONCE)).isEqualTo("E")
            assertThat(describe(KeyKind.LETTER, "é", ShiftState.LOCKED)).isEqualTo("É")
        }

        @Test
        fun `touches de fonction nommées`() {
            assertThat(describe(KeyKind.BACKSPACE, "⌫")).isEqualTo("effacer")
            assertThat(describe(KeyKind.SYMBOLS, "?123")).isEqualTo("symboles")
            assertThat(describe(KeyKind.LETTERS, "ABC")).isEqualTo("lettres")
            assertThat(describe(KeyKind.SHIFT, "⇧")).isEqualTo("majuscule")
            assertThat(describe(KeyKind.CHARACTER, "?")).isEqualTo("?")
            assertThat(describe(KeyKind.DECIMAL, ",")).isEqualTo(",")
        }

        @Test
        fun `l'espace annonce le mot qu'elle insérera`() {
            assertThat(describe(KeyKind.SPACE, " ")).isEqualTo("espace")
            assertThat(describe(KeyKind.SPACE, " ", space = "␣ Bonjour")).isEqualTo("espace insère(Bonjour)")
        }

        @Test
        fun `actions de la touche Entrée`() {
            val expected = mapOf(
                EnterKeyLabel.SEND to "envoyer", EnterKeyLabel.SEARCH to "rechercher", EnterKeyLabel.GO to "aller",
                EnterKeyLabel.NEXT to "suivant", EnterKeyLabel.PREVIOUS to "précédent", EnterKeyLabel.DONE to "terminé",
                EnterKeyLabel.ENTER to "entrée",
            )
            for ((label, name) in expected) assertThat(describe(KeyKind.ENTER, label, enter = label)).isEqualTo(name)
        }

        @Test
        fun `suggestion et annonce`() {
            assertThat(descriptions.suggestion("Gwen")).isEqualTo("suggestion(Gwen)")
            assertThat(descriptions.inserted("Bonjour")).isEqualTo("inséré(Bonjour)")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un emplacement vide n'a pas de nœud`() {
            assertThat(describe(KeyKind.EMPTY, "")).isNull()
        }

        @Test
        fun `un libellé de Entrée inconnu se lit Entrée`() {
            assertThat(describe(KeyKind.ENTER, "?", enter = "?")).isEqualTo("entrée")
        }
    }

    @Nested
    inner class BoundaryValues {

        @ParameterizedTest
        @EnumSource(ShiftState::class)
        fun `état de la majuscule`(shift: ShiftState) {
            val expected = when (shift) {
                ShiftState.OFF -> null
                ShiftState.ONCE -> "activée"
                ShiftState.LOCKED -> "verrouillée"
            }

            assertThat(descriptions.shiftState(shift)).isEqualTo(expected)
        }

        @Test
        fun `libellé d'espace sans préfixe gardé tel quel`() {
            assertThat(describe(KeyKind.SPACE, " ", space = "mot")).isEqualTo("espace insère(mot)")
        }
    }
}
