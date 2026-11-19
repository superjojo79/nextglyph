package nextglyph.engine.profile

import nextglyph.engine.layout.DateTimeVariant
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.SpecialLayouts

/** Construit la disposition d'un [InputProfile] (spec §4, colonne « Disposition »). */
object ProfileLayouts {

    /**
     * Disposition de repos du profil. [kind] (AZERTY ou QWERTY) ne joue que pour les dispositions
     * à lettres : compacte et mot de passe. [decimalLabel] est le séparateur décimal de la locale.
     */
    fun of(profile: InputProfile, kind: LayoutKind, decimalLabel: Char = ','): KeyboardLayout = when (profile.layout) {
        ProfileLayout.COMPACT -> KeyboardLayouts.of(kind)
        ProfileLayout.PASSWORD -> SpecialLayouts.password(kind)
        ProfileLayout.NUMBER_PAD -> SpecialLayouts.number(profile.numberSigned, profile.numberDecimal, decimalLabel)
        ProfileLayout.PHONE_PAD -> SpecialLayouts.phone()
        ProfileLayout.DATE_TIME_PAD -> SpecialLayouts.dateTime(profile.dateTimeVariant ?: DateTimeVariant.DATE_TIME)
        ProfileLayout.PIN_PAD -> SpecialLayouts.pin()
    }

    /** Hauteur d'une rangée en dp : 44 dp pour les pavés (§8), [textRowHeightDp] sinon. */
    fun rowHeightDp(profile: InputProfile, textRowHeightDp: Float = KeyboardMetrics.DEFAULT_ROW_HEIGHT_DP): Float =
        when (profile.layout) {
            ProfileLayout.COMPACT, ProfileLayout.PASSWORD -> textRowHeightDp
            ProfileLayout.NUMBER_PAD, ProfileLayout.PHONE_PAD, ProfileLayout.DATE_TIME_PAD, ProfileLayout.PIN_PAD ->
                KeyboardMetrics.PAD_ROW_HEIGHT_DP
        }
}
