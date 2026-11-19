package nextglyph.engine.edit

import nextglyph.engine.input.EditOperation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TextEditorTest {

    private val editor = TextEditor()
    private val nn = EditConfig.NARROW_NBSP
    private val nb = EditConfig.NBSP

    private fun auto() = EditState(SpaceOrigin.AUTO)
    private fun typed() = EditState(SpaceOrigin.TYPED)

    private fun fr(
        before: String,
        state: EditState = auto(),
        after: String = "",
        caps: CapsMode = CapsMode.NONE,
        shift: ShiftState = ShiftState.OFF,
    ) = EditContext(before, after, TextProfile.TEXT, SentenceLanguage.FRENCH, caps, shift, state)

    private fun en(before: String, state: EditState = auto(), after: String = "") =
        EditContext(before, after, TextProfile.TEXT, SentenceLanguage.ENGLISH, CapsMode.NONE, ShiftState.OFF, state)

    /** Texte obtenu en appliquant le résultat à [before]. */
    private fun applied(before: String, result: EditResult): String {
        val sb = StringBuilder(before)
        for (op in result.operations) when (op) {
            is EditOperation.DeleteBeforeCursor -> sb.setLength(sb.length - op.count)
            is EditOperation.InsertText -> sb.append(op.text)
        }
        return sb.toString()
    }

    private fun punct(ctx: EditContext, sign: Char) = applied(ctx.textBefore.toString(), editor.onPunctuation(ctx, sign))

    @Nested
    inner class HappyPath {
        @Test
        fun `un mot validé reçoit une espace automatique`() {
            val result = editor.onWordCommitted(fr("Je dis bonjour", EditState.INITIAL), "bonjour")

            assertThat(result.operations).containsExactly(EditOperation.InsertText(" "))
            assertThat(result.state).isEqualTo(auto())
        }

        @Test
        fun `la virgule remplace l'espace automatique`() {
            assertThat(punct(fr("bonjour "), ',')).isEqualTo("bonjour, ")
            assertThat(punct(en("hello "), ',')).isEqualTo("hello, ")
        }

        @Test
        fun `la virgule supprime une espace puis insère le signe et une espace`() {
            val result = editor.onPunctuation(fr("bonjour "), ',')

            assertThat(result.operations).containsExactly(EditOperation.DeleteBeforeCursor(1), EditOperation.InsertText(", "))
            assertThat(result.state).isEqualTo(auto())
        }

        @Test
        fun `point et points de suspension sans espace avant`() {
            assertThat(punct(fr("bonjour "), '.')).isEqualTo("bonjour. ")
            assertThat(punct(fr("bonjour "), '…')).isEqualTo("bonjour… ")
        }

        @Test
        fun `en français point d'interrogation, d'exclamation et point-virgule prennent une fine insécable`() {
            assertThat(punct(fr("vraiment "), '?')).isEqualTo("vraiment${nn}? ")
            assertThat(punct(fr("vraiment "), '!')).isEqualTo("vraiment${nn}! ")
            assertThat(punct(fr("vraiment "), ';')).isEqualTo("vraiment${nn}; ")
        }

        @Test
        fun `en français les deux-points prennent une insécable`() {
            assertThat(punct(fr("voici "), ':')).isEqualTo("voici$nb: ")
        }

        @Test
        fun `en anglais aucune insécable`() {
            assertThat(punct(en("really "), '?')).isEqualTo("really? ")
            assertThat(punct(en("here "), ':')).isEqualTo("here: ")
        }

        @Test
        fun `l'apostrophe remplace l'espace automatique sans espace après`() {
            val result = editor.onPunctuation(fr("l "), '\'')

            assertThat(applied("l ", result)).isEqualTo("l'")
            assertThat(result.state).isEqualTo(EditState.INITIAL)
        }

        @Test
        fun `les guillemets français`() {
            assertThat(punct(fr("il dit "), '«')).isEqualTo("il dit «$nn")
            assertThat(punct(fr("il dit «${nn}oui "), '»')).isEqualTo("il dit «${nn}oui$nn» ")
        }

        @Test
        fun `les guillemets hors français sont insérés tels quels`() {
            assertThat(punct(en("he said "), '«')).isEqualTo("he said «")
            assertThat(punct(en("yes "), '»')).isEqualTo("yes» ")
        }

        @Test
        fun `la typographie française se désactive par le réglage`() {
            val off = TextEditor(EditConfig(frenchTypography = false))
            val result = off.onPunctuation(fr("vraiment "), '?')

            assertThat(applied("vraiment ", result)).isEqualTo("vraiment? ")
        }

        @Test
        fun `double espace après une validation donne point espace`() {
            val result = editor.onSpace(fr("bonjour "))

            assertThat(result.operations).containsExactly(EditOperation.DeleteBeforeCursor(1), EditOperation.InsertText(". "))
            assertThat(result.state).isEqualTo(typed())
        }

        @Test
        fun `double espace avec une espace tapée`() {
            assertThat(applied("fin ", editor.onSpace(fr("fin ", typed())))).isEqualTo("fin. ")
        }

        @Test
        fun `double espace active la majuscule suivante`() {
            val result = editor.onSpace(fr("bonjour ", caps = CapsMode.SENTENCES))

            assertThat(result.shift).isEqualTo(ShiftState.ONCE)
        }

        @Test
        fun `double espace après un chiffre ou une parenthèse fermante`() {
            assertThat(applied("en 2024 ", editor.onSpace(fr("en 2024 ")))).isEqualTo("en 2024. ")
            assertThat(applied("(ok) ", editor.onSpace(fr("(ok) ")))).isEqualTo("(ok). ")
            assertThat(applied("« ok» ", editor.onSpace(fr("« ok» ")))).isEqualTo("« ok». ")
        }

        @Test
        fun `une espace simple est insérée et mémorisée comme tapée`() {
            val result = editor.onSpace(fr("bonjour", EditState.INITIAL))

            assertThat(result.operations).containsExactly(EditOperation.InsertText(" "))
            assertThat(result.state).isEqualTo(typed())
        }

        @Test
        fun `la majuscule suit la ponctuation finale`() {
            val result = editor.onPunctuation(fr("bonjour ", caps = CapsMode.SENTENCES), '.')

            assertThat(result.shift).isEqualTo(ShiftState.ONCE)
            assertThat(editor.onPunctuation(fr("bonjour ", caps = CapsMode.SENTENCES), ',').shift).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `la majuscule manuelle est conservée`() {
            val locked = editor.onPunctuation(fr("a ", shift = ShiftState.LOCKED), ',')
            val once = editor.onSpace(fr("a", EditState.INITIAL, shift = ShiftState.ONCE))

            assertThat(locked.shift).isEqualTo(ShiftState.LOCKED)
            assertThat(once.shift).isEqualTo(ShiftState.ONCE)
        }

        @Test
        fun `un signe isolé après une abréviation ne donne pas de majuscule`() {
            val result = editor.onPunctuation(fr("M ", caps = CapsMode.SENTENCES), '.')

            assertThat(result.shift).isEqualTo(ShiftState.OFF)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un signe non pris en charge est refusé`() {
            assertThatThrownBy { editor.onPunctuation(fr("a "), '#') }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("non pris en charge")
        }

        @Test
        fun `l'état dit espace automatique mais le champ a changé`() {
            val result = editor.onPunctuation(fr("bonjour", auto()), ',')

            assertThat(result.operations).containsExactly(EditOperation.InsertText(", "))
        }

        @Test
        fun `l'état dit espace automatique mais le texte avant le curseur est vide`() {
            val result = editor.onPunctuation(fr("", auto()), ',')

            assertThat(result.operations).containsExactly(EditOperation.InsertText(", "))
        }

        @Test
        fun `l'espace est une insécable et non l'espace automatique`() {
            val result = editor.onPunctuation(fr("a$nb", auto()), ',')

            assertThat(result.operations.first()).isEqualTo(EditOperation.InsertText(", "))
        }

        @Test
        fun `une espace tapée par l'utilisateur n'est pas remplacée`() {
            val result = editor.onPunctuation(fr("bonjour ", typed()), ',')

            assertThat(applied("bonjour ", result)).isEqualTo("bonjour , ")
        }

        @Test
        fun `sans état d'espace un signe ne supprime rien`() {
            val result = editor.onPunctuation(fr("bonjour ", EditState.INITIAL), ',')

            assertThat(result.operations).containsExactly(EditOperation.InsertText(", "))
        }

        @Test
        fun `la validation ne s'applique pas si le mot n'est plus avant le curseur`() {
            val result = editor.onWordCommitted(fr("autre", EditState.INITIAL), "bonjour")

            assertThat(result.operations).isEmpty()
            assertThat(result.state).isEqualTo(EditState.INITIAL)
        }

        @Test
        fun `un mot vide ne reçoit pas d'espace`() {
            assertThat(editor.onWordCommitted(fr("a"), "").operations).isEmpty()
        }

        @ParameterizedTest
        @ValueSource(strings = ["l'", "l’", "lʼ", "peut-", "peut‑"])
        fun `pas d'espace après une élision ou un trait d'union`(word: String) {
            val result = editor.onWordCommitted(fr("dis $word", EditState.INITIAL), word)

            assertThat(result.operations).isEmpty()
            assertThat(result.state).isEqualTo(EditState.INITIAL)
        }

        @Test
        fun `pas d'espace automatique devant du texte`() {
            val commit = editor.onWordCommitted(fr("bonjour", EditState.INITIAL, after = "monde"), "bonjour")
            val comma = editor.onPunctuation(fr("bonjour ", after = " monde"), ',')

            assertThat(commit.operations).isEmpty()
            assertThat(applied("bonjour ", comma)).isEqualTo("bonjour,")
            assertThat(comma.state).isEqualTo(EditState.INITIAL)
        }

        @ParameterizedTest
        @ValueSource(strings = ["EMAIL", "URL", "PASSWORD"])
        fun `aucune espace automatique ni insécable dans les profils sans typographie`(name: String) {
            val profile = TextProfile.valueOf(name)
            fun ctx(before: String) =
                EditContext(before, "", profile, SentenceLanguage.FRENCH, CapsMode.SENTENCES, ShiftState.OFF, auto())

            val commit = editor.onWordCommitted(ctx("bonjour"), "bonjour")
            val question = editor.onPunctuation(ctx("bonjour "), '?')
            val space = editor.onSpace(ctx("bonjour "))

            assertThat(commit.operations).isEmpty()
            assertThat(question.operations).containsExactly(EditOperation.InsertText("?"))
            assertThat(question.state).isEqualTo(EditState.INITIAL)
            assertThat(question.shift).isEqualTo(ShiftState.OFF)
            assertThat(space.operations).containsExactly(EditOperation.InsertText(" "))
        }

        @Test
        fun `le double espace se désactive par le réglage`() {
            val off = TextEditor(EditConfig(doubleSpacePeriod = false))

            assertThat(off.onSpace(fr("bonjour ")).operations).containsExactly(EditOperation.InsertText(" "))
        }

        @Test
        fun `pas de double espace sans espace mémorisée`() {
            val result = editor.onSpace(fr("bonjour ", EditState.INITIAL))

            assertThat(result.operations).containsExactly(EditOperation.InsertText(" "))
        }

        @ParameterizedTest
        @ValueSource(strings = ["bonjour. ", "bonjour, ", "quoi ? ", "a  ", "x\n ", "😀 ", "- "])
        fun `pas de double espace après une ponctuation, une espace ou un symbole`(before: String) {
            val result = editor.onSpace(fr(before, typed()))

            assertThat(result.operations).containsExactly(EditOperation.InsertText(" "))
        }

        @ParameterizedTest
        @ValueSource(strings = ["bonjour. ", "bonjour, ", "quoi ? ", "fin… ", "oui ; "])
        fun `l'espace tapée juste après l'espace automatique d'un signe est absorbée une fois`(before: String) {
            val first = editor.onSpace(fr(before))
            val second = editor.onSpace(fr(before, first.state))

            assertThat(first.operations).isEmpty()
            assertThat(first.state).isEqualTo(typed())
            assertThat(second.operations).containsExactly(EditOperation.InsertText(" "))
        }

        @ParameterizedTest
        @ValueSource(strings = ["- ", "😀 ", " ", "a ", "dit » "])
        fun `pas d'absorption après un autre caractère que la ponctuation`(before: String) {
            val result = editor.onSpace(fr(before))

            assertThat(result.operations).isNotEmpty()
        }

        @Test
        fun `pas de double espace si l'état est vrai mais le texte ne finit pas par une espace`() {
            val result = editor.onSpace(fr("bonjour", auto()))

            assertThat(result.operations).containsExactly(EditOperation.InsertText(" "))
        }

        @Test
        fun `pas de double espace avec une insécable`() {
            assertThat(editor.onSpace(fr("a$nb")).operations).containsExactly(EditOperation.InsertText(" "))
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `signe français en début de champ sans insécable`() {
            assertThat(punct(fr("", EditState.INITIAL), '?')).isEqualTo("? ")
            assertThat(punct(fr("", EditState.INITIAL), ':')).isEqualTo(": ")
            assertThat(punct(fr("", EditState.INITIAL), '»')).isEqualTo("» ")
        }

        @Test
        fun `pas d'insécable après un saut de ligne ou une espace déjà là`() {
            assertThat(punct(fr("a\n", EditState.INITIAL), '!')).isEqualTo("a\n! ")
            assertThat(punct(fr("a$nn", EditState.INITIAL), '!')).isEqualTo("a$nn! ")
            assertThat(punct(fr("a$nb", EditState.INITIAL), ':')).isEqualTo("a$nb: ")
            assertThat(punct(fr("a ", typed()), '?')).isEqualTo("a ? ")
        }

        @Test
        fun `l'insécable déjà présente après remplacement n'est pas doublée`() {
            assertThat(punct(fr("a$nn "), '?')).isEqualTo("a$nn? ")
        }

        @Test
        fun `pas d'espace entre deux signes consécutifs`() {
            assertThat(punct(fr("quoi${nn}? "), '!')).isEqualTo("quoi${nn}?! ")
            assertThat(punct(fr("quoi... "), '!')).isEqualTo("quoi...! ")
            assertThat(punct(fr("quoi, "), ';')).isEqualTo("quoi,; ")
            assertThat(punct(fr("fin. "), '»')).isEqualTo("fin.$nn» ")
        }

        @Test
        fun `un emoji avant l'espace automatique`() {
            assertThat(punct(fr("super 😀 "), '?')).isEqualTo("super 😀$nn? ")
            val result = editor.onPunctuation(fr("😀 "), ',')
            assertThat(result.operations.first()).isEqualTo(EditOperation.DeleteBeforeCursor(1))
        }

        @Test
        fun `emoji hors BMP juste avant le curseur sans espace`() {
            val result = editor.onPunctuation(fr("😀", auto()), '.')

            assertThat(result.operations).containsExactly(EditOperation.InsertText(". "))
        }

        @Test
        fun `double espace en début de champ`() {
            val result = editor.onSpace(fr(" ", typed()))

            assertThat(result.operations).containsExactly(EditOperation.InsertText(" "))
        }

        @Test
        fun `double espace sur champ vide`() {
            val result = editor.onSpace(fr("", EditState.INITIAL))

            assertThat(result.operations).containsExactly(EditOperation.InsertText(" "))
        }

        @Test
        fun `double espace avec le minimum de texte`() {
            assertThat(applied("a ", editor.onSpace(fr("a ")))).isEqualTo("a. ")
        }

        @Test
        fun `texte après le curseur non vide`() {
            val result = editor.onSpace(fr("bonjour ", after = "tout le monde"))

            assertThat(applied("bonjour ", result)).isEqualTo("bonjour. ")
        }

        @Test
        fun `la touche espace n'ajoute pas de point en mode mot de passe`() {
            val ctx = EditContext("abc ", "", TextProfile.PASSWORD, state = typed())

            assertThat(editor.onSpace(ctx).operations).containsExactly(EditOperation.InsertText(" "))
        }

        @Test
        fun `le nom de personne a la typographie`() {
            val ctx = EditContext("Jean ", "", TextProfile.PERSON_NAME, SentenceLanguage.FRENCH, state = auto())

            assertThat(applied("Jean ", editor.onPunctuation(ctx, '?'))).isEqualTo("Jean${nn}? ")
        }

        @Test
        fun `chaque signe pris en charge est accepté`() {
            for (sign in EditConfig.SUPPORTED_SIGNS) {
                assertThat(editor.onPunctuation(fr("a "), sign).operations).isNotEmpty()
            }
        }

        @Test
        fun `la langue autre que français n'applique pas la typographie`() {
            val ctx = EditContext("a ", "", TextProfile.TEXT, SentenceLanguage.OTHER, state = auto())

            assertThat(applied("a ", editor.onPunctuation(ctx, '?'))).isEqualTo("a? ")
        }

        @Test
        fun `guillemet ouvrant en début de champ puis fermant`() {
            assertThat(punct(fr("", EditState.INITIAL), '«')).isEqualTo("«$nn")
            assertThat(editor.onPunctuation(fr("a "), '«').state).isEqualTo(EditState.INITIAL)
        }
    }
}
