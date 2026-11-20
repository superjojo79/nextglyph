package nextglyph.engine.emoji

/** Un émoji : son texte (une ou plusieurs unités de code) et la version d'emoji qui l'a introduit. */
data class Emoji(val text: String, val version: Float)

/** Un groupe d'émojis Unicode (`smileys_emotion`, `flags`...), dans l'ordre Unicode. */
data class EmojiGroup(val id: String, val emojis: List<Emoji>)

/**
 * Catalogue des émojis (spec §5.11, issue #26), lu depuis l'asset `emoji.txt` produit par
 * `:tools:buildEmoji` : une ligne `groupe<TAB>émoji<TAB>version`, lignes vides ignorées.
 */
class EmojiCatalog(val groups: List<EmojiGroup>) {

    /** Le même catalogue limité aux émojis que [supported] accepte ; les groupes vides disparaissent. */
    fun filter(supported: (Emoji) -> Boolean): EmojiCatalog =
        EmojiCatalog(groups.map { g -> EmojiGroup(g.id, g.emojis.filter(supported)) }.filter { it.emojis.isNotEmpty() })

    companion object {
        /** Lit [lines] ; lève [IllegalArgumentException] (avec le numéro de ligne) sur une ligne mal formée. */
        fun parse(lines: Sequence<String>): EmojiCatalog {
            val groups = LinkedHashMap<String, MutableList<Emoji>>()
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val fields = line.split('\t')
                require(fields.size == 3 && fields[0].isNotEmpty() && fields[1].isNotEmpty()) { "Ligne ${index + 1} mal formée : « $line »." }
                val version = requireNotNull(fields[2].toFloatOrNull()) { "Ligne ${index + 1} : version « ${fields[2]} » invalide." }
                groups.getOrPut(fields[0]) { mutableListOf() } += Emoji(fields[1], version)
            }
            return EmojiCatalog(groups.map { (id, emojis) -> EmojiGroup(id, emojis) })
        }
    }
}

/**
 * Émojis récents (§5.11) : les plus récents d'abord, sans doublon, au plus [capacity]. Enregistrés comme
 * une chaîne (émojis séparés par une espace) ; jamais alimentés en incognito ni hors des profils texte.
 */
class EmojiRecents(private val capacity: Int = DEFAULT_CAPACITY) {

    private val items = ArrayDeque<String>()

    init {
        require(capacity > 0) { "La capacité doit être strictement positive : $capacity." }
    }

    /** Récents, du plus récent au plus ancien. */
    val list: List<String> get() = items.toList()

    /** [emoji] passe en tête ; le plus ancien est oublié au-delà de la capacité. */
    fun add(emoji: String) {
        require(emoji.isNotBlank() && emoji.none { it.isWhitespace() }) { "Émoji invalide : « $emoji »." }
        items.remove(emoji)
        items.addFirst(emoji)
        while (items.size > capacity) items.removeLast()
    }

    /** Forme enregistrée. */
    fun serialize(): String = items.joinToString(SEPARATOR)

    companion object {
        const val DEFAULT_CAPACITY: Int = 32
        private const val SEPARATOR = " "

        /** Lit une forme enregistrée, tolérante : `null` ou vide donne une liste vide, les doublons sont ignorés. */
        fun parse(stored: String?, capacity: Int = DEFAULT_CAPACITY): EmojiRecents {
            val recents = EmojiRecents(capacity)
            stored.orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }.asReversed().forEach(recents::add)
            return recents
        }
    }
}

/** Découpage d'une liste d'émojis en pages de [perPage] cases (§5.11). */
object EmojiPages {

    /** Nombre de pages (au moins 1, même pour une liste vide). */
    fun count(size: Int, perPage: Int): Int {
        require(size >= 0 && perPage > 0) { "Taille ou page invalide : $size, $perPage." }
        return maxOf(1, (size + perPage - 1) / perPage)
    }

    /** Éléments de la page [page] (bornée aux pages existantes). */
    fun <T> page(items: List<T>, page: Int, perPage: Int): List<T> {
        val p = page.coerceIn(0, count(items.size, perPage) - 1)
        return items.subList(minOf(p * perPage, items.size), minOf((p + 1) * perPage, items.size))
    }
}
