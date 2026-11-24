package nextglyph.engine.pack

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signature ECDSA P-256 / SHA-256 (DER) du manifeste. Disponible dans `java.security` sur la JVM et sur
 * Android 8 et plus, sans dépendance.
 */
object PackSignature {

    const val ALGORITHM = "SHA256withECDSA"

    private const val PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PEM_END = "-----END PRIVATE KEY-----"

    /** Signature DER de [data] avec [key]. */
    fun sign(data: ByteArray, key: PrivateKey): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }

    /** Vrai si [signature] est valide pour [data] avec l'une des [keys] ; faux sinon, y compris pour une signature mal formée. */
    fun verify(data: ByteArray, signature: ByteArray, keys: List<PublicKey>): Boolean =
        keys.any { key ->
            try {
                Signature.getInstance(ALGORITHM).run {
                    initVerify(key)
                    update(data)
                    verify(signature)
                }
            } catch (e: GeneralSecurityException) {
                false
            }
        }

    /** Clé publique EC depuis son encodage SPKI DER en base64. @throws IllegalArgumentException si elle est invalide. */
    fun publicKey(base64Spki: String): PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64Spki.trim())))
    } catch (e: GeneralSecurityException) {
        throw IllegalArgumentException("Clé publique invalide : ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Clé publique invalide : base64 incorrect.", e)
    }

    /** Clé privée EC depuis un PEM PKCS#8 (`-----BEGIN PRIVATE KEY-----`). @throws IllegalArgumentException si elle est invalide. */
    fun privateKeyFromPem(pem: String): PrivateKey {
        val trimmed = pem.trim()
        require(trimmed.startsWith(PEM_BEGIN) && trimmed.endsWith(PEM_END)) {
            "Clé privée invalide : un PEM PKCS#8 (« $PEM_BEGIN ») est attendu."
        }
        val body = trimmed.removePrefix(PEM_BEGIN).removeSuffix(PEM_END).filterNot { it.isWhitespace() }
        return try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Clé privée invalide : ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Clé privée invalide : base64 incorrect.", e)
        }
    }
}

/** Clés de confiance du moteur. */
object PackKeys {

    /**
     * Clés publiques de confiance, SPKI DER en base64 (deux emplacements pour la rotation, `docs/PUBLISHING.md`).
     *
     * TODO : vide tant que la clé du projet n'est pas générée ; aucun paquet n'est accepté dans cet état.
     */
    val TRUSTED: List<String> = emptyList()

    fun trusted(): List<PublicKey> = TRUSTED.map(PackSignature::publicKey)
}
