package nextglyph.engine.input

/**
 * Opération d'édition abstraite sur le texte qui précède le curseur.
 *
 * Les modules du moteur qui modifient le texte (validation par espace et annulation dans
 * `nextglyph.engine.predict`, ponctuation et typographie dans `nextglyph.engine.edit`) décrivent
 * leurs effets avec ces opérations, sans dépendre d'Android. L'IME les applique telles quelles via
 * `InputConnection` (`deleteSurroundingText(n, 0)`, `commitText`), regroupées entre
 * `beginBatchEdit()` et `endBatchEdit()` (spec §11.2).
 */
sealed interface EditOperation {

    /** Efface les [length] `char` qui précèdent le curseur. */
    data class DeleteBefore(val length: Int) : EditOperation {
        init {
            require(length > 0) { "Le nombre de caractères à effacer doit être strictement positif ($length)." }
        }
    }

    /** Insère [text] au curseur. */
    data class Insert(val text: String) : EditOperation {
        init {
            require(text.isNotEmpty()) { "Le texte inséré ne doit pas être vide." }
        }
    }
}

/** Destination des opérations d'édition ; implémentée par l'IME au-dessus d'`InputConnection`. */
interface TextEditor {
    fun deleteBefore(length: Int)

    fun insert(text: String)
}

/**
 * Une modification du texte : une suite d'opérations à appliquer d'un bloc.
 *
 * Deux modifications se composent par [plus] (par exemple : validation du mot en cours par
 * `predict`, puis insertion d'un signe de ponctuation par `edit`).
 *
 * @property operations opérations, dans l'ordre d'application.
 * @property endsWithAutoSpace vrai si le dernier caractère inséré est une espace automatique
 *   (spec §3, §5.6) : un signe de ponctuation tapé ensuite peut la remplacer.
 */
data class TextEdit(val operations: List<EditOperation>, val endsWithAutoSpace: Boolean = false) {

    /** Vrai si la modification ne change rien. */
    val isEmpty: Boolean get() = operations.isEmpty()

    /** Applique les opérations à [editor], dans l'ordre. */
    fun applyTo(editor: TextEditor) {
        for (operation in operations) {
            when (operation) {
                is EditOperation.DeleteBefore -> editor.deleteBefore(operation.length)
                is EditOperation.Insert -> editor.insert(operation.text)
            }
        }
    }

    /**
     * Texte obtenu en appliquant la modification à [textBeforeCursor] (tests, simulations).
     *
     * @throws IllegalArgumentException si une opération efface plus de caractères qu'il n'y en a.
     */
    fun applyTo(textBeforeCursor: String): String {
        val buffer = StringBuilder(textBeforeCursor)
        applyTo(
            object : TextEditor {
                override fun deleteBefore(length: Int) {
                    require(length <= buffer.length) { "Impossible d'effacer $length caractères sur ${buffer.length}." }
                    buffer.setLength(buffer.length - length)
                }

                override fun insert(text: String) {
                    buffer.append(text)
                }
            },
        )
        return buffer.toString()
    }

    /** Cette modification suivie de [next] ; l'espace automatique est celle de [next]. */
    operator fun plus(next: TextEdit): TextEdit = TextEdit(operations + next.operations, next.endsWithAutoSpace)

    companion object {
        /** Modification vide. */
        val NONE: TextEdit = TextEdit(emptyList())
    }
}
