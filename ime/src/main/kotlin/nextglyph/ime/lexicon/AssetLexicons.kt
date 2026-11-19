package nextglyph.ime.lexicon

import android.content.res.AssetManager
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.lexicon.LexiconSet
import java.nio.channels.FileChannel

/**
 * Lexiques embarqués dans l'APK (`assets/fr-lexique.nglx`, `assets/fr-dicollecte.nglx`,
 * `assets/en.nglx`, produits par `:tools:buildLexicons`). Ils sont stockés non compressés
 * (`noCompress` dans la configuration Gradle) : chaque fichier est projeté en mémoire (`mmap`) à
 * l'endroit où il se trouve dans l'APK, sans copie ni lecture complète.
 */
class AssetLexicons(private val assets: AssetManager) {

    /**
     * Ouvre les fichiers de [language] (`fr` : un par licence) et les fusionne en un [LexiconSet].
     *
     * @throws java.io.IOException si un asset est absent ou compressé.
     * @throws nextglyph.engine.lexicon.LexiconFormatException si un fichier est invalide.
     * @throws IllegalArgumentException si la langue n'a pas de lexique embarqué.
     */
    fun openSet(language: String): LexiconSet =
        LexiconLanguage.assemble(language, LexiconLanguage.files(language).map { open(it.name) })

    /** Ensembles de lexiques des [languages], dans l'ordre (langues actives, §10.6). */
    fun openAll(languages: List<String>): Map<String, LexiconSet> = languages.associateWith(::openSet)

    /** Ouvre le fichier d'asset [fileName]. */
    fun open(fileName: String): Lexicon =
        assets.openFd(fileName).use { descriptor ->
            // Le canal et le descripteur sont fermés après la projection ; elle reste valide.
            descriptor.createInputStream().channel.use { channel ->
                load(channel, descriptor.startOffset, descriptor.declaredLength)
            }
        }

    companion object {
        /** Projette [length] octets de [channel] à partir de [offset] et les charge comme lexique. */
        fun load(channel: FileChannel, offset: Long, length: Long): Lexicon =
            Lexicon.load(channel.map(FileChannel.MapMode.READ_ONLY, offset, length))
    }
}
