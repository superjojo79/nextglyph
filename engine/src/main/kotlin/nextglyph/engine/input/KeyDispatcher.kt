package nextglyph.engine.input

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind

/** Ce que fait la touche Entrée dans le champ courant, déduit de `imeOptions` par l'application. */
sealed interface EnterBehavior {
    /** Insère un saut de ligne (champ multiligne sans action). */
    data object NewLine : EnterBehavior

    /** Déclenche l'action de l'éditeur [actionId] (envoyer, rechercher, aller, suivant, terminé). */
    data class EditorAction(val actionId: Int) : EnterBehavior

    /** Envoie la touche Entrée (champ une ligne sans action). */
    data object EnterKey : EnterBehavior
}

/** Accès à l'éditeur du champ de saisie ; implémenté côté Android au-dessus d'`InputConnection`. */
interface EditorGateway {
    fun commitText(text: String)

    /** Efface la sélection si elle existe, sinon le caractère avant le curseur. */
    fun deleteBackward()

    fun performEditorAction(actionId: Int)

    fun sendEnterKey()
}

/** Traduit l'appui sur une touche en appels à l'éditeur. */
class KeyDispatcher(private val editor: EditorGateway) {

    /** Appui validé sur [key]. La touche `?123` n'a pas encore d'effet (couche symboles à venir). */
    fun onKey(key: Key, enter: EnterBehavior) {
        when (key.kind) {
            KeyKind.LETTER, KeyKind.SPACE, KeyKind.CHARACTER -> editor.commitText(checkNotNull(key.text))
            KeyKind.BACKSPACE -> editor.deleteBackward()
            KeyKind.ENTER -> when (enter) {
                EnterBehavior.NewLine -> editor.commitText("\n")
                is EnterBehavior.EditorAction -> editor.performEditorAction(enter.actionId)
                EnterBehavior.EnterKey -> editor.sendEnterKey()
            }
            // Bascules de couche, majuscule, séparateur décimal (décidé par DecimalSeparatorPolicy) et
            // emplacements vides : gérés par l'appelant, sans effet direct sur l'éditeur.
            KeyKind.SYMBOLS, KeyKind.SHIFT, KeyKind.LETTERS, KeyKind.DECIMAL, KeyKind.EMPTY, KeyKind.PC -> Unit
        }
    }
}
