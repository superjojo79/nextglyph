package nextglyph.engine.session

import nextglyph.engine.input.EnterBehavior
import nextglyph.engine.profile.AndroidInputConstants as C

/**
 * Libellé de la touche `↵`, contextuel d'après `imeOptions` (spec §5.1) : aller, rechercher, envoyer,
 * suivant, précédent, terminé ; `↵` pour un saut de ligne, la touche Entrée ou une action inconnue.
 */
object EnterKeyLabel {

    const val ENTER: String = "↵"
    const val GO: String = "➔"
    const val SEARCH: String = "⌕"
    const val SEND: String = "➤"
    const val NEXT: String = "⇥"
    const val PREVIOUS: String = "⇤"
    const val DONE: String = "✓"

    fun of(enter: EnterBehavior): String {
        if (enter !is EnterBehavior.EditorAction) return ENTER
        return when (enter.actionId) {
            C.IME_ACTION_GO -> GO
            C.IME_ACTION_SEARCH -> SEARCH
            C.IME_ACTION_SEND -> SEND
            C.IME_ACTION_NEXT -> NEXT
            C.IME_ACTION_PREVIOUS -> PREVIOUS
            C.IME_ACTION_DONE -> DONE
            else -> ENTER
        }
    }
}
