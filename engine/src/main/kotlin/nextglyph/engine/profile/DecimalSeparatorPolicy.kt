package nextglyph.engine.profile

/** Clé d'un champ de saisie : paquet de l'application et identifiant du champ (§8.1). */
data class FieldKey(val packageName: String, val fieldId: Int)

/** Accès à l'éditeur nécessaire à l'insertion d'un séparateur décimal ; implémenté côté Android. */
interface SeparatorEditor {
    /** Texte avant le curseur (fenêtre limitée), ou `null` si l'application ne le fournit pas. */
    fun textBeforeCursor(): String?

    fun commitText(text: String)
}

/**
 * Choix du séparateur décimal inséré par la touche du pavé numérique (spec §8.1).
 *
 * On insère [preferred] (`,`) ; si le texte avant le curseur montre que le champ l'a refusé, on insère l'autre
 * caractère (`.`) et on mémorise ce choix pour le champ, afin de ne plus tâtonner. La mémoire est volatile
 * (jamais écrite sur disque) et bornée à [capacity] champs, les moins récents étant oubliés.
 *
 * On ne conclut au refus que si le nombre en cours (suite de chiffres et de séparateurs avant le curseur) n'a pas
 * déjà de séparateur : sinon le refus peut venir d'un second séparateur, pas du caractère.
 */
class DecimalSeparatorPolicy(
    private val preferred: Char = ',',
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(preferred == ',' || preferred == '.') { "Le séparateur préféré est ',' ou '.' : '$preferred'." }
        require(capacity > 0) { "La capacité doit être strictement positive : $capacity." }
    }

    private val alternative: Char = if (preferred == ',') '.' else ','

    private val remembered = object : LinkedHashMap<FieldKey, Char>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FieldKey, Char>): Boolean = size > capacity
    }

    /** Séparateur qui sera tenté en premier dans [field] : celui mémorisé, sinon le préféré. */
    fun separatorFor(field: FieldKey): Char = remembered[field] ?: preferred

    /** Oublie tout ce qui a été mémorisé. */
    fun clear() = remembered.clear()

    /**
     * Insère le séparateur dans [editor] et renvoie le caractère inséré, ou `null` si le champ a refusé les deux
     * (rien n'est alors inséré). Sans accès au texte, le premier essai est supposé accepté.
     */
    fun insert(field: FieldKey, editor: SeparatorEditor): Char? {
        val first = separatorFor(field)
        val second = if (first == preferred) alternative else preferred
        val before = editor.textBeforeCursor()
        val learnable = before != null && !hasSeparatorInTrailingNumber(before)
        for (candidate in charArrayOf(first, second)) {
            val reference = editor.textBeforeCursor()
            editor.commitText(candidate.toString())
            if (accepted(candidate, reference, editor.textBeforeCursor())) {
                if (learnable) remember(field, candidate)
                return candidate
            }
        }
        return null
    }

    private fun remember(field: FieldKey, separator: Char) {
        if (separator == preferred) remembered.remove(field) else remembered[field] = separator
    }

    private fun accepted(candidate: Char, before: String?, after: String?): Boolean =
        before == null || after == null || (after != before && after.endsWith(candidate))

    private fun hasSeparatorInTrailingNumber(text: String): Boolean =
        text.takeLastWhile { it.isDigit() || it == ',' || it == '.' }.any { it == ',' || it == '.' }

    companion object {
        /** Nombre maximal de champs mémorisés. */
        const val DEFAULT_CAPACITY: Int = 256
    }
}
