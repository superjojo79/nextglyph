package nextglyph.ime

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import nextglyph.engine.input.EditorGateway
import nextglyph.engine.input.TextUnits

/**
 * [EditorGateway] au-dessus de l'`InputConnection` courante ([connection] est relue à chaque appel :
 * elle change à chaque champ et peut être absente). Aucun texte saisi n'est journalisé (spec §12).
 */
class InputConnectionEditor(private val connection: () -> InputConnection?) : EditorGateway {

    override fun commitText(text: String) {
        connection()?.commitText(text, 1)
    }

    override fun deleteBackward() {
        val ic = connection() ?: return
        val selected = ic.getSelectedText(0)
        if (selected != null && selected.isNotEmpty()) {
            ic.commitText("", 1)
            return
        }
        val length = TextUnits.lastCodePointLength(ic.getTextBeforeCursor(2, 0) ?: "")
        if (length > 0) {
            ic.deleteSurroundingText(length, 0)
        } else {
            // Champ qui ne rend pas son texte (terminal, WebView) : repli sur la touche Retour arrière.
            sendKey(ic, KeyEvent.KEYCODE_DEL)
        }
    }

    override fun performEditorAction(actionId: Int) {
        connection()?.performEditorAction(actionId)
    }

    override fun sendEnterKey() {
        val ic = connection() ?: return
        sendKey(ic, KeyEvent.KEYCODE_ENTER)
    }

    private fun sendKey(ic: InputConnection, keyCode: Int) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
