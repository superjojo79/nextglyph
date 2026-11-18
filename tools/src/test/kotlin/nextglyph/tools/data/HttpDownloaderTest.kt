package nextglyph.tools.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/** Serveur HTTP local sur la boucle locale : aucun accès au réseau public. */
class HttpDownloaderTest {

    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private lateinit var server: HttpServer
    private lateinit var base: String

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ok") { reply(it, 200, payload) }
        server.createContext("/empty") { reply(it, 200, ByteArray(0)) }
        server.createContext("/missing") { reply(it, 404, "introuvable".toByteArray()) }
        server.createContext("/error") { reply(it, 500, "erreur".toByteArray()) }
        server.createContext("/redirect") {
            it.responseHeaders.add("Location", "/ok")
            it.sendResponseHeaders(302, -1)
            it.close()
        }
        server.start()
        base = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun reply(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `télécharge le contenu exact`(@TempDir dir: Path) {
            val target = dir.resolve("out.bin")

            HttpDownloader().download("$base/ok", target)

            assertThat(Files.readAllBytes(target)).isEqualTo(payload)
        }

        @Test
        fun `suit une redirection`(@TempDir dir: Path) {
            val target = dir.resolve("out.bin")

            HttpDownloader().download("$base/redirect", target)

            assertThat(Files.readAllBytes(target)).isEqualTo(payload)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `lève une IOException avec le code HTTP 404`(@TempDir dir: Path) {
            assertThatThrownBy { HttpDownloader().download("$base/missing", dir.resolve("out.bin")) }
                .isInstanceOf(IOException::class.java)
                .hasMessage("HTTP 404")
        }

        @Test
        fun `lève une IOException avec le code HTTP 500`(@TempDir dir: Path) {
            assertThatThrownBy { HttpDownloader().download("$base/error", dir.resolve("out.bin")) }
                .isInstanceOf(IOException::class.java)
                .hasMessage("HTTP 500")
        }

        @Test
        fun `lève une IOException si le serveur est injoignable`(@TempDir dir: Path) {
            val url = "$base/ok"
            server.stop(0)

            assertThatThrownBy { HttpDownloader().download(url, dir.resolve("out.bin")) }
                .isInstanceOf(IOException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une réponse 200 vide produit un fichier vide`(@TempDir dir: Path) {
            val target = dir.resolve("out.bin")

            HttpDownloader().download("$base/empty", target)

            assertThat(Files.size(target)).isEqualTo(0L)
        }

        @Test
        fun `le code 200 est le seul accepté, un 204 est refusé`(@TempDir dir: Path) {
            server.createContext("/nocontent") {
                it.sendResponseHeaders(204, -1)
                it.close()
            }

            assertThatThrownBy { HttpDownloader().download("$base/nocontent", dir.resolve("out.bin")) }
                .isInstanceOf(IOException::class.java)
                .hasMessage("HTTP 204")
        }
    }
}
