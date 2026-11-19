package nextglyph.engine.profile

import nextglyph.engine.layout.DateTimeVariant

/** Profil de saisie choisi pour un champ (spec §4), dans l'ordre de priorité. */
enum class ProfileType {
    PIN,
    NUMBER,
    PHONE,
    DATE_TIME,
    PASSWORD,
    EMAIL,
    URL,
    PERSON_NAME,
    TEXT,
}

/** Disposition à utiliser pour un profil (spec §4, colonne « Disposition »). */
enum class ProfileLayout {
    /** Disposition compacte AZERTY / QWERTY (§5.1). */
    COMPACT,

    /** Classique à 6 rangées (§7.1). */
    PASSWORD,

    /** Pavé numérique (§8.1). */
    NUMBER_PAD,

    /** Pavé téléphone (§8.2). */
    PHONE_PAD,

    /** Pavé date / heure (§8.3). */
    DATE_TIME_PAD,

    /** Pavé du code PIN (§8.4). */
    PIN_PAD,
}

/** Règle de majuscules automatiques demandée par le champ (`TYPE_TEXT_FLAG_CAP_*`, §5.7). */
enum class CapitalizationMode { NONE, CHARACTERS, WORDS, SENTENCES }

/** Barre de raccourcis permanente à la place des suggestions (§9.1). */
enum class ShortcutBar(val shortcuts: List<String>) {
    NONE(emptyList()),
    EMAIL(listOf("@", ".", ".com", ".fr", "-", "_")),
    URL(listOf("/", ".", ".com", ".fr", "-")),
}

/**
 * Comportements du clavier pour un champ : encode les tableaux du §4, du §7.2, du §8 et du §9.
 * Produit par [InputProfileResolver.resolve].
 */
data class InputProfile(
    val type: ProfileType,
    val layout: ProfileLayout,
    /** Adaptation visuelle des touches (§5.2). */
    val adaptation: Boolean,
    /** Barre de suggestions de mots et validation par espace (§5.4, §5.5). */
    val suggestions: Boolean,
    /** Le clavier lit le texte avant le curseur (préfixe du mot en cours). */
    val readsText: Boolean,
    /** Apprentissage (lexique, modèle spatial, dictionnaire personnel). */
    val learning: Boolean,
    /** Majuscules automatiques (§5.7). */
    val autoCapitalize: Boolean,
    /** Règle de majuscules demandée ; [CapitalizationMode.NONE] quand [autoCapitalize] est faux. */
    val capitalization: CapitalizationMode,
    /** Typographie française (espaces insécables, guillemets, §5.6). */
    val typography: Boolean,
    /** `λ = 0` : décodage purement spatial, sans modèle de langue (§7.2). */
    val zeroLambda: Boolean,
    /** Aperçu agrandi de la touche appuyée. */
    val keyPreview: Boolean,
    /** `↵` insère un saut de ligne (`TYPE_TEXT_FLAG_MULTI_LINE`). */
    val multiLine: Boolean,
    /** Mode incognito (`IME_FLAG_NO_PERSONALIZED_LEARNING`, §9.2) : aucune écriture, quel que soit le profil. */
    val incognito: Boolean,
    /** Noms des contacts prioritaires dans les suggestions (§6). */
    val contactsPriority: Boolean,
    /** Barre de raccourcis permanente (§9.1). */
    val shortcutBar: ShortcutBar,
    /** Pavé numérique : `−` proposé (`TYPE_NUMBER_FLAG_SIGNED`). Faux hors profil numérique. */
    val numberSigned: Boolean,
    /** Pavé numérique : séparateur décimal proposé (`TYPE_NUMBER_FLAG_DECIMAL`). Faux hors profil numérique. */
    val numberDecimal: Boolean,
    /** Pavé date / heure : variation. `null` hors profil date / heure. */
    val dateTimeVariant: DateTimeVariant?,
)
