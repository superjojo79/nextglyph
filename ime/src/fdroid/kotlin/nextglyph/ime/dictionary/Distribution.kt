package nextglyph.ime.dictionary

/**
 * Variante `fdroid` (§2.7, §10.7) : aucune permission réseau, donc aucun téléchargeur. Les dictionnaires s'obtiennent
 * dans le navigateur puis s'importent depuis un fichier. Voir `src/play/` pour l'autre variante.
 */
object Distribution {
    val downloader: DictionaryDownloader? = null
}
