package nextglyph.engine.pack

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64

class PackSignatureTest {

    private val data = "manifeste".toByteArray()

    private fun pem(key: java.security.PrivateKey, wrap: Int = 64): String =
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(key.encoded).chunked(wrap).joinToString("\n") + "\n-----END PRIVATE KEY-----\n"

    @Nested
    inner class HappyPath {
        @Test
        fun `vérifie une signature valide`() {
            val pair = PackFixtures.keyPair()

            assertThat(PackSignature.verify(data, PackSignature.sign(data, pair.private), listOf(pair.public))).isTrue()
        }

        @Test
        fun `accepte la signature d'une des deux clés de confiance`() {
            val a = PackFixtures.keyPair()
            val b = PackFixtures.keyPair()

            assertThat(PackSignature.verify(data, PackSignature.sign(data, b.private), listOf(a.public, b.public))).isTrue()
        }

        @Test
        fun `relit la clé publique en SPKI base64`() {
            val pair = PackFixtures.keyPair()

            val key = PackSignature.publicKey(Base64.getEncoder().encodeToString(pair.public.encoded))

            assertThat(key.encoded).isEqualTo(pair.public.encoded)
            assertThat(PackSignature.ALGORITHM).isEqualTo("SHA256withECDSA")
        }

        @Test
        fun `relit la clé privée en PEM PKCS8`() {
            val pair = PackFixtures.keyPair()

            val key = PackSignature.privateKeyFromPem(pem(pair.private))

            assertThat(PackSignature.verify(data, PackSignature.sign(data, key), listOf(pair.public))).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `refuse la signature d'une autre clé`() {
            val signer = PackFixtures.keyPair()

            assertThat(PackSignature.verify(data, PackSignature.sign(data, signer.private), listOf(PackFixtures.keyPair().public))).isFalse()
        }

        @Test
        fun `refuse des données modifiées`() {
            val pair = PackFixtures.keyPair()
            val signature = PackSignature.sign(data, pair.private)

            assertThat(PackSignature.verify("autre".toByteArray(), signature, listOf(pair.public))).isFalse()
        }

        @Test
        fun `refuse une signature mal formée sans lever d'exception`() {
            assertThat(PackSignature.verify(data, byteArrayOf(1, 2, 3), listOf(PackFixtures.keyPair().public))).isFalse()
        }

        @Test
        fun `refuse une signature vide`() {
            assertThat(PackSignature.verify(data, ByteArray(0), listOf(PackFixtures.keyPair().public))).isFalse()
        }

        @Test
        fun `refuse tout sans clé de confiance`() {
            val pair = PackFixtures.keyPair()

            assertThat(PackSignature.verify(data, PackSignature.sign(data, pair.private), emptyList())).isFalse()
        }

        @Test
        fun `refuse une clé publique en base64 incorrect`() {
            assertThatThrownBy { PackSignature.publicKey("!!!") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Clé publique invalide")
        }

        @Test
        fun `refuse une clé publique qui n'est pas du SPKI`() {
            assertThatThrownBy { PackSignature.publicKey(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Clé publique invalide")
        }

        @Test
        fun `refuse un texte sans entête PEM`() {
            assertThatThrownBy { PackSignature.privateKeyFromPem("abc") }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PKCS#8")
        }

        @Test
        fun `refuse un PEM sans pied de page`() {
            assertThatThrownBy { PackSignature.privateKeyFromPem("-----BEGIN PRIVATE KEY-----\nAAAA") }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `refuse un PEM de base64 incorrect`() {
            assertThatThrownBy { PackSignature.privateKeyFromPem("-----BEGIN PRIVATE KEY-----\n!!!\n-----END PRIVATE KEY-----") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Clé privée invalide")
        }

        @Test
        fun `refuse un PEM qui n'est pas du PKCS8`() {
            assertThatThrownBy { PackSignature.privateKeyFromPem("-----BEGIN PRIVATE KEY-----\nAQID\n-----END PRIVATE KEY-----") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Clé privée invalide")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `signe des données vides`() {
            val pair = PackFixtures.keyPair()

            assertThat(PackSignature.verify(ByteArray(0), PackSignature.sign(ByteArray(0), pair.private), listOf(pair.public))).isTrue()
        }

        @Test
        fun `accepte un PEM sur une seule ligne avec espaces autour`() {
            val pair = PackFixtures.keyPair()
            val key = PackSignature.privateKeyFromPem("  " + pem(pair.private, wrap = 10_000).replace("\n", "") + "\n\n")

            assertThat(key.encoded).isEqualTo(pair.private.encoded)
        }

        @Test
        fun `accepte une clé publique entourée d'espaces`() {
            val pair = PackFixtures.keyPair()

            assertThat(PackSignature.publicKey(" " + Base64.getEncoder().encodeToString(pair.public.encoded) + "\n").encoded)
                .isEqualTo(pair.public.encoded)
        }
    }
}
