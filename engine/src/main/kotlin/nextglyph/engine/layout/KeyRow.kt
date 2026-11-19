package nextglyph.engine.layout

/**
 * Une rangée de touches, de gauche à droite.
 *
 * [leadingInset] est le retrait à gauche, en unités (rangée 2 du QWERTY, spec §5.1).
 * Le retrait fait partie de la largeur de rangée : retrait + touches = [KeyboardLayout.ROW_UNITS].
 */
class KeyRow(keys: List<Key>, val leadingInset: Float = 0f) {

    val keys: List<Key> = keys.toList()

    init {
        require(this.keys.isNotEmpty()) { "Une rangée contient au moins une touche." }
        require(leadingInset.isFinite() && leadingInset >= 0f) { "Le retrait doit être positif ou nul : $leadingInset." }
    }

    /** Largeurs au repos des touches, dans l'ordre. */
    val restWidths: List<Float> = this.keys.map(Key::restWidth)

    /** Somme des largeurs au repos, retrait compris, en unités. */
    val restUnits: Float = leadingInset + restWidths.sum()

    override fun equals(other: Any?): Boolean =
        other is KeyRow && other.keys == keys && other.leadingInset == leadingInset

    override fun hashCode(): Int = 31 * keys.hashCode() + leadingInset.hashCode()

    override fun toString(): String = "KeyRow(inset=$leadingInset, keys=${keys.joinToString(" ") { it.label.ifEmpty { "_" } }})"
}
