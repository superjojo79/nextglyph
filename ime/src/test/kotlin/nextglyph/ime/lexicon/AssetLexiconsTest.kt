package nextglyph.ime.lexicon

import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.engine.lexicon.LexiconFormatException
import nextglyph.engine.ngram.NgramFormatException
import nextglyph.engine.ngram.NgramModelBuilder
import nextglyph.engine.ngram.NgramPruning
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AssetLexiconsTest {

    private val bytes: ByteArray = LexiconBuilder("fr").apply {
        add("bonjour", 10)
        add("bon", 5)
    }.build().toByteArray()

    /** Fichier [padding] octets de remplissage, le lexique, puis [trailing] octets (comme un asset dans un APK). */
    private fun archive(dir: Path, padding: Int, trailing: Int): Path {
        val file = dir.resolve("app.apk")
        Files.write(file, ByteArray(padding) { 7 } + bytes + ByteArray(trailing) { 9 })
        return file
    }

    private val nextWordBytes: ByteArray = NgramModelBuilder("fr", NgramPruning(minBigramCount = 1, minTrigramCount = 1)).apply {
        addSentence(listOf("je", "suis", "content"), 3)
        addSentence(listOf("je", "veux", "partir"))
    }.build()

    private fun <T> Path.channel(block: (FileChannel) -> T): T =
        FileChannel.open(this, StandardOpenOption.READ).use(block)

    @Nested
    inner class HappyPath {

        @Test
        fun `projette un lexique placé à un décalage dans l'archive`(@TempDir dir: Path) {
            val file = archive(dir, padding = 4_321, trailing = 77)

            val lexicon = file.channel { AssetLexicons.load(it, 4_321L, bytes.size.toLong()) }

            assertThat(lexicon.languageTag).isEqualTo("fr")
            assertThat(lexicon.keyCount).isEqualTo(2)
            assertThat(lexicon.distribution("bon").endProbability).isGreaterThan(0.0)
        }

        @Test
        fun `le lexique reste utilisable après la fermeture du canal`(@TempDir dir: Path) {
            val file = archive(dir, padding = 10, trailing = 0)
            val lexicon = file.channel { AssetLexicons.load(it, 10L, bytes.size.toLong()) }

            assertThat(lexicon.distribution("bonj").isUnknownPrefix).isFalse()
        }

        @Test
        fun `projette un modèle de mot suivant placé à un décalage dans l'archive`(@TempDir dir: Path) {
            val file = dir.resolve("app.apk")
            Files.write(file, ByteArray(321) { 7 } + nextWordBytes + ByteArray(5) { 9 })

            val model = file.channel { AssetLexicons.loadNextWord(it, 321L, nextWordBytes.size.toLong()) }

            assertThat(model.next(listOf("je"), 1).map { it.text }).containsExactly("suis")
        }

        @Test
        fun `le nom de l'asset du mot suivant dépend de la langue`() {
            assertThat(AssetLexicons.nextWordFileName("fr")).isEqualTo("fr.ngrm")
            assertThat(AssetLexicons.nextWordFileName("en")).isEqualTo("en.ngrm")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `refuse une zone qui n'est pas un modèle de mot suivant`(@TempDir dir: Path) {
            val file = archive(dir, padding = 100, trailing = 0)

            assertThatThrownBy { file.channel { AssetLexicons.loadNextWord(it, 0L, 100L) } }
                .isInstanceOf(NgramFormatException::class.java)
        }

        @Test
        fun `refuse une zone qui n'est pas un lexique`(@TempDir dir: Path) {
            val file = archive(dir, padding = 100, trailing = 0)

            assertThatThrownBy { file.channel { AssetLexicons.load(it, 0L, 100L) } }
                .isInstanceOf(LexiconFormatException::class.java)
        }

        @Test
        fun `refuse une taille qui ne correspond pas au lexique`(@TempDir dir: Path) {
            val file = archive(dir, padding = 0, trailing = 50)

            assertThatThrownBy { file.channel { AssetLexicons.load(it, 0L, bytes.size + 50L) } }
                .isInstanceOf(LexiconFormatException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un lexique au début du fichier, sans remplissage`(@TempDir dir: Path) {
            val file = archive(dir, padding = 0, trailing = 0)

            val lexicon = file.channel { AssetLexicons.load(it, 0L, bytes.size.toLong()) }

            assertThat(lexicon.sizeInBytes).isEqualTo(bytes.size)
        }

        @Test
        fun `un lexique à la toute fin du fichier`(@TempDir dir: Path) {
            val file = archive(dir, padding = 1, trailing = 0)

            val lexicon = file.channel { AssetLexicons.load(it, 1L, bytes.size.toLong()) }

            assertThat(lexicon.keyCount).isEqualTo(2)
        }
    }
}
