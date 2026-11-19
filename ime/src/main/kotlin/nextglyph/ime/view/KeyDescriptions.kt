package nextglyph.ime.view

import nextglyph.engine.edit.ShiftState
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.session.EnterKeyLabel
import nextglyph.ime.R
import java.util.Locale

/**
 * Textes lus par TalkBack pour chaque touche (spec §13) : la lettre (en capitale si la majuscule est active),
 * le nom des touches de fonction, l'état de `⇧`, le mot que l'espace insérera.
 *
 * @param text lit une chaîne de ressource (`Context.getString`) ; avec un argument, `%1$s` est remplacé.
 */
class KeyDescriptions(private val text: (id: Int, arg: String?) -> String) {

    /** Description de [key], ou `null` pour un emplacement vide (aucun nœud). */
    fun describe(key: Key, label: String, shift: ShiftState, spaceLabel: String?, enterLabel: String): String? = when (key.kind) {
        KeyKind.EMPTY -> null
        KeyKind.LETTER -> if (shift != ShiftState.OFF) label.uppercase(Locale.ROOT) else label
        KeyKind.CHARACTER, KeyKind.DECIMAL -> label
        KeyKind.SPACE -> spaceLabel?.let { text(R.string.a11y_space_inserts, it.removePrefix(SPACE_PREFIX)) } ?: text(R.string.a11y_space, null)
        KeyKind.BACKSPACE -> text(R.string.a11y_backspace, null)
        KeyKind.ENTER -> text(enterName(enterLabel), null)
        KeyKind.SYMBOLS -> text(R.string.a11y_symbols, null)
        KeyKind.LETTERS -> text(R.string.a11y_letters, null)
        KeyKind.SHIFT -> text(R.string.a11y_shift, null)
    }

    /** État de `⇧` (`stateDescription`), ou `null` si la majuscule est inactive. */
    fun shiftState(shift: ShiftState): String? = when (shift) {
        ShiftState.OFF -> null
        ShiftState.ONCE -> text(R.string.a11y_shift_on, null)
        ShiftState.LOCKED -> text(R.string.a11y_shift_locked, null)
    }

    /** Description d'une suggestion de la barre. */
    fun suggestion(word: String): String = text(R.string.a11y_suggestion, word)

    /** Annonce d'un mot inséré par la validation par espace (§13). */
    fun inserted(word: String): String = text(R.string.a11y_inserted, word)

    private fun enterName(label: String): Int = when (label) {
        EnterKeyLabel.SEND -> R.string.a11y_enter_send
        EnterKeyLabel.SEARCH -> R.string.a11y_enter_search
        EnterKeyLabel.GO -> R.string.a11y_enter_go
        EnterKeyLabel.NEXT -> R.string.a11y_enter_next
        EnterKeyLabel.PREVIOUS -> R.string.a11y_enter_previous
        EnterKeyLabel.DONE -> R.string.a11y_enter_done
        else -> R.string.a11y_enter
    }

    private companion object {
        const val SPACE_PREFIX = "␣ "
    }
}
