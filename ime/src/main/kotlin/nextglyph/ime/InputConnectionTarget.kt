package nextglyph.ime

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import nextglyph.engine.session.SessionTarget

/**
 * [SessionTarget] au-dessus de l'`InputConnection` courante ([connection] est relue à chaque appel :
 * elle change à chaque champ et peut être absente). Simple traduction, sans logique : la session du
 * moteur décide de tout. Aucun texte saisi n'est journalisé (spec §12).
 */
class InputConnectionTarget(private val connection: () -> InputConnection?) : SessionTarget {

    override fun textBeforeCursor(length: Int): CharSequence? = connection()?.getTextBeforeCursor(length, 0)

    override fun textAfterCursor(length: Int): CharSequence? = connection()?.getTextAfterCursor(length, 0)

    override fun beginBatchEdit() {
        connection()?.beginBatchEdit()
    }

    override fun endBatchEdit() {
        connection()?.endBatchEdit()
    }

    override fun setComposingText(text: String) {
        connection()?.setComposingText(text, 1)
    }

    override fun setComposingRegion(start: Int, end: Int) {
        connection()?.setComposingRegion(start, end)
    }

    override fun finishComposingText() {
        connection()?.finishComposingText()
    }

    override fun commitText(text: String) {
        connection()?.commitText(text, 1)
    }

    override fun deleteBefore(length: Int) {
        connection()?.deleteSurroundingText(length, 0)
    }

    override fun sendBackspaceKey() {
        sendKey(KeyEvent.KEYCODE_DEL)
    }

    override fun performEditorAction(actionId: Int) {
        connection()?.performEditorAction(actionId)
    }

    override fun sendEnterKey() {
        sendKey(KeyEvent.KEYCODE_ENTER)
    }

    private fun sendKey(keyCode: Int) {
        val ic = connection() ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
