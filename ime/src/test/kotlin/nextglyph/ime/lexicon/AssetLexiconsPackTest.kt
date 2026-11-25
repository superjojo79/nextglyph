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
import java.io.File
import java.io.FileNotFoundException

/** Chargement depuis un paquet de dictionnaires installé (§10.7) et repli sur les assets. */
class AssetLexiconsPackTest {

    private val lexiconBytes: ByteArray = LexiconBuilder("fr").apply {
        add("bonjour", 10)
        add("bon", 5)
    }.build().toByteArray()

    private val nextWordBytes: ByteArray = NgramModelBuilder("fr", NgramPruning(minBigramCount = 1, minTrigramCount = 1)).apply {
        addSentence(listOf("je", "suis", "content"), 3)
        addSentence(listOf("je", "veux", "partir"))
    }.build()

    private val noFailure: (String, Exception) -> Unit = { _, _ -> }

    @Nested
    inner class HappyPath {
        @Test
        fun `charge un lexique depuis un fichier du paquet`(@TempDir dir: File) {
            val file = File(dir, "fr-lexique.nglx").apply { writeBytes(lexiconBytes) }

            val lexicon = AssetLexicons.loadFile(file)

            assertThat(lexicon.keyCount).isEqualTo(2)
            assertThat(lexicon.distribution("bon").endProbability).isGreaterThan(0.0)
        }

        @Test
        fun `charge un modèle de mot suivant depuis un fichier du paquet`(@TempDir dir: File) {
            val file = File(dir, "fr.ngrm").apply { writeBytes(nextWordBytes) }

            val model = AssetLexicons.loadNextWordFile(file)

            assertThat(model.next(listOf("je"), 1).map { it.text }).containsExactly("suis")
        }

        @Test
        fun `le paquet est préféré aux assets quand il se charge`() {
            var assetsOpened = false

            val value = AssetLexicons.preferPack("fr", { "paquet" }, { assetsOpened = true; "assets" }, noFailure)

            assertThat(value).isEqualTo("paquet")
            assertThat(assetsOpened).isFalse()
        }

        @Test
        fun `sans paquet, les assets sont utilisés sans rien signaler`() {
            val failures = mutableListOf<String>()

            val value = AssetLexicons.preferPack("fr", null, { "assets" }) { language, _ -> failures += language }

            assertThat(value).isEqualTo("assets")
            assertThat(failures).isEmpty()
        }

        @Test
        fun `le fichier lu reste utilisable après la fermeture du canal`(@TempDir dir: File) {
            val file = File(dir, "fr-lexique.nglx").apply { writeBytes(lexiconBytes) }
            val lexicon = AssetLexicons.loadFile(file)
            file.delete()

            assertThat(lexicon.distribution("bonj").isUnknownPrefix).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un échec du paquet est signalé puis la langue repart des assets`() {
            val failures = mutableListOf<Pair<String, Exception>>()
            val error = LexiconFormatException("paquet tronqué")

            val value = AssetLexicons.preferPack("fr", { throw error }, { "assets" }) { language, cause -> failures += language to cause }

            assertThat(value).isEqualTo("assets")
            assertThat(failures).containsExactly("fr" to error)
        }

        @Test
        fun `l'échec des assets après celui du paquet se propage`() {
            assertThatThrownBy {
                AssetLexicons.preferPack<String>("fr", { error("paquet") }, { throw FileNotFoundException("asset") }, noFailure)
            }.isInstanceOf(FileNotFoundException::class.java)
        }

        @Test
        fun `un fichier absent du paquet est refusé`(@TempDir dir: File) {
            assertThatThrownBy { AssetLexicons.loadFile(File(dir, "absent.nglx")) }.isInstanceOf(FileNotFoundException::class.java)
            assertThatThrownBy { AssetLexicons.loadNextWordFile(File(dir, "absent.ngrm")) }.isInstanceOf(FileNotFoundException::class.java)
        }

        @Test
        fun `un fichier du paquet qui n'est pas du bon format est refusé`(@TempDir dir: File) {
            val garbage = File(dir, "x").apply { writeBytes(ByteArray(100) { 7 }) }

            assertThatThrownBy { AssetLexicons.loadFile(garbage) }.isInstanceOf(LexiconFormatException::class.java)
            assertThatThrownBy { AssetLexicons.loadNextWordFile(garbage) }.isInstanceOf(NgramFormatException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un fichier de paquet vide est refusé`(@TempDir dir: File) {
            val empty = File(dir, "vide").apply { writeBytes(ByteArray(0)) }

            assertThatThrownBy { AssetLexicons.loadFile(empty) }.isInstanceOf(Exception::class.java)
        }

        @Test
        fun `un fichier de paquet tronqué d'un octet est refusé`(@TempDir dir: File) {
            val truncated = File(dir, "tronque").apply { writeBytes(lexiconBytes.copyOf(lexiconBytes.size - 1)) }

            assertThatThrownBy { AssetLexicons.loadFile(truncated) }.isInstanceOf(Exception::class.java)
        }

        @Test
        fun `le nom du fichier de mot suivant vient du format de paquet`() {
            assertThat(AssetLexicons.nextWordFileName("fr")).isEqualTo("fr.ngrm")
        }
    }
}
