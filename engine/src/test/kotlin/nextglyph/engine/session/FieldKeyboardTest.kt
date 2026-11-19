package nextglyph.engine.session

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.LayoutRole
import nextglyph.engine.profile.AndroidInputConstants as C
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.profile.ProfileLayout
import nextglyph.engine.profile.ProfileType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FieldKeyboardTest {

    private fun keyboard(inputType: Int, imeOptions: Int = 0, kind: LayoutKind = LayoutKind.AZERTY, decimal: Char = ',') =
        FieldKeyboard.of(InputProfileResolver.resolve(inputType, imeOptions, null), kind, decimal)

    private val text = C.TYPE_CLASS_TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Nested
    inner class HappyPath {
        @Test
        fun `texte - disposition compacte, couche des symboles, barre et échappatoire proposée`() {
            val k = keyboard(text)

            assertThat(k.main.role).isEqualTo(LayoutRole.TEXT)
            assertThat(k.main.kind).isEqualTo(LayoutKind.AZERTY)
            assertThat(k.symbols?.role).isEqualTo(LayoutRole.TEXT_SYMBOLS)
            assertThat(k.showsBar).isTrue()
            assertThat(k.rowHeightDp).isEqualTo(KeyboardMetrics.DEFAULT_ROW_HEIGHT_DP)
            assertThat(k.canUseClassic).isTrue()
            assertThat(k.classic).isFalse()
            assertThat(k.symbolsMenu).isEqualTo(SymbolsMenu.QUICK_PUNCTUATION_AND_CLASSIC)
            assertThat(k.profile).isSameAs(k.requested)
        }

        @Test
        fun `mot de passe - six rangées, couche du mot de passe, ni barre ni menu`() {
            val k = keyboard(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD, kind = LayoutKind.QWERTY)

            assertThat(k.main.role).isEqualTo(LayoutRole.PASSWORD)
            assertThat(k.main.rows).hasSize(6)
            assertThat(k.main.kind).isEqualTo(LayoutKind.QWERTY)
            assertThat(k.symbols?.role).isEqualTo(LayoutRole.PASSWORD_SYMBOLS)
            assertThat(k.showsBar).isFalse()
            assertThat(k.canUseClassic).isFalse()
            assertThat(k.symbolsMenu).isEqualTo(SymbolsMenu.NONE)
        }

        @Test
        fun `pavés - aucune couche de symboles, touches de 44 dp, pas de barre`() {
            val pads = listOf(
                keyboard(C.TYPE_CLASS_NUMBER) to LayoutRole.NUMBER,
                keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD) to LayoutRole.PIN,
                keyboard(C.TYPE_CLASS_PHONE) to LayoutRole.PHONE,
                keyboard(C.TYPE_CLASS_DATETIME) to LayoutRole.DATE_TIME,
            )

            for ((k, role) in pads) {
                assertThat(k.main.role).isEqualTo(role)
                assertThat(k.symbols).isNull()
                assertThat(k.showsBar).isFalse()
                assertThat(k.rowHeightDp).isEqualTo(KeyboardMetrics.PAD_ROW_HEIGHT_DP)
                assertThat(k.symbolsMenu).isEqualTo(SymbolsMenu.NONE)
                assertThat(k.main.rows.flatMap { it.keys }.none { it.kind == KeyKind.SYMBOLS || it.kind == KeyKind.LETTER }).isTrue()
            }
        }

        @Test
        fun `échappatoire - disposition classique sans adaptation, puis retour`() {
            val k = keyboard(text)

            val classic = k.withClassic(true)
            val back = classic.withClassic(false)

            assertThat(classic.classic).isTrue()
            assertThat(classic.main.role).isEqualTo(LayoutRole.PASSWORD)
            assertThat(classic.symbols?.role).isEqualTo(LayoutRole.PASSWORD_SYMBOLS)
            assertThat(classic.profile.layout).isEqualTo(ProfileLayout.PASSWORD)
            assertThat(classic.profile.adaptation).isFalse()
            assertThat(classic.profile.readsText).isFalse()
            assertThat(classic.showsBar).isFalse()
            assertThat(classic.symbolsMenu).isEqualTo(SymbolsMenu.BACK_FROM_CLASSIC)
            assertThat(classic.requested).isSameAs(k.requested)
            assertThat(back.classic).isFalse()
            assertThat(back.id).isEqualTo(k.id)
            assertThat(back.profile).isEqualTo(k.profile)
        }

        @Test
        fun `le séparateur appris remplace celui de la locale sur le pavé`() {
            val k = keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL).withDecimalLabel('.')

            assertThat(k.decimalLabel).isEqualTo('.')
            assertThat(k.main.rows[1].keys[3].label).isEqualTo(".")
            assertThat(k.id).isNotEqualTo(keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL).id)
        }

        @Test
        fun `le séparateur décimal affiché suit le paramètre`() {
            val k = keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL, decimal = '.')

            assertThat(k.main.rows[1].keys[3].kind).isEqualTo(KeyKind.DECIMAL)
            assertThat(k.main.rows[1].keys[3].label).isEqualTo(".")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `le même séparateur décimal garde le même clavier`() {
            val k = keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL)

            assertThat(k.withDecimalLabel(',')).isSameAs(k)
        }

        @Test
        fun `l'échappatoire n'existe pas pour un mot de passe ni un pavé`() {
            for (inputType in listOf(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD, C.TYPE_CLASS_NUMBER, C.TYPE_CLASS_PHONE)) {
                val k = keyboard(inputType)

                assertThat(k.withClassic(true)).isSameAs(k)
            }
        }

        @Test
        fun `demander l'état courant ne crée pas de nouveau clavier`() {
            val k = keyboard(text)
            val classic = k.withClassic(true)

            assertThat(k.withClassic(false)).isSameAs(k)
            assertThat(classic.withClassic(true)).isSameAs(classic)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `les identifiants distinguent les variantes des pavés et les dispositions`() {
            val ids = listOf(
                keyboard(C.TYPE_CLASS_NUMBER),
                keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_SIGNED),
                keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL),
                keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL, decimal = '.'),
                keyboard(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD),
                keyboard(C.TYPE_CLASS_DATETIME),
                keyboard(C.TYPE_CLASS_DATETIME or C.TYPE_DATETIME_VARIATION_DATE),
                keyboard(C.TYPE_CLASS_DATETIME or C.TYPE_DATETIME_VARIATION_TIME),
                keyboard(C.TYPE_CLASS_PHONE),
                keyboard(text),
                keyboard(text, kind = LayoutKind.QWERTY),
                keyboard(text).withClassic(true),
            ).map { it.id }

            assertThat(ids).doesNotHaveDuplicates()
        }

        @Test
        fun `deux champs de texte différents partagent les mêmes dispositions`() {
            assertThat(keyboard(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).id).isEqualTo(keyboard(text).id)
        }

        @Test
        fun `la hauteur des rangées de texte est réglable, pas celle des pavés`() {
            val profile = InputProfileResolver.resolve(text, 0, null)
            val number = InputProfileResolver.resolve(C.TYPE_CLASS_NUMBER, 0, null)

            assertThat(FieldKeyboard.of(profile, LayoutKind.AZERTY, ',', 46f).rowHeightDp).isEqualTo(46f)
            assertThat(FieldKeyboard.of(profile, LayoutKind.AZERTY, ',', 46f).withClassic(true).rowHeightDp).isEqualTo(46f)
            assertThat(FieldKeyboard.of(number, LayoutKind.AZERTY, ',', 46f).rowHeightDp).isEqualTo(KeyboardMetrics.PAD_ROW_HEIGHT_DP)
        }

        @Test
        fun `la disposition classique d'un nom de personne garde son type`() {
            val k = keyboard(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME).withClassic(true)

            assertThat(k.profile.type).isEqualTo(ProfileType.PERSON_NAME)
            assertThat(k.profile.contactsPriority).isFalse()
        }
    }
}
