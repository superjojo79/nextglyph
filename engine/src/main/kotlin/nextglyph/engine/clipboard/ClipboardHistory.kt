package nextglyph.engine.clipboard

/**
 * Historique du presse-papiers (issue #35, spec §5.13) : derniers textes copiés, le plus récent d'abord,
 * sans doublon, au plus [capacity]. **En mémoire seulement** : jamais écrit sur disque ni journalisé ; le
 * clavier ne l'alimente ni en incognito, ni dans un champ qui ne lit pas le texte (mot de passe, pavés),
 * ni pour un contenu marqué sensible.
 */
class ClipboardHistory(private val capacity: Int = DEFAULT_CAPACITY, private val maxLength: Int = MAX_LENGTH) {

    private val items = ArrayDeque<String>()

    init {
        require(capacity > 0 && maxLength > 0) { "Capacité ou longueur invalide : $capacity, $maxLength." }
    }

    /** Textes, du plus récent au plus ancien. */
    val list: List<String> get() = items.toList()

    /**
     * Ajoute [text] en tête. Ignoré s'il est vide, blanc ou plus long que [maxLength] (un très long texte
     * se recolle depuis l'application). Vrai si l'historique a changé.
     */
    fun add(text: String): Boolean {
        if (text.isBlank() || text.length > maxLength) return false
        if (items.firstOrNull() == text) return false
        items.remove(text)
        items.addFirst(text)
        while (items.size > capacity) items.removeLast()
        return true
    }

    fun clear() = items.clear()

    companion object {
        const val DEFAULT_CAPACITY: Int = 10
        const val MAX_LENGTH: Int = 5000
    }
}
