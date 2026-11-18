package nextglyph.tools.data

/** Besoin couvert par une source (docs/DATA.md) : a = vocabulaire, b = fréquences, c = phrases. */
enum class Need(val code: String) {
    VOCABULARY("a"),
    FREQUENCY("b"),
    SENTENCES("c"),
    ;

    companion object {
        fun fromCode(code: String): Need? = entries.firstOrNull { it.code == code }
    }
}

/** Un fichier de données à télécharger, tel que décrit dans `tools/data/sources.json`. */
data class DataSource(
    val id: String,
    val language: String,
    val needs: Set<Need>,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
    /** Nom du fichier local : dernier segment du chemin de l'URL. */
    val fileName: String,
)
