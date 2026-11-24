package nextglyph.engine.ngram

import java.nio.ByteBuffer

/** Modèles de mot suivant de test, partagés. */
object NgramFixtures {

    /** Élagage neutre : tout ce qui est observé est gardé. */
    val KEEP_ALL = NgramPruning(
        minBigramCount = 1,
        minTrigramCount = 1,
        maxBigramFollowers = 8,
        maxTrigramFollowers = 4,
        maxVocabulary = 1000,
        minTrigramGain = 0.0,
        minConfusionCount = 1,
    )

    /** Phrases françaises (déjà découpées, élisions séparées) avec leur nombre d'occurrences. */
    val FRENCH: List<Pair<String, Int>> = listOf(
        "je suis content" to 5,
        "je suis fatigué" to 3,
        "je veux partir" to 2,
        "tu es là" to 4,
        "tu suis ça" to 2,
        "j' ai faim" to 3,
        "il est parti" to 1,
    )

    fun builder(
        sentences: List<Pair<String, Int>> = FRENCH,
        tag: String = "fr",
        pruning: NgramPruning = KEEP_ALL,
        predictable: (String) -> Boolean = { true },
    ): NgramModelBuilder = NgramModelBuilder(tag, pruning, predictable).apply {
        for ((sentence, times) in sentences) addSentence(sentence.split(' '), times)
    }

    fun bytes(
        sentences: List<Pair<String, Int>> = FRENCH,
        tag: String = "fr",
        pruning: NgramPruning = KEEP_ALL,
        predictable: (String) -> Boolean = { true },
    ): ByteArray = builder(sentences, tag, pruning, predictable).build()

    fun model(
        sentences: List<Pair<String, Int>> = FRENCH,
        tag: String = "fr",
        pruning: NgramPruning = KEEP_ALL,
        predictable: (String) -> Boolean = { true },
    ): NgramModel = NgramModel.load(ByteBuffer.wrap(bytes(sentences, tag, pruning, predictable)))
}
