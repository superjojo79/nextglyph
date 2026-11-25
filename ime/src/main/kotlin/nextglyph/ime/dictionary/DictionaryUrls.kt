package nextglyph.ime.dictionary

/**
 * URL stables des paquets de dictionnaires (issue #62, §10.7) : fichiers de la release GitHub fixe `dictionnaires`,
 * remplacés à chaque publication. Partagées par la variante `fdroid` (qui n'ouvre que le paquet dans le navigateur)
 * et la variante `play` (qui lit aussi le manifeste et sa signature).
 */
object DictionaryUrls {
    private const val BASE = "https://github.com/superjojo79/nextglyph/releases/download/dictionnaires/"

    /** Manifeste du paquet (`manifest.txt`). */
    const val MANIFEST = BASE + "manifest.txt"

    /** Signature du manifeste (`manifest.sig`). */
    const val SIGNATURE = BASE + "manifest.sig"

    /** Paquet complet (`nextglyph-dictionnaires.ngpk`). */
    const val PACK = BASE + "nextglyph-dictionnaires.ngpk"

    /** `true` si [url] est une URL `https` ; tout autre schéma (http, file, ftp…) est refusé par le téléchargeur. */
    fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)
}
