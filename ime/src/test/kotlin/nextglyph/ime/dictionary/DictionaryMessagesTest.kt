package nextglyph.ime.dictionary

import nextglyph.engine.pack.PackException
import nextglyph.ime.R
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

class DictionaryMessagesTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `chaque motif de refus a son message`() {
            assertThat(DictionaryMessages.forReason(PackException.Reason.SIGNATURE)).isEqualTo(R.string.dictionaries_error_signature)
            assertThat(DictionaryMessages.forReason(PackException.Reason.INCOMPATIBLE)).isEqualTo(R.string.dictionaries_error_incompatible)
            assertThat(DictionaryMessages.forReason(PackException.Reason.OUTDATED)).isEqualTo(R.string.dictionaries_error_outdated)
            assertThat(DictionaryMessages.forReason(PackException.Reason.CORRUPT)).isEqualTo(R.string.dictionaries_error_corrupt)
            assertThat(DictionaryMessages.forReason(PackException.Reason.TOO_LARGE)).isEqualTo(R.string.dictionaries_error_too_large)
            assertThat(DictionaryMessages.forReason(PackException.Reason.IO)).isEqualTo(R.string.dictionaries_error_io)
        }

        @Test
        fun `les messages de motifs différents sont différents`() {
            val ids = PackException.Reason.entries.map(DictionaryMessages::forReason)

            assertThat(ids).doesNotHaveDuplicates()
        }

        @Test
        fun `une PackException donne le message de son motif`() {
            val error = PackException(PackException.Reason.SIGNATURE, "signature invalide")

            assertThat(DictionaryMessages.forError(error)).isEqualTo(R.string.dictionaries_error_signature)
        }

        @Test
        fun `une erreur réseau donne le message réseau indisponible`() {
            assertThat(DictionaryMessages.forError(IOException("timeout"))).isEqualTo(R.string.dictionaries_error_network)
            assertThat(DictionaryMessages.forError(FileNotFoundException("404"))).isEqualTo(R.string.dictionaries_error_network)
        }

        @Test
        fun `la version est rendue en date selon la locale`() {
            val french = DictionaryMessages.formatVersion(202611241830L, Locale.FRANCE)
            val english = DictionaryMessages.formatVersion(202611241830L, Locale.US)

            assertThat(french).contains("2026").contains("24")
            assertThat(english).contains("2026").contains("24")
            assertThat(french).isNotEqualTo(english)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une erreur inattendue donne le message d'échec générique`() {
            assertThat(DictionaryMessages.forError(IllegalStateException("bug"))).isEqualTo(R.string.dictionaries_error_io)
        }

        @Test
        fun `une version qui n'est pas une date est rendue telle quelle`() {
            assertThat(DictionaryMessages.formatVersion(202613241830L, Locale.FRANCE)).isEqualTo("202613241830")
            assertThat(DictionaryMessages.formatVersion(202611321830L, Locale.FRANCE)).isEqualTo("202611321830")
            assertThat(DictionaryMessages.formatVersion(-1L, Locale.FRANCE)).isEqualTo("-1")
            assertThat(DictionaryMessages.formatVersion(0L, Locale.FRANCE)).isEqualTo("0")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `le 29 février n'existe que les années bissextiles`() {
            assertThat(DictionaryMessages.formatVersion(202402290000L, Locale.FRANCE)).contains("2024")
            assertThat(DictionaryMessages.formatVersion(202302290000L, Locale.FRANCE)).isEqualTo("202302290000")
        }

        @Test
        fun `la taille est arrondie au Mio le plus proche, au moins 1`() {
            assertThat(DictionaryMessages.megabytes(0L)).isEqualTo(1L)
            assertThat(DictionaryMessages.megabytes(1L)).isEqualTo(1L)
            assertThat(DictionaryMessages.megabytes(1_048_576L)).isEqualTo(1L)
            assertThat(DictionaryMessages.megabytes(1_572_863L)).isEqualTo(1L)
            assertThat(DictionaryMessages.megabytes(1_572_864L)).isEqualTo(2L)
            assertThat(DictionaryMessages.megabytes(100L * 1_048_576L)).isEqualTo(100L)
        }
    }
}
