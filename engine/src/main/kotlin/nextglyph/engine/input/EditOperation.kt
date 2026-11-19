package nextglyph.engine.input

/**
 * Opération d'édition abstraite, produite par le moteur et appliquée par l'IME à l'`InputConnection`
 * dans un `beginBatchEdit` / `endBatchEdit`, dans l'ordre de la liste.
 *
 * Interface volontairement minimale (deux opérations) : tout autre module du moteur qui doit
 * éditer le texte s'exprime avec ces deux cas, sans en ajouter.
 */
sealed interface EditOperation {

    /**
     * Supprime [count] `char` UTF-16 juste avant le curseur (`deleteSurroundingText(count, 0)`).
     * Le module d'édition ne produit que des suppressions de caractères du plan de base
     * (espaces, signes de ponctuation).
     */
    data class DeleteBeforeCursor(val count: Int) : EditOperation {
        init {
            require(count > 0) { "Le nombre de caractères à supprimer doit être strictement positif : $count" }
        }
    }

    /** Insère [text] au curseur (`commitText(text, 1)`). */
    data class InsertText(val text: String) : EditOperation {
        init {
            require(text.isNotEmpty()) { "Le texte à insérer ne doit pas être vide" }
        }
    }
}
