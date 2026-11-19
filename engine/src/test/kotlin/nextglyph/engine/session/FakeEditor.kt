package nextglyph.engine.session

/**
 * Éditeur simulé, au comportement d'un `EditText` : texte, sélection, région de composition.
 * Les mises à jour de sélection produites par les lots d'édition sont mises en file
 * ([updates]) et livrées à la session par [deliver], comme `onUpdateSelection`.
 */
class FakeEditor(initial: String = "", var providesText: Boolean = true) : SessionTarget {
    val text = StringBuilder(initial)
    var selStart = initial.length
    var selEnd = initial.length
    var composeStart = -1
    var composeEnd = -1
    var reads = 0
    val calls = mutableListOf<String>()
    val updates = ArrayDeque<IntArray>()

    /** Insertions, compositions et suppressions faites hors d'un lot d'édition (doit rester 0). */
    var outsideBatch = 0

    /** Lots d'édition fermés. */
    var batches = 0
    private var depth = 0

    private fun edited() {
        if (depth == 0) outsideBatch++
    }

    override fun textBeforeCursor(length: Int): CharSequence? {
        reads++
        if (!providesText) return null
        return text.substring(maxOf(0, selStart - length), selStart)
    }

    override fun textAfterCursor(length: Int): CharSequence? {
        reads++
        if (!providesText) return null
        return text.substring(selEnd, minOf(text.length, selEnd + length))
    }

    override fun beginBatchEdit() {
        depth++
    }

    override fun endBatchEdit() {
        depth--
        batches++
        if (depth == 0) updates.addLast(intArrayOf(selStart, selEnd, composeStart, composeEnd))
    }

    override fun setComposingText(text: String) {
        edited()
        calls += "compose:$text"
        val (start, end) = if (composeStart >= 0) composeStart to composeEnd else selStart to selEnd
        this.text.replace(start, end, text)
        selStart = start + text.length
        selEnd = selStart
        if (text.isEmpty()) {
            composeStart = -1
            composeEnd = -1
        } else {
            composeStart = start
            composeEnd = selStart
        }
    }

    override fun setComposingRegion(start: Int, end: Int) {
        edited()
        calls += "region:$start:$end"
        composeStart = start
        composeEnd = end
    }

    override fun finishComposingText() {
        calls += "finish"
        composeStart = -1
        composeEnd = -1
    }

    override fun commitText(text: String) {
        edited()
        calls += "commit:$text"
        val (start, end) = if (composeStart >= 0) composeStart to composeEnd else selStart to selEnd
        this.text.replace(start, end, text)
        selStart = start + text.length
        selEnd = selStart
        composeStart = -1
        composeEnd = -1
    }

    override fun deleteBefore(length: Int) {
        edited()
        calls += "delete:$length"
        val start = maxOf(0, selStart - length)
        text.delete(start, selStart)
        selEnd -= selStart - start
        selStart = start
    }

    override fun sendBackspaceKey() {
        calls += "key:backspace"
    }

    override fun performEditorAction(actionId: Int) {
        calls += "action:$actionId"
    }

    override fun sendEnterKey() {
        calls += "key:enter"
    }

    /** Livre toutes les mises à jour en attente à [session]. */
    fun deliver(session: InputSession) {
        while (updates.isNotEmpty()) {
            val u = updates.removeFirst()
            session.onSelectionUpdate(u[0], u[1], u[2], u[3])
        }
    }

    /** Déplace le curseur comme un toucher de l'utilisateur, et le signale à [session]. */
    fun moveCursor(session: InputSession, position: Int) {
        updates.clear()
        selStart = position
        selEnd = position
        composeStart = -1
        composeEnd = -1
        session.onSelectionUpdate(position, position, -1, -1)
    }

    override fun toString(): String = text.toString()
}
