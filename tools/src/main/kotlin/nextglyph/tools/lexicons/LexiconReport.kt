package nextglyph.tools.lexicons

import java.util.Locale

/** Budget de taille par langue (spec §10.1, §11.3) : 8 Mio. */
const val SIZE_BUDGET_BYTES: Long = 8L * 1024 * 1024

/** Mesures d'un lexique construit. */
data class LexiconMeasure(
    val languageTag: String,
    val fileName: String,
    val keyCount: Int,
    val formCount: Int,
    val sizeBytes: Long,
    val skippedForms: List<String>,
    val loadNanos: Long,
    val prefixCount: Int,
    val distribution: LatencyStats?,
)

object LexiconReport {

    /** Nombre de formes ignorées citées en exemple. */
    const val SKIPPED_EXAMPLES: Int = 10

    /** Budget de `P(c | p)` (spec §11.3) : 1 ms. */
    const val DISTRIBUTION_BUDGET_NANOS: Long = 1_000_000L

    /** Taille totale des fichiers de chaque langue, dans l'ordre d'apparition. */
    fun sizesByLanguage(measures: List<LexiconMeasure>): Map<String, Long> {
        val sizes = LinkedHashMap<String, Long>()
        for (m in measures) sizes.merge(m.languageTag, m.sizeBytes, Long::plus)
        return sizes
    }

    fun render(measures: List<LexiconMeasure>, sizeBudget: Long = SIZE_BUDGET_BYTES): String = buildString {
        appendLine("# Rapport des lexiques binaires")
        appendLine()
        appendLine("Budgets (spec §11.3) : taille ≤ 8 Mio par langue (tous ses fichiers) ; `distribution` < 1 ms.")
        appendLine()
        appendLine("| Langue | Taille totale |")
        appendLine("|---|---|")
        for ((language, size) in sizesByLanguage(measures)) {
            appendLine("| $language | $size octets (${mebibytes(size)} Mio) : ${verdict(size <= sizeBudget)} |")
        }
        for (m in measures) {
            appendLine()
            appendLine("## ${m.languageTag} : `${m.fileName}`")
            appendLine()
            appendLine("| Mesure | Valeur |")
            appendLine("|---|---|")
            appendLine("| Fichier | `${m.fileName}` |")
            appendLine("| Clés | ${m.keyCount} |")
            appendLine("| Formes d'affichage | ${m.formCount} |")
            appendLine("| Taille | ${m.sizeBytes} octets (${mebibytes(m.sizeBytes)} Mio) |")
            appendLine("| Chargement (map + `Lexicon.load`) | ${millis(m.loadNanos.toDouble())} ms |")
            appendLine("| Préfixes mesurés | ${m.prefixCount} |")
            val d = m.distribution
            if (d == null) {
                appendLine("| `distribution` | non mesuré (aucun préfixe) |")
            } else {
                appendLine("| `distribution` moyenne | ${micros(d.meanNanos)} µs |")
                appendLine(
                    "| `distribution` p99 | ${micros(d.p99Nanos.toDouble())} µs : " +
                        "${verdict(d.p99Nanos < DISTRIBUTION_BUDGET_NANOS)} |",
                )
            }
            appendLine("| Formes ignorées (hors alphabet) | ${m.skippedForms.size} |")
            if (m.skippedForms.isNotEmpty()) {
                val examples = m.skippedForms.take(SKIPPED_EXAMPLES).joinToString(", ") { "`$it`" }
                appendLine()
                appendLine("Exemples de formes ignorées : $examples")
            }
        }
    }

    private fun verdict(ok: Boolean) = if (ok) "dans le budget" else "HORS BUDGET"

    private fun mebibytes(bytes: Long) = String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0))

    private fun millis(nanos: Double) = String.format(Locale.ROOT, "%.2f", nanos / 1e6)

    private fun micros(nanos: Double) = String.format(Locale.ROOT, "%.2f", nanos / 1e3)
}
