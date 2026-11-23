package nextglyph.engine.session

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRect
import nextglyph.engine.learn.LearnOutcome
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.learn.SpatialModel
import nextglyph.engine.predict.LanguageState
import nextglyph.engine.predict.Prediction
import nextglyph.engine.predict.PredictionSettings

/**
 * Position d'une frappe relativement à sa touche **au repos** : décalage du centre en fraction de la
 * largeur de la touche ([dx]) et de la hauteur de rangée ([dy]), comme `SpatialModel.stage`.
 */
data class TapOffset(val dx: Double, val dy: Double) {
    companion object {
        /**
         * Décalage de la frappe ([x], [y]) (pixels, repère de la géométrie au repos) par rapport à [rest] ; `null`
         * si la touche n'est ni une lettre ni une touche de fonction apprise ([SpatialModel.LEARNED_KEYS]).
         */
        fun of(x: Float, y: Float, rest: KeyRect): TapOffset? {
            if (rest.key.kind != KeyKind.LETTER && rest.key.kind !in SpatialModel.LEARNED_KEYS) return null
            return TapOffset((x - rest.centerX).toDouble() / rest.width, (y - rest.centerY).toDouble() / rest.height)
        }
    }
}

/**
 * Apprentissage pendant la saisie (spec §5.3, §5.5, §10.5) : relie les événements de la session au
 * dictionnaire personnel et au modèle spatial de [data] (la mémoire des refus est alimentée par le
 * prédicteur lui-même). La politique (`LearningPolicy` : rien en incognito, mot de passe, code PIN, pavés ;
 * pas de mots en e-mail ni en URL) est appliquée par [data] à partir des réglages du champ.
 *
 * - **Frappes** : chaque lettre tapée met sa frappe en attente ([onLetter]) ; une lettre effacée retire la
 *   sienne. Les frappes d'un mot validé ne sont intégrées qu'au début du mot suivant ou à la sortie du
 *   champ ([settle]) : un ⌫ juste après la validation (annulation, ou correction du mot) les écarte.
 * - **Touches de fonction** : la frappe sur espace, entrée, ⌫ ou `?123` est mise en attente à son tour
 *   ([onKeyTap], une seule à la fois) puis validée ou écartée d'après l'événement qui la suit : ⌫ écarte
 *   espace et entrée (correction) ; un ⌫ isolé n'est pas appris (c'était peut-être un `m` ou un `n` manqué),
 *   une série de ⌫ si ; `?123` est écartée par un retour immédiat à `ABC` ou un ⌫.
 * - **Mots** : la validation d'un mot ([onValidated], [onSuggestionPicked]) est observée par le dictionnaire
 *   personnel ; annulée (⌫ immédiat, [onUndo] ou [onErase]), le mot en attente est oublié.
 * - Dans un champ sans validation par espace (e-mail, URL, `NO_SUGGESTIONS`), la fin d'un mot ([onWordEnded])
 *   intègre ses frappes.
 *
 * Non thread-safe : fil de l'interface, comme la session.
 */
class SessionLearning(val data: LearnedData) {

    private class Validated(val word: String, val language: String)

    /** Une case par lettre du mot en cours : vrai si sa frappe a été mise en attente. */
    private val taps = ArrayDeque<Boolean>()
    private var validated: Validated? = null

    /** Lettre [letter] tapée dans le mot en cours ; [offset] : position de la frappe, `null` hors tap (menu, glissé). */
    fun onLetter(letter: String, offset: TapOffset?, settings: PredictionSettings) {
        resolveKey(KeyKind.LETTER)
        settle()
        val staged = offset != null && letter.length == 1 && data.spatial.stage(letter[0], offset.dx, offset.dy, settings)
        taps.addLast(staged)
        if (taps.size > MAX_TRACKED) taps.removeFirst()
    }

