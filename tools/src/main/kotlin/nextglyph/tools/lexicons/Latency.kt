package nextglyph.tools.lexicons

import kotlin.math.ceil

/** Statistiques de durées, en nanosecondes. */
data class LatencyStats(val count: Int, val meanNanos: Double, val p99Nanos: Long) {

    companion object {
        /** Moyenne et 99e centile (rang le plus proche : le plus petit échantillon couvrant 99 %). */
        fun of(samples: LongArray): LatencyStats {
            require(samples.isNotEmpty()) { "Aucun échantillon de durée." }
            val sorted = samples.sortedArray()
            val rank = ceil(sorted.size * 0.99).toInt()
            return LatencyStats(sorted.size, sorted.average(), sorted[rank - 1])
        }
    }
}
