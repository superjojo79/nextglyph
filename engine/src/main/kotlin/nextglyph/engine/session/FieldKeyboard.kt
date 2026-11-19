package nextglyph.engine.session

import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardMetrics
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.SpecialLayouts
import nextglyph.engine.profile.InputProfile
import nextglyph.engine.profile.InputProfileResolver
import nextglyph.engine.profile.ProfileLayout
import nextglyph.engine.profile.ProfileLayouts

/**
 * Clavier d'un champ (spec §4, §5.1, §7.1, §8) : disposition principale, couche `?123`, hauteur des
 * rangées, présence de la barre et menu de `?123`, d'après le profil résolu. Porte aussi
 * l'échappatoire « Disposition classique » du §4 : un profil texte peut passer sur la disposition du
 * mot de passe, avec les règles du mot de passe ([InputProfileResolver.classic]), puis revenir.
 *
 * Valeur immuable : [withClassic] en donne une nouvelle.
 *
 * @property requested profil résolu pour le champ.
 * @property classic l'échappatoire est active.
 * @property decimalLabel séparateur décimal affiché sur le pavé numérique (§8.1).
 * @property profile profil effectif : [requested], ou sa version classique.
 * @property main disposition affichée à l'ouverture.
 * @property symbols couche `?123`, ou `null` (pavés : aucune bascule, §8).
 * @property rowHeightDp hauteur d'une rangée : 44 dp pour les pavés (§8), [textRowHeightDp] sinon.
 * @property showsBar barre au-dessus des touches (profils à disposition compacte seulement).
 * @property symbolsMenu contenu du menu d'appui long sur `?123`.
 * @property id identifiant de la paire ([main], [symbols]) : deux claviers de même [id] ont les mêmes dispositions.
 */
class FieldKeyboard private constructor(
    val requested: InputProfile,
    val classic: Boolean,
    private val kind: LayoutKind,
    val decimalLabel: Char,
    private val textRowHeightDp: Float,
) {
    val profile: InputProfile = if (classic) InputProfileResolver.classic(requested) else requested

    val main: KeyboardLayout = ProfileLayouts.of(profile, kind, decimalLabel)

    val symbols: KeyboardLayout? = when (profile.layout) {
        ProfileLayout.COMPACT -> SpecialLayouts.textSymbols(kind)
        ProfileLayout.PASSWORD -> SpecialLayouts.passwordSymbols(kind)
        ProfileLayout.NUMBER_PAD, ProfileLayout.PHONE_PAD, ProfileLayout.DATE_TIME_PAD, ProfileLayout.PIN_PAD -> null
    }

    val rowHeightDp: Float = ProfileLayouts.rowHeightDp(profile, textRowHeightDp)

    val showsBar: Boolean = profile.layout == ProfileLayout.COMPACT

    /** L'échappatoire est proposée : profils à disposition compacte (texte, nom, e-mail, URL). */
    val canUseClassic: Boolean = requested.layout == ProfileLayout.COMPACT

    val symbolsMenu: SymbolsMenu = when {
        classic -> SymbolsMenu.BACK_FROM_CLASSIC
        canUseClassic -> SymbolsMenu.QUICK_PUNCTUATION_AND_CLASSIC
        else -> SymbolsMenu.NONE
    }

    val id: String = buildString {
        append(main.role).append('/').append(main.kind)
        append('/').append(profile.numberSigned).append('/').append(profile.numberDecimal).append('/').append(decimalLabel)
        append('/').append(profile.dateTimeVariant)
    }

    /**
     * Le même champ avec l'échappatoire activée ou non. Sans effet (renvoie `this`) si l'état est déjà
     * celui demandé, ou si le profil n'a pas de disposition compacte.
     */
    fun withClassic(enabled: Boolean): FieldKeyboard =
        if (enabled == classic || !canUseClassic) this else FieldKeyboard(requested, enabled, kind, decimalLabel, textRowHeightDp)

    /**
     * Le même champ avec le séparateur décimal [label] (appris par `DecimalSeparatorPolicy`, §8.1) ; `this` si
     * c'est déjà le sien.
     */
    fun withDecimalLabel(label: Char): FieldKeyboard =
        if (label == decimalLabel) this else FieldKeyboard(requested, classic, kind, label, textRowHeightDp)

    companion object {
        /**
         * Clavier du champ de profil [profile].
         *
         * @param kind disposition des lettres choisie par l'utilisateur (AZERTY / QWERTY).
         * @param decimalLabel séparateur décimal affiché sur le pavé numérique (§8.1).
         * @param textRowHeightDp hauteur des rangées hors pavés (réglage, §13).
         */
        fun of(
            profile: InputProfile,
            kind: LayoutKind,
            decimalLabel: Char = ',',
            textRowHeightDp: Float = KeyboardMetrics.DEFAULT_ROW_HEIGHT_DP,
        ): FieldKeyboard = FieldKeyboard(profile, false, kind, decimalLabel, textRowHeightDp)
    }
}
