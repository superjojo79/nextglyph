package nextglyph.tools.lexicons

import nextglyph.engine.lexicon.InvalidFormPolicy
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.engine.text.TextNormalizer
import nextglyph.tools.wordlists.WordEntry
import java.nio.ByteBuffer

/** Lexique sérialisé et formes que le format ne peut pas indexer (hors alphabet, etc.). */
class CompiledLexicon(val bytes: ByteArray, val skippedForms: List<String>)

object LexiconCompiler {

    /**
     * Construit le lexique de [languageTag] (politique SKIP : les formes inindexables sont
     * ignorées et rapportées), puis le recharge et vérifie ses compteurs contre ceux que
     * l'on déduit des entrées.
     *
     * @throws LexiconToolException si le rechargement ne redonne pas les compteurs attendus.
     */
    fun compile(languageTag: String, entries: List<WordEntry>): CompiledLexicon {
        val builder = LexiconBuilder(languageTag, InvalidFormPolicy.SKIP)
        for (entry in entries) builder.add(entry.display, entry.frequency)
        val bytes = builder.toByteArray()
        val skipped = builder.skippedForms
        val loaded = Lexicon.load(ByteBuffer.wrap(bytes))

        val skippedSet = skipped.toSet()
        val indexed = entries.map { it.display }.filter { it !in skippedSet }.toSet()
        val expectedKeys = indexed.map(TextNormalizer::normalize).toSet().size
        if (loaded.keyCount != expectedKeys || loaded.formCount != indexed.size || loaded.languageTag != languageTag) {
            throw LexiconToolException(
                "Rechargement incohérent : ${loaded.keyCount} clés et ${loaded.formCount} formes lues, " +
                    "$expectedKeys et ${indexed.size} attendues.",
            )
        }
        return CompiledLexicon(bytes, skipped)
    }
}
