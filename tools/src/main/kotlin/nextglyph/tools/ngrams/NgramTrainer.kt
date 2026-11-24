package nextglyph.tools.ngrams

import nextglyph.engine.ngram.NgramModelBuilder
import nextglyph.engine.ngram.NgramPruning
import nextglyph.engine.predict.ConfusionConfig
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.Tokenizer

/**
 * Construction du modèle d'une langue à partir de phrases du corpus d'entraînement : bigrammes et trigrammes, et
 * comptes des formes confondues ([confusionSets], issue #55) après chaque mot précédent d'un même groupe de mots
 * (sans virgule ni autre signe entre eux, comme le clavier lit le contexte).
 */
class NgramTrainer(
    language: Language,
    private val vocabulary: NgramVocabulary,
    pruning: NgramPruning = DEFAULT_PRUNING,
    confusionSets: List<List<String>> = ConfusionConfig().sets[language.code].orEmpty().map { it.forms },
) {

    private val tokenizer = Tokenizer(language)
    private val builder = NgramModelBuilder(language.code, pruning, vocabulary::isPredictable, confusionSets)

    /** Nombre de phrases (au sens du clavier) apprises. */
    val sentences: Long get() = builder.sentenceCount

    /** Nombre de mots appris, inconnus compris. */
    val tokens: Long get() = builder.tokenCount

    /** Apprend une ligne du corpus, qui peut contenir plusieurs phrases. */
    fun add(line: String) {
        for (sentence in SentenceSplitter.split(line)) {
            builder.addSentence(vocabulary.forms(tokenizer.tokens(sentence)))
            for (clause in ClauseSplitter.split(sentence)) builder.addConfusionContext(vocabulary.forms(tokenizer.tokens(clause)))
        }
    }

    /** Fichier du modèle. */
    fun build(): ByteArray = builder.build()

    companion object {
        /** Élagage retenu pour les modèles distribués (§10.3) : voir `docs/analysis/M51-ngrams.md`. */
        val DEFAULT_PRUNING = NgramPruning()
    }
}
