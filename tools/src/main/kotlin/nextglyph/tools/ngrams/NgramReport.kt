package nextglyph.tools.ngrams

import java.util.Locale

/** Budget de taille d'un modèle de mot suivant (issue #51) : 5 Mio par langue. */
const val NGRAM_SIZE_BUDGET_BYTES: Long = 5L * 1024 * 1024

/** Mesures d'un modèle construit. */
data class NgramMeasure(
    val languageTag: String,
    val fileName: String,
    val sizeBytes: Long,
    val vocabulary: Int,
    val bigrams: Int,
    val trigramContexts: Int,
    val trigrams: Int,
    val trainSentences: Long,
    val trainTokens: Long,
    val loadNanos: Long,
    val hits: NgramHitRates,
)

object NgramReport {

    fun render(measures: List<NgramMeasure>, sizeBudget: Long = NGRAM_SIZE_BUDGET_BYTES): String = buildString {
        appendLine("# Rapport des modèles de mot suivant")
        appendLine()
        appendLine("Budget (issue #51) : taille ≤ ${mebibytes(sizeBudget)} Mio par langue. Source : Tatoeba (CC BY 2.0 FR), découpage entraînement / test de `SentencePartition`.")
        appendLine()
        appendLine("| Langue | Taille | Top-1 (positions éligibles) | Top-3 (positions éligibles) |")
        appendLine("|---|---|---|---|")
        for (m in measures) {
            appendLine(
                "| ${m.languageTag} | ${m.sizeBytes} octets (${mebibytes(m.sizeBytes)} Mio) : ${verdict(m.sizeBytes <= sizeBudget)} | " +
                    "${percent(m.hits.top1OfEligible)} | ${percent(m.hits.top3OfEligible)} |",
            )
        }
        for (m in measures) {
            appendLine()
            appendLine("## ${m.languageTag} : `${m.fileName}`")
            appendLine()
            appendLine("| Mesure | Valeur |")
            appendLine("|---|---|")
            appendLine("| Taille | ${m.sizeBytes} octets (${mebibytes(m.sizeBytes)} Mio) |")
            appendLine("| Vocabulaire | ${m.vocabulary} mots |")
            appendLine("| Bigrammes gardés | ${m.bigrams} |")
            appendLine("| Contextes de trigrammes | ${m.trigramContexts} |")
            appendLine("| Trigrammes gardés | ${m.trigrams} |")
            appendLine("| Phrases d'entraînement | ${m.trainSentences} |")
            appendLine("| Mots d'entraînement | ${m.trainTokens} |")
            appendLine("| Chargement (map + `NgramModel.load`) | ${millis(m.loadNanos)} ms |")
            appendLine("| Positions du test | ${m.hits.positions} |")
            appendLine("| Positions éligibles (mot proposable, dans le vocabulaire) | ${m.hits.eligible} |")
            appendLine("| Top-1, positions éligibles | ${percent(m.hits.top1OfEligible)} |")
            appendLine("| Top-3, positions éligibles | ${percent(m.hits.top3OfEligible)} |")
            appendLine("| Top-1, toutes positions | ${percent(m.hits.top1OfAll)} |")
            appendLine("| Top-3, toutes positions | ${percent(m.hits.top3OfAll)} |")
        }
    }

    private fun verdict(ok: Boolean) = if (ok) "dans le budget" else "HORS BUDGET"

    private fun mebibytes(bytes: Long) = String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0))

    private fun millis(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1e6)

    private fun percent(ratio: Double) = String.format(Locale.ROOT, "%.2f %%", ratio * 100.0)
}
