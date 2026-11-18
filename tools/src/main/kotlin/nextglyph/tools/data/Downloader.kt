package nextglyph.tools.data

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** Abstraction du téléchargement : les tests n'accèdent jamais au réseau public. */
fun interface Downloader {
    /** Écrit le contenu de [url] dans [target] ; lève [IOException] en cas d'échec. */
    fun download(url: String, target: Path)
}

/** Téléchargement HTTP(S) avec le client du JDK ; suit les redirections (jamais HTTPS vers HTTP). */
class HttpDownloader : Downloader {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    override fun download(url: String, target: Path) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(REQUEST_TIMEOUT_MINUTES))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() != HTTP_OK) throw IOException("HTTP ${response.statusCode()}")
    }

    private companion object {
        const val HTTP_OK = 200
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val REQUEST_TIMEOUT_MINUTES = 30L
    }
}
