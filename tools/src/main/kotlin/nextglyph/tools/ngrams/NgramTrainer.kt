package nextglyph.tools.ngrams

import nextglyph.engine.ngram.NgramModelBuilder
import nextglyph.engine.ngram.NgramPruning
import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.Tokenizer

/** Construction du modèle d'une langue à partir de phrases du corpus d'entraînement. */
class NgramTrainer(language: Language, private val vocabulary: NgramVocabulary, pruning: NgramPruning = DEFAULT_PRUNING) {

    private val tokenizer = Tokenizer(language)
    private val builder = NgramModelBuilder(language.code, pruning, vocabulary::isPredictable)

    /** Nombre de phrases (au sens du clavier) apprises. */
    val sentences: Long get() = builder.sentenceCount

    /** Nombre de mots appris, inconnus compris. */
    val tokens: Long get() = builder.tokenCount

    /** Apprend une ligne du corpus, qui peut contenir plusieurs phrases. */
    fun add(line: String) {
        for (sentence in SentenceSplitter.split(line)) {
            builder.addSentence(vocabulary.forms(tokenizer.tokens(sentence)))
        }
    }

    /** Fichier du modèle. */
    fun build(): ByteArray = builder.build()

    companion object {
        /** Élagage retenu pour les modèles distribués (§10.3) : voir `docs/analysis/M51-ngrams.md`. */
        val DEFAULT_PRUNING = NgramPruning()
    }
}
