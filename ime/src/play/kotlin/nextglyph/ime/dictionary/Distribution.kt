package nextglyph.ime.dictionary

/**
 * Variante `play` (§2.7, §10.7) : déclare la permission INTERNET (`src/play/AndroidManifest.xml`) pour la seule
 * recherche de mise à jour des dictionnaires, à la demande et après opt-in. Voir `src/fdroid/` pour l'autre variante.
 */
object Distribution {
    val downloader: DictionaryDownloader? = GitHubDictionaryDownloader()
}
