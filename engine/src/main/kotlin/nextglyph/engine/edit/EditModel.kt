package nextglyph.engine.edit

import nextglyph.engine.input.EditOperation

/** Profil de champ, du point de vue de l'édition (§4, §9.1). */
enum class TextProfile(
    /** Espace automatique, typographie et majuscules automatiques (faux : e-mail, URL, mot de passe). */
    val automaticTypography: Boolean,
) {
    TEXT(true),
    PERSON_NAME(true),
    EMAIL(false),
    URL(false),
    PASSWORD(false),
}

/** Langue de la phrase en cours (score de langue du §10.6, calculé ailleurs). */
enum class SentenceLanguage { FRENCH, ENGLISH, OTHER }

/**
 * Règle de majuscules automatiques du champ. Android peut combiner plusieurs drapeaux
 * `TYPE_TEXT_FLAG_CAP_*` : l'application passe le plus fort (CHARACTERS > WORDS > SENTENCES).
 */
enum class CapsMode { NONE, SENTENCES, WORDS, CHARACTERS }

/** État de la touche majuscule : éteinte, pour une lettre, ou verrouillée (§5.7). */
enum class ShiftState { OFF, ONCE, LOCKED }

/** Origine de l'espace qui précède le curseur, d'après le dernier événement traité. */
enum class SpaceOrigin {
    /** Aucun espace mémorisé. */
    NONE,

    /** Espace inséré par le clavier après une validation ou un signe : un signe la remplace. */
    AUTO,

    /** Espace tapé par l'utilisateur : un second espace donne `. ` (double espace), un signe la respecte. */
    TYPED,
}

/**
 * Petit état conservé par l'IME entre deux événements. Toute autre frappe (lettre, effacement,
 * déplacement du curseur, changement de champ) le remet à [INITIAL] ; le moteur vérifie de plus
 * toujours le texte réel avant d'agir.
 */
data class EditState(val trailingSpace: SpaceOrigin = SpaceOrigin.NONE) {
    companion object {
        val INITIAL = EditState()
    }
}

/**
 * Situation de l'éditeur au moment d'un événement.
 *
 * @property textBefore texte avant le curseur : au moins [AutoCapitalization.LOOKBACK] derniers
 *   caractères, ou tout le texte s'il est plus court (le début du champ se reconnaît à sa longueur).
 * @property textAfter texte après le curseur (quelques caractères suffisent) ; vide en fin de champ.
 *   L'espace automatique n'est inséré qu'en fin de texte.
 * @property language langue de la phrase en cours ; la typographie française ne s'applique qu'au français.
 * @property shift état manuel de la touche majuscule.
 */
data class EditContext(
    val textBefore: CharSequence,
    val textAfter: CharSequence = "",
    val profile: TextProfile = TextProfile.TEXT,
    val language: SentenceLanguage = SentenceLanguage.OTHER,
    val capsMode: CapsMode = CapsMode.NONE,
    val shift: ShiftState = ShiftState.OFF,
    val state: EditState = EditState.INITIAL,
)

/**
 * Résultat d'un événement.
 *
 * @property operations à appliquer dans l'ordre, dans un seul lot d'édition (vide si rien à faire).
 * @property state nouvel état à mémoriser.
 * @property shift état de la touche majuscule à afficher une fois les opérations appliquées
 *   (majuscule manuelle conservée, sinon majuscule automatique).
 */
data class EditResult(
    val operations: List<EditOperation>,
    val state: EditState,
    val shift: ShiftState,
)
