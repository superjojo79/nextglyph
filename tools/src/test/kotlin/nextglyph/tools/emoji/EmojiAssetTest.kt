package nextglyph.tools.emoji

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class EmojiAssetTest {

    private val sample = """
        [
          {"name": "Smileys & Emotion", "slug": "smileys_emotion", "emojis": [
            {"emoji": "😀", "name": "grinning face", "emoji_version": "1.0"},
            {"emoji": "🫠", "name": "melting face", "emoji_version": "14.0"}
          ]},
          {"name": "Component", "slug": "component", "emojis": [
            {"emoji": "🏻", "name": "light skin tone", "emoji_version": "1.0"}
          ]},
          {"name": "Flags", "slug": "flags", "emojis": [
            {"emoji": "🇫🇷", "name": "flag: France", "emoji_version": "2.0"}
          ]}
        ]
    """.trimIndent()

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = BuildEmojiCli().run(arrayOf(*args), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Triple(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `une ligne par émoji, ordre conservé, composants écartés`() {
            assertThat(EmojiAsset.build(sample)).containsExactly(
                "smileys_emotion\t😀\t1.0",
                "smileys_emotion\t🫠\t14.0",
                "flags\t🇫🇷\t2.0",
            )
        }

        @Test
        fun `ligne de commande - écrit le fichier`(@TempDir dir: File) {
            val input = File(dir, "data.json").apply { writeText(sample) }
            val output = File(dir, "out/emoji.txt")

            val (code, out, _) = run(input.path, output.path)

            assertThat(code).isZero()
            assertThat(out).contains("3 émojis")
            assertThat(output.readText()).isEqualTo("smileys_emotion\t😀\t1.0\nsmileys_emotion\t🫠\t14.0\nflags\t🇫🇷\t2.0\n")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `JSON illisible ou mal formé refusé`() {
            assertThatThrownBy { EmojiAsset.build("[") }.isInstanceOf(EmojiDataException::class.java).hasMessageContaining("illisible")
            assertThatThrownBy { EmojiAsset.build("{}") }.isInstanceOf(EmojiDataException::class.java).hasMessageContaining("Tableau")
            assertThatThrownBy { EmojiAsset.build("[3]") }.isInstanceOf(EmojiDataException::class.java).hasMessageContaining("objet")
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "x"}]""") }.hasMessageContaining("emojis")
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "x", "emojis": [1]}]""") }.hasMessageContaining("objet")
            assertThatThrownBy { EmojiAsset.build("""[{"emojis": []}]""") }.hasMessageContaining("slug")
        }

        @Test
        fun `champ absent, vide, non textuel ou version invalide refusés`() {
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "x", "emojis": [{"emoji_version": "1.0"}]}]""") }.hasMessageContaining("emoji")
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "x", "emojis": [{"emoji": "", "emoji_version": "1.0"}]}]""") }.hasMessageContaining("vide")
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "x", "emojis": [{"emoji": 5, "emoji_version": "1.0"}]}]""") }.hasMessageContaining("emoji")
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "x", "emojis": [{"emoji": {}, "emoji_version": "1.0"}]}]""") }.hasMessageContaining("emoji")
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "x", "emojis": [{"emoji": "😀", "emoji_version": "un"}]}]""") }.hasMessageContaining("invalide")
        }

        @Test
        fun `ligne de commande - usage, fichier absent, données invalides`(@TempDir dir: File) {
            assertThat(run().first).isEqualTo(2)
            assertThat(run(File(dir, "absent.json").path, File(dir, "o.txt").path).first).isEqualTo(1)
            val bad = File(dir, "bad.json").apply { writeText("[") }
            val (code, _, err) = run(bad.path, File(dir, "o.txt").path)
            assertThat(code).isEqualTo(1)
            assertThat(err).contains("illisible")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `aucun émoji, ou seulement des composants - refusé`() {
            assertThatThrownBy { EmojiAsset.build("[]") }.hasMessageContaining("Aucun")
            assertThatThrownBy { EmojiAsset.build("""[{"slug": "component", "emojis": [{"emoji": "🏻", "emoji_version": "1.0"}]}]""") }
                .hasMessageContaining("Aucun")
        }

        @Test
        fun `groupe vide accepté s'il reste des émojis ailleurs`() {
            val json = """[{"slug": "a", "emojis": []}, {"slug": "b", "emojis": [{"emoji": "😀", "emoji_version": "0.6"}]}]"""

            assertThat(EmojiAsset.build(json)).containsExactly("b\t😀\t0.6")
        }

        @Test
        fun `sortie dans un dossier existant`(@TempDir dir: File) {
            val input = File(dir, "data.json").apply { writeText(sample) }

            assertThat(run(input.path, File(dir, "emoji.txt").path).first).isZero()
        }
    }
}
