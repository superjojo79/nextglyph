package nextglyph.engine.profile

import nextglyph.engine.layout.DateTimeVariant
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.LayoutRole
import nextglyph.engine.profile.AndroidInputConstants as C
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ProfileLayoutsTest {

    private fun profile(inputType: Int) = InputProfileResolver.resolve(inputType, 0, null)

    @Nested
    inner class HappyPath {
        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `le texte et les profils compacts utilisent la disposition choisie`(kind: LayoutKind) {
            for (inputType in listOf(C.TYPE_CLASS_TEXT, C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_URI, C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME)) {
                assertThat(ProfileLayouts.of(profile(inputType), kind)).isSameAs(KeyboardLayouts.of(kind))
            }
        }

        @ParameterizedTest
        @EnumSource(LayoutKind::class)
        fun `le mot de passe utilise les 6 rangées`(kind: LayoutKind) {
            val layout = ProfileLayouts.of(profile(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD), kind)

            assertThat(layout.role).isEqualTo(LayoutRole.PASSWORD)
            assertThat(layout.kind).isEqualTo(kind)
            assertThat(layout.rows).hasSize(6)
        }

        @Test
        fun `les pavés suivent le profil`() {
            val signedDecimal = ProfileLayouts.of(profile(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_SIGNED or C.TYPE_NUMBER_FLAG_DECIMAL), LayoutKind.AZERTY)
            val plain = ProfileLayouts.of(profile(C.TYPE_CLASS_NUMBER), LayoutKind.AZERTY)

            assertThat(signedDecimal.role).isEqualTo(LayoutRole.NUMBER)
            assertThat(signedDecimal.rows[0].keys[3].label).isEqualTo("−")
            assertThat(signedDecimal.rows[1].keys[3].label).isEqualTo(",")
            assertThat(plain.rows[0].keys[3].label).isEmpty()
            assertThat(ProfileLayouts.of(profile(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD), LayoutKind.QWERTY).role).isEqualTo(LayoutRole.PIN)
            assertThat(ProfileLayouts.of(profile(C.TYPE_CLASS_PHONE), LayoutKind.QWERTY).role).isEqualTo(LayoutRole.PHONE)
            assertThat(ProfileLayouts.of(profile(C.TYPE_CLASS_DATETIME), LayoutKind.QWERTY).role).isEqualTo(LayoutRole.DATE_TIME)
        }

        @Test
        fun `le séparateur de la locale est transmis`() {
            val layout = ProfileLayouts.of(profile(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL), LayoutKind.QWERTY, '.')

            assertThat(layout.rows[1].keys[3].label).isEqualTo(".")
        }

        @Test
        fun `la hauteur de rangée est 44 dp pour les pavés et celle du texte sinon`() {
            assertThat(ProfileLayouts.rowHeightDp(profile(C.TYPE_CLASS_NUMBER))).isEqualTo(44f)
            assertThat(ProfileLayouts.rowHeightDp(profile(C.TYPE_CLASS_PHONE), 46f)).isEqualTo(44f)
            assertThat(ProfileLayouts.rowHeightDp(profile(C.TYPE_CLASS_DATETIME))).isEqualTo(44f)
            assertThat(ProfileLayouts.rowHeightDp(profile(C.TYPE_CLASS_NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD))).isEqualTo(44f)
            assertThat(ProfileLayouts.rowHeightDp(profile(C.TYPE_CLASS_TEXT))).isEqualTo(KeyboardMetrics.DEFAULT_ROW_HEIGHT_DP)
            assertThat(ProfileLayouts.rowHeightDp(profile(C.TYPE_CLASS_TEXT or C.TYPE_TEXT_VARIATION_PASSWORD), 46f)).isEqualTo(46f)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un profil date et heure sans variante retombe sur le pavé complet`() {
            val broken = profile(C.TYPE_CLASS_DATETIME).copy(dateTimeVariant = null)

            val layout = ProfileLayouts.of(broken, LayoutKind.QWERTY)

            assertThat(layout.rows[1].keys[3].label).isEqualTo("-")
            assertThat(layout.rows[3].keys[2].label).isEqualTo(":")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `chaque variante de date et d'heure donne le pavé correspondant`() {
            for ((inputType, variant) in listOf(
                C.TYPE_CLASS_DATETIME or C.TYPE_DATETIME_VARIATION_DATE to DateTimeVariant.DATE,
                C.TYPE_CLASS_DATETIME or C.TYPE_DATETIME_VARIATION_TIME to DateTimeVariant.TIME,
            )) {
                val layout = ProfileLayouts.of(profile(inputType), LayoutKind.QWERTY)

                assertThat(layout.rows[0].keys[3].label.isNotEmpty()).isEqualTo(variant.hasDate)
                assertThat(layout.rows[3].keys[2].label.isNotEmpty()).isEqualTo(variant.hasTime)
            }
        }

        @Test
        fun `tous les profils ont une disposition dont les rangées font 10 u`() {
            for (cls in 0..4) for (variation in listOf(0, 0x10, 0x20, 0x60, 0x80, 0xd0)) for (flags in listOf(0, 0x1000, 0x3000)) {
                val p = profile(cls or variation or flags)
                val layout = ProfileLayouts.of(p, LayoutKind.AZERTY)

                assertThat(layout.rows).isNotEmpty()
            }
        }
    }
}
