package nextglyph.engine.predict

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet

/** Petits lexiques construits à la main pour les tests de prédiction. */
object PredictFixtures {

    /** Français : élisions, accents, formes ambiguës, nom propre homographe. Initiales voyelles : 2 755. */
    val FRENCH: List<Pair<String, Long>> = listOf(
        "le" to 1000L,
        "la" to 900L,
        "les" to 800L,
        "l'" to 700L,
        "j'" to 500L,
        "c'" to 400L,
        "ai" to 600L,
        "est" to 900L,
        "école" to 50L,
        "écoles" to 10L,
        "a" to 500L,
        "à" to 400L,
        "ou" to 100L,
        "où" to 80L,
        "bonjour" to 200L,
        "bonne" to 50L,
        "bon" to 100L,
        "peut-être" to 60L,
        "peut" to 70L,
        "aujourd'hui" to 30L,
        "dont" to 40L,
        "paris" to 20L,
        "Paris" to 1L,
        "i" to 5L,
        "résumé" to 10L,
        "ami" to 40L,
        "amis" to 20L,
        "lami" to 1L,
    )

    /** Anglais : contractions, pronom `I`, jours, paires ambiguës. */
    val ENGLISH: List<Pair<String, Long>> = listOf(
        "the" to 2000L,
        "I" to 1000L,
        "i" to 2L,
        "I'm" to 300L,
        "don't" to 400L,
        "its" to 50L,
        "it's" to 200L,
        "well" to 100L,
        "we'll" to 30L,
        "Monday" to 40L,
        "resume" to 20L,
        "hello" to 100L,
        "help" to 80L,
    )

    fun french(): LexiconSet = LexiconSet(LexiconFixtures.lexicon(FRENCH, "fr"))

    fun english(): LexiconSet = LexiconSet(LexiconFixtures.lexicon(ENGLISH, "en"))

    fun frenchPredictor(rejections: RejectionStore = InMemoryRejectionStore(), config: PredictionConfig = PredictionConfig()): Predictor =
        Predictor(mapOf("fr" to french()), KeyboardLayouts.AZERTY, config, rejections)

    fun englishPredictor(): Predictor = Predictor(mapOf("en" to english()), KeyboardLayouts.QWERTY)

    fun bilingualPredictor(cacheSize: Int = 0): Predictor =
        Predictor(mapOf("fr" to french(), "en" to english()), KeyboardLayouts.AZERTY, cacheSize = cacheSize)

    /** `chat` et `chien` : pour `ch`, `P(chat) = chat / (chat + chien + 0,002 · (chat + chien))` (lettre en trop `c`). */
    fun catPredictor(chat: Long = 75L, chien: Long = 25L): Predictor =
        Predictor(mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("chat" to chat, "chien" to chien)))), KeyboardLayouts.AZERTY)
}