    /**
     * Frappe sur la touche [kind] qui n'est pas une lettre (espace, entrée, ⌫, `?123`, `ABC`, caractère…) ;
     * [offset] : sa position, `null` hors tap ou touche non apprise. D'abord, la frappe de touche en attente
     * est résolue d'après [kind] (règles dans la description de la classe) ; puis celle-ci est mise en
     * attente si elle est apprise ([SpatialModel.LEARNED_KEYS]) et localisée. Les frappes de lettres, elles,
     * résolvent la frappe en attente par [onLetter].
     */
    fun onKeyTap(kind: KeyKind, offset: TapOffset?, settings: PredictionSettings) {
        resolveKey(kind)
        if (offset != null) data.spatial.stageKey(kind, offset.dx, offset.dy, settings)
    }

    /** Résout la frappe de touche en attente d'après l'événement suivant [next] (`LETTER` pour une lettre). */
    private fun resolveKey(next: KeyKind) {
        val pending = data.spatial.pendingKey ?: return
        val keep = when (pending) {
            KeyKind.BACKSPACE -> next == KeyKind.BACKSPACE
            KeyKind.SYMBOLS -> next != KeyKind.LETTERS && next != KeyKind.BACKSPACE
            else -> next != KeyKind.BACKSPACE
        }
        if (keep) data.spatial.confirmKey() else data.spatial.discardKey()
    }

    /**
     * ⌫ hors annulation : juste après une validation, l'annule pour l'apprentissage (frappes écartées, mot en
     * attente oublié) ; sinon retire la frappe de la dernière lettre.
     */
    fun onErase() {
        if (validated != null) {
            cancel()
            return
        }
        if (taps.removeLastOrNull() == true) data.spatial.discardLast()
    }

    /**
     * Validation du mot en cours décrite par [prediction] (espace ou ponctuation), dans l'état de langue
     * d'avant la validation. [atSentenceStart] : sa majuscule initiale venait du début de phrase.
     */
    fun onValidated(prediction: Prediction, language: LanguageState, settings: PredictionSettings, atSentenceStart: Boolean): LearnOutcome {
        settle()
        val outcome = data.personal.onValidated(prediction, language, settings, atSentenceStart)
        validate(prediction.word, language.dominant)
        return outcome
    }

    /**
     * Choix de [text] dans la barre. [unknown] : c'est le texte tapé, absent des lexiques (cas 3 du §5.5) ;
     * sinon, seul un mot déjà du dictionnaire personnel est compté.
     */
    fun onSuggestionPicked(
        text: String,
        unknown: Boolean,
        language: LanguageState,
        settings: PredictionSettings,
        atSentenceStart: Boolean,
    ): LearnOutcome {
        settle()
        val outcome = data.personal.observe(text, language.dominant, unknown, settings, atSentenceStart)
        validate(text, language.dominant)
        return outcome
    }

    /** Annulation par ⌫ d'une validation automatique (§5.5). */
    fun onUndo() = cancel()

    /** Fin d'un mot sans validation par espace (e-mail, URL, `↵`) : ses frappes sont intégrées. */
    fun onWordEnded() {
        settle()
        data.spatial.confirm()
        taps.clear()
    }

    /** Mot en cours abandonné (curseur déplacé, champ quitté ou changé) : ses frappes, et la frappe de touche en attente, sont écartées. */
    fun onWordAbandoned() {
        data.spatial.discardKey()
        settle()
        data.spatial.discardStaged()
        taps.clear()
    }

    /** Intègre les frappes du dernier mot validé, s'il y en a un : il ne peut plus être annulé. */
    fun settle() {
        if (validated == null) return
        validated = null
        data.spatial.confirm()
    }

    private fun validate(word: String, language: String) {
        validated = Validated(word, language)
        taps.clear()
    }

    private fun cancel() {
        val last = validated ?: return
        validated = null
        data.spatial.discardStaged()
        data.personal.discard(last.word, last.language)
        taps.clear()
    }

    private companion object {
        /** Lettres suivies par mot (au-delà, `SpatialModel` oublie aussi les plus anciennes). */
        const val MAX_TRACKED = 64
    }
}
