package nextglyph.engine.session

/**
 * L'éditeur du champ, vu par la session de saisie ; implémenté par l'IME au-dessus
 * d'`InputConnection` (spec §11.2). Toutes les positions sont en `char` UTF-16.
 *
 * Aucune implémentation ne journalise le texte reçu ou envoyé (§12).
 */
interface SessionTarget {
    /** Les [length] derniers caractères avant le curseur, ou `null` si le champ ne les fournit pas. */
    fun textBeforeCursor(length: Int): CharSequence?

    /** Les [length] premiers caractères après le curseur, ou `null` si le champ ne les fournit pas. */
    fun textAfterCursor(length: Int): CharSequence?

    fun beginBatchEdit()

    fun endBatchEdit()

    /** Remplace le texte en composition (ou la sélection) par [text], curseur après (`setComposingText(text, 1)`). */
    fun setComposingText(text: String)

    /** Marque le texte entre [start] et [end] comme texte en composition (`setComposingRegion`). */
    fun setComposingRegion(start: Int, end: Int)

    /** Valide le texte en composition tel quel (`finishComposingText`). */
    fun finishComposingText()

    /** Insère [text] au curseur, à la place de la sélection s'il y en a une (`commitText(text, 1)`). */
    fun commitText(text: String)

    /** Efface les [length] caractères avant le curseur (`deleteSurroundingText(length, 0)`). */
    fun deleteBefore(length: Int)

    /** Envoie la touche Retour arrière (champ qui ne rend pas son texte). */
    fun sendBackspaceKey()

    fun performEditorAction(actionId: Int)

    /** Envoie la touche Entrée (champ une ligne sans action). */
    fun sendEnterKey()
}
