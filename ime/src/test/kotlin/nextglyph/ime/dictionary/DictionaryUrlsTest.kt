package nextglyph.ime.dictionary

import nextglyph.engine.pack.PackFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DictionaryUrlsTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `les trois URL sont en https sur la release fixe dictionnaires`() {
            for (url in listOf(DictionaryUrls.MANIFEST, DictionaryUrls.SIGNATURE, DictionaryUrls.PACK)) {
                assertThat(DictionaryUrls.isHttps(url)).`as`(url).isTrue()
                assertThat(url).startsWith("https://github.com/superjojo79/nextglyph/releases/download/dictionnaires/")
            }
        }

        @Test
        fun `les noms de fichiers sont ceux du format de paquet`() {
            assertThat(DictionaryUrls.MANIFEST).endsWith("/" + PackFormat.MANIFEST)
            assertThat(DictionaryUrls.SIGNATURE).endsWith("/" + PackFormat.SIGNATURE)
            assertThat(DictionaryUrls.PACK).endsWith("/nextglyph-dictionnaires" + PackFormat.EXTENSION)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `refuse http, file, ftp et les schémas inconnus`() {
            for (url in listOf("http://github.com/x", "file:///etc/passwd", "ftp://github.com/x", "javascript:alert(1)")) {
                assertThat(DictionaryUrls.isHttps(url)).`as`(url).isFalse()
            }
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une URL vide est refusée`() {
            assertThat(DictionaryUrls.isHttps("")).isFalse()
        }

        @Test
        fun `le schéma est reconnu quelle que soit la casse, pas un préfixe voisin`() {
            assertThat(DictionaryUrls.isHttps("HTTPS://github.com")).isTrue()
            assertThat(DictionaryUrls.isHttps("https:/github.com")).isFalse()
            assertThat(DictionaryUrls.isHttps("httpsx://github.com")).isFalse()
        }
    }
}
