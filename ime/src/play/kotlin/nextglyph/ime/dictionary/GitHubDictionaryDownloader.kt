package nextglyph.ime.dictionary

import nextglyph.engine.pack.PackFormat
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Téléchargeur de la variante `play` (§10.7) : deux GET HTTPS vers les fichiers de la release GitHub `dictionnaires`
 * ([DictionaryUrls]), jamais en arrière-plan. La requête ne porte que `User-Agent: Nextglyph` (sans version ni
 * identifiant) ; ni cookie, ni cache, ni autre en-tête personnalisé. Les redirections HTTPS de GitHub vers son stockage
 * sont suivies par le cadre Android, qui refuse celles qui changent de protocole. Les réponses sont plafonnées.
 */
class GitHubDictionaryDownloader : DictionaryDownloader {

    override fun fetchManifest(): Pair<ByteArray, ByteArray> =
        get(DictionaryUrls.MANIFEST, MAX_SMALL_BYTES) to get(DictionaryUrls.SIGNATURE, MAX_SMALL_BYTES)

    override fun openPack(): InputStream = open(DictionaryUrls.PACK, PackFormat.MAX_TOTAL_BYTES)

    private fun get(url: String, limit: Long): ByteArray = open(url, limit).use { it.readBytes() }

    /** Ouvre [url] ; le flux est borné à [limit] octets et ferme la connexion avec lui. */
    private fun open(url: String, limit: Long): InputStream {
        if (!DictionaryUrls.isHttps(url)) throw IOException("URL non HTTPS refusée.")
        val connection = URI(url).toURL().openConnection() as? HttpsURLConnection ?: throw IOException("Connexion HTTPS attendue.")
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Réponse HTTP ${connection.responseCode}.")
            }
            val stream = connection.inputStream
            return object : BoundedInputStream(stream, limit) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
    }

    private companion object {
        const val USER_AGENT = "Nextglyph"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_SMALL_BYTES = 64L * 1024L
    }
}
