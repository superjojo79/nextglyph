package nextglyph.engine.lexicon

import kotlin.random.Random

/**
 * Générateur de lexique synthétique « à la française », pour les mesures et les tests
 * de cohérence : radicaux syllabiques, familles de flexions (verbes, noms, adjectifs),
 * environ un tiers de formes accentuées, quelques clés à deux formes, fréquences de Zipf.
 */
object SyntheticLexicon {

    private const val CONSONANTS = "bcdfghjlmnprstvz"
    private val CONSONANT_WEIGHTS = intArrayOf(3, 5, 6, 3, 2, 1, 1, 6, 4, 5, 4, 8, 7, 6, 3, 1)
    private const val VOWELS = "aeiou"
    private val VOWEL_WEIGHTS = intArrayOf(8, 10, 5, 5, 4)

    private val VERB = listOf(
        "er", "e", "es", "ent", "ons", "ez", "ais", "ait", "aient", "ai", "as", "a", "erai", "eras", "era",
        "erons", "erez", "eront", "erais", "erait", "erions", "eriez", "eraient", "ant", "ee", "ees",
        "asse", "assent", "ions", "iez", "at", "ames", "ates", "erent",
    )
    private val NOUN = listOf("", "s")
    private val ADJECTIVE = listOf("", "e", "s", "es", "ement")

    /** [keyCount] clés distinctes et leurs formes d'affichage, avec fréquences. */
    fun entries(keyCount: Int, seed: Int = 42): List<Pair<String, Long>> {
        val random = Random(seed)
        val keys = LinkedHashSet<String>()
        while (keys.size < keyCount) {
            val stem = buildString { repeat(listOf(1, 2, 2, 3, 3, 4).random(random)) { append(syllable(random)) } }
            val family = random.nextDouble().let { if (it < 0.3) VERB else if (it < 0.7) NOUN else ADJECTIVE }
            for (suffix in family) {
                if (keys.size < keyCount) keys += stem + suffix
            }
        }
        val ranks = (1..keyCount).shuffled(random)
        val result = ArrayList<Pair<String, Long>>(keyCount + keyCount / 20)
        keys.forEachIndexed { i, key ->
            val frequency = 1_000_000_000L / ranks[i] + 1
            val accented = accent(key, random)
            when {
                accented == key -> result += key to frequency
                random.nextDouble() < 0.1 -> {
                    result += accented to frequency
                    result += key to frequency / 3 + 1
                }
                else -> result += accented to frequency
            }
        }
        return result
    }

    private fun syllable(random: Random): String = buildString {
        if (random.nextDouble() < 0.85) append(weighted(CONSONANTS, CONSONANT_WEIGHTS, random))
        append(weighted(VOWELS, VOWEL_WEIGHTS, random))
        if (random.nextDouble() < 0.25) append(weighted(CONSONANTS, CONSONANT_WEIGHTS, random))
    }

    private fun weighted(chars: String, weights: IntArray, random: Random): Char {
        var pick = random.nextInt(weights.sum())
        for (i in weights.indices) {
            pick -= weights[i]
            if (pick < 0) return chars[i]
        }
        error("inaccessible")
    }

    /** Accentue un `e` sur cinq (é, è ou ê) : environ un tiers des formes portent un accent. */
    private fun accent(key: String, random: Random): String {
        val chars = key.toCharArray()
        for (i in chars.indices) {
            if (chars[i] == 'e' && random.nextDouble() < 0.2) chars[i] = "éèê"[random.nextInt(3)]
        }
        return String(chars)
    }
}
