package nextglyph.ime.debug

import android.text.InputType
import android.view.inputmethod.EditorInfo
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.profile.ProfileType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FieldTestCasesTest {

    private val cases = FieldTestCases.ALL

    @Nested
    inner class HappyPath {
        @Test
        fun `le panel couvre chaque profil du §4`() {
            val profiles = cases.map { InputProfileResolver.resolve(it.inputType, it.imeOptions, null).type }.toSet()

            assertThat(profiles).containsExactlyInAnyOrder(*ProfileType.entries.toTypedArray())
        }

        @Test
        fun `le panel couvre les drapeaux et les actions de la touche Entrée`() {
            val actions = cases.map { it.imeOptions and EditorInfo.IME_MASK_ACTION }.toSet()

            assertThat(actions).contains(
                EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE,
            )
            assertThat(cases).anyMatch { it.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 }
            assertThat(cases).anyMatch { it.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 }
            assertThat(cases).anyMatch { it.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 }
            assertThat(cases).anyMatch { it.pointOnly }
        }

        @Test
        fun `l'écho montre le texte exact, les sauts de ligne et l'action`() {
            assertThat(FieldTestCases.echo("text", "Bonjour", null)).isEqualTo("text = «Bonjour»")
            assertThat(FieldTestCases.echo("multiline", "a\nb", "enter")).isEqualTo("multiline = «a⏎b» [U+000A] action=enter")
            assertThat(FieldTestCases.echo("number", "−3,5", null)).isEqualTo("number = «−3,5» [U+2212]")
        }

        @Test
        fun `noms des actions d'éditeur`() {
            assertThat(FieldTestCases.actionName(EditorInfo.IME_ACTION_SEND)).isEqualTo("send")
            assertThat(FieldTestCases.actionName(EditorInfo.IME_ACTION_SEARCH)).isEqualTo("search")
            assertThat(FieldTestCases.actionName(EditorInfo.IME_ACTION_GO)).isEqualTo("go")
            assertThat(FieldTestCases.actionName(EditorInfo.IME_ACTION_NEXT)).isEqualTo("next")
            assertThat(FieldTestCases.actionName(EditorInfo.IME_ACTION_DONE)).isEqualTo("done")
            assertThat(FieldTestCases.actionName(EditorInfo.IME_ACTION_PREVIOUS)).isEqualTo("previous")
            assertThat(FieldTestCases.actionName(EditorInfo.IME_NULL)).isEqualTo("enter")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `aucun nom ni identifiant en double`() {
            assertThat(cases.map { it.key }).doesNotHaveDuplicates()
            assertThat(cases.map { it.viewId }).doesNotHaveDuplicates()
        }

        @Test
        fun `action inconnue - nommée par son numéro`() {
            assertThat(FieldTestCases.actionName(42)).isEqualTo("action42")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `écho d'un champ vide`() {
            assertThat(FieldTestCases.echo("pin", "", null)).isEqualTo("pin = «»")
        }

        @Test
        fun `un point de code répété n'est cité qu'une fois, hors BMP compris`() {
            assertThat(FieldTestCases.echo("text", "éé😀", null)).isEqualTo("text = «éé😀» [U+00E9 U+1F600]")
        }

        @Test
        fun `les vingt-cinq champs demandés sont présents`() {
            assertThat(cases).hasSize(25)
        }
    }
}
