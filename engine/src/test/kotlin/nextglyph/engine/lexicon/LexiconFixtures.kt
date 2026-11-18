package nextglyph.engine.lexicon

import java.nio.ByteBuffer

/** Lexiques de test partagés. */
object LexiconFixtures {

    /**
     * Petit lexique couvrant les cas de structure : clé à deux formes (`a`/`à`), mot préfixe
     * d'un autre (`chat`/`chats`), étiquettes fusionnées (`ch`, `ien`), apostrophe, trait d'union.
     *
     * Masses : `a…` = 115, `c…` = 80, `p…` = 25 ; total 220.
     */
    val SAMPLE: List<Pair<String, Long>> = listOf(
        "a" to 50L,
        "à" to 30L,
        "abri" to 20L,
        "aujourd'hui" to 15L,
        "chat" to 40L,
        "chats" to 10L,
        "chien" to 30L,
        "peut-être" to 25L,
    )

    fun sample(): Lexicon = lexicon(SAMPLE)

    fun lexicon(entries: List<Pair<String, Long>>, tag: String = "fr"): Lexicon = builder(entries, tag).build()

    fun builder(entries: List<Pair<String, Long>>, tag: String = "fr"): LexiconBuilder =
        LexiconBuilder(tag).apply { entries.forEach { (form, frequency) -> add(form, frequency) } }

    /** Fichier binaire écrit à la main : en-tête valide autour de la zone de données [data]. */
    fun rawFile(
        data: IntArray,
        rootOffset: Int,
        tag: String = "fr",
        keyCount: Int = 1,
        formCount: Int = 1,
        dataLength: Int = data.size,
    ): ByteArray {
        val sink = ByteSink()
        sink.writeBytes(LexiconFormat.MAGIC)
        sink.writeShort(LexiconFormat.VERSION)
        sink.writeByte(tag.length)
        sink.writeBytes(tag.toByteArray(Charsets.US_ASCII))
        sink.writeInt(keyCount)
        sink.writeInt(formCount)
        sink.writeInt(rootOffset)
        sink.writeInt(dataLength)
        data.forEach(sink::writeByte)
        return sink.toByteArray()
    }

    fun rawLexicon(data: IntArray, rootOffset: Int): Lexicon = Lexicon.load(ByteBuffer.wrap(rawFile(data, rootOffset)))
}
