package nextglyph.tools.lexicons

import nextglyph.engine.lexicon.Lexicon
import nextglyph.tools.wordlists.WordEntry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class LexiconCompilerTest {

    private fun load(compiled: CompiledLexicon) = Lexicon.load(ByteBuffer.wrap(compiled.bytes))

    @Nested
    inner class HappyPath {
        @Test
        fun `compile et le fichier rechargé redonne clés, formes et fréquences`() {
            val compiled = LexiconCompiler.compile(
                "fr",
                listOf(WordEntry("a", 10), WordEntry("à", 4), WordEntry("école", 7), WordEntry("l'", 3)),
            )

            val lexicon = load(compiled)
            assertThat(compiled.skippedForms).isEmpty()
            assertThat(lexicon.languageTag).isEqualTo("fr")
            assertThat(lexicon.keyCount).isEqualTo(3)
            assertThat(lexicon.formCount).isEqualTo(4)
            assertThat(lexicon.displayForms("a").map { it.form to it.frequency }).containsExactly("a" to 10L, "à" to 4L)
            assertThat(lexicon.displayForms("ecole").map { it.form }).containsExactly("école")
        }

        @Test
        fun `les formes hors alphabet sont ignorées et rapportées`() {
            val compiled = LexiconCompiler.compile("en", listOf(WordEntry("cat", 5), WordEntry("3D", 9), WordEntry("Straße", 2)))

            assertThat(compiled.skippedForms).containsExactly("3D", "Straße")
            val lexicon = load(compiled)
            assertThat(lexicon.keyCount).isEqualTo(1)
            assertThat(lexicon.formCount).isEqualTo(1)
        }

        @Test
        fun `une forme répétée compte une seule fois`() {
            val compiled = LexiconCompiler.compile("en", listOf(WordEntry("go", 5), WordEntry("go", 6)))

            val lexicon = load(compiled)
            assertThat(lexicon.formCount).isEqualTo(1)
            assertThat(lexicon.displayForms("go").single().frequency).isEqualTo(11L)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `étiquette de langue invalide - refusée par le moteur`() {
            assertThatThrownBy { LexiconCompiler.compile("français!", listOf(WordEntry("a", 1))) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `la classe d'erreur de l'outil porte son message`() {
            val error = LexiconToolException("message", IllegalStateException("cause"))

            assertThat(error).hasMessage("message").hasCauseInstanceOf(IllegalStateException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `aucune entrée - lexique vide chargeable`() {
            val compiled = LexiconCompiler.compile("fr", emptyList())

            val lexicon = load(compiled)
            assertThat(lexicon.keyCount).isZero()
            assertThat(lexicon.formCount).isZero()
            assertThat(compiled.skippedForms).isEmpty()
        }

        @Test
        fun `toutes les formes ignorées - lexique vide`() {
            val compiled = LexiconCompiler.compile("fr", listOf(WordEntry("3D", 1)))

            assertThat(compiled.skippedForms).containsExactly("3D")
            assertThat(load(compiled).formCount).isZero()
        }

        @Test
        fun `une seule entrée`() {
            val lexicon = load(LexiconCompiler.compile("en", listOf(WordEntry("a", 1))))

            assertThat(lexicon.keyCount).isEqualTo(1)
            assertThat(lexicon.formCount).isEqualTo(1)
        }
    }
}
