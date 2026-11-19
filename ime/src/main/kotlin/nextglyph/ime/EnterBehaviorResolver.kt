package nextglyph.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import nextglyph.engine.input.EnterBehavior

/** Déduit le comportement de la touche Entrée de `imeOptions` et `inputType` du champ (spec §5.1). */
object EnterBehaviorResolver {

    fun resolve(imeOptions: Int, inputType: Int): EnterBehavior {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val actionAllowed = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
        return when {
            actionAllowed && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                EnterBehavior.EditorAction(action)
            isMultiLine(inputType) -> EnterBehavior.NewLine
            else -> EnterBehavior.EnterKey
        }
    }

    private fun isMultiLine(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
}
