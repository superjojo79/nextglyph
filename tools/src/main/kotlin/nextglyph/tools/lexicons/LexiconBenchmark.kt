package nextglyph.tools.lexicons

import nextglyph.engine.lexicon.Lexicon

/** Mesure de `Lexicon.distribution` sur des préfixes donnés. */
object LexiconBenchmark {

    /**
     * Une passe d'échauffement (compilation à chaud de la JVM), puis une passe chronométrée
     * préfixe par préfixe avec [nanoTime].
     */
    fun distributionLatency(lexicon: Lexicon, prefixes: List<String>, nanoTime: () -> Long = System::nanoTime): LatencyStats {
        for (prefix in prefixes) lexicon.distribution(prefix)
        val samples = LongArray(prefixes.size)
        for ((index, prefix) in prefixes.withIndex()) {
            val start = nanoTime()
            lexicon.distribution(prefix)
            samples[index] = nanoTime() - start
        }
        return LatencyStats.of(samples)
    }
}
