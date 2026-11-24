package nextglyph.engine.predict

import nextglyph.engine.input.TextEdit

/** Ce que fera l'appui sur espace (spec §5.5). */
enum class SpaceCase {
    /** Pas de mot en cours : l'espace insère une espace. */
    NO_WORD,

    /** Suggestions désactivées (profil, `NO_SUGGESTIONS`) : l'espace insère une espace. */
    DISABLED,

    /** Cas 1 : le texte tapé est un mot ; inséré avec restitution des accents et de la casse. */
    WORD,

    /** Cas 2 : un candidat domine ; il remplace le texte tapé. */
    DOMINANT,

    /** Cas 3 : le texte tapé est inséré tel quel. */
    LITERAL,
}

/**
 * Un candidat classé.
 *
 * @property key clé normalisée (`l'ecole` pour une élision reconstituée).
 * @property text forme qui serait insérée (accents, casse, apostrophe).
 * @property probability `P(w)` mélangée entre langues (§5.5, §10.6).
 * @property replaces texte qui précède le curseur et que ce candidat remplace quand il déborde le mot en cours
 *   (espace en trop supprimée : `ordina teur`, issue #52) ; `null` : il remplace le mot en cours.
 */
data class Candidate(val key: String, val text: String, val probability: Double, val replaces: String? = null)

/**
 * Une case de la barre de suggestions.
 *
 * @property highlighted c'est le mot que l'espace insérera à la place du texte tapé (§5.5, « Prévisibilité »).
 * @property isTyped c'est le texte tapé lui-même, proposé pour le garder tel quel.
 */
data class Suggestion(val text: String, val highlighted: Boolean = false, val isTyped: Boolean = false)

/** Barre de suggestions (§5.4) : trois cases, le candidat principal au centre. */
data class SuggestionBar(val left: Suggestion?, val center: Suggestion?, val right: Suggestion?) {

    /** Les suggestions présentes, de gauche à droite. */
    val slots: List<Suggestion> get() = listOfNotNull(left, center, right)

    val isEmpty: Boolean get() = left == null && center == null && right == null

    companion object {
        val EMPTY: SuggestionBar = SuggestionBar(null, null, null)

        /** Barre construite à partir d'une liste par ordre de priorité : 1re au centre, 2e à gauche, 3e à droite. */
        fun of(ranked: List<Suggestion>): SuggestionBar =
            SuggestionBar(ranked.getOrNull(1), ranked.getOrNull(0), ranked.getOrNull(2))
    }
}

/**
 * Résultat de [Predictor.predict] pour l'état courant : décision de l'espace et barre.
 *
 * @property word mot en cours tel qu'écrit avant le curseur (`""` s'il n'y en a pas).
 * @property key sa clé normalisée (`""` s'il n'y en a pas ou si elle sort de l'alphabet).
 * @property inserted texte qui remplacera [word] à l'appui sur espace (égal à [word] s'il est gardé).
 * @property candidates candidats classés (pour la barre et les mesures).
 * @property sentenceLanguage langue de la phrase en cours au moment de la prédiction.
 * @property span texte qui précède le curseur et que la validation remplace : [word], ou le mot précédent, l'espace
 *   et [word] quand une espace en trop est supprimée (issue #52).
 */
class Prediction(
    val word: String,
    val key: String,
    val case: SpaceCase,
    val inserted: String,
    val candidates: List<Candidate>,
    val bar: SuggestionBar,
    val sentenceLanguage: String,
    val span: String = word,
) {
    /**
     * Libellé de la touche espace (§2.5, §5.5) : le mot qui sera inséré s'il diffère du texte tapé
     * (complétion, correction, accent ou majuscule restitués), sinon `null` (espace simple).
     */
    val spaceLabel: String? get() = inserted.takeIf { it != word }

    override fun toString(): String = "Prediction(« $word » → « $inserted », $case, ${candidates.take(3)})"
}

/**
 * Jeton d'annulation d'une validation automatique (§5.5, « Annulation ») : à conserver par l'IME
 * tant que le curseur ne bouge pas et qu'aucune autre touche n'est tapée ; à oublier sinon.
 *
 * @property typed texte tapé à l'origine, restitué par l'annulation (avec l'espace en trop supprimée, issue #52).
 * @property inserted texte inséré à sa place (sans l'espace automatique qui le suit).
 * @property case [SpaceCase.DOMINANT] pour une correction, [SpaceCase.WORD] pour une restitution d'accent ou de casse.
 */
data class UndoToken(val typed: String, val inserted: String, val case: SpaceCase = SpaceCase.DOMINANT)

/**
 * Effet d'une validation : modification du texte, jeton d'annulation éventuel, score de langue mis à jour.
 */
data class Commit(val edit: TextEdit, val undo: UndoToken?, val language: LanguageState)
