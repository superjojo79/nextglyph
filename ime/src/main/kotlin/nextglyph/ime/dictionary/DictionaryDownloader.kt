package nextglyph.ime.dictionary

import java.io.IOException
import java.io.InputStream

/**
 * Accès réseau aux paquets de dictionnaires (§10.7). Défini dans le code commun, mais implémenté par la seule variante
 * `play` : voir [Distribution]. Chaque appel est bloquant (jamais sur le fil principal) et ne fait qu'un GET.
 */
interface DictionaryDownloader {

    /**
     * Télécharge le manifeste et sa signature publiés à côté du paquet.
     *
     * @return `manifest.txt` puis `manifest.sig`, octets bruts, à passer à `PackStore.checkUpdate`.
     * @throws IOException si le réseau est indisponible ou si la réponse n'est pas un succès (HTTP 200).
     */
    @Throws(IOException::class)
    fun fetchManifest(): Pair<ByteArray, ByteArray>

    /**
     * Ouvre le flux du paquet complet, borné à `PackFormat.MAX_TOTAL_BYTES` ; à fermer par l'appelant.
     *
     * @throws IOException si le réseau est indisponible ou si la réponse n'est pas un succès (HTTP 200).
     */
    @Throws(IOException::class)
    fun openPack(): InputStream
}
