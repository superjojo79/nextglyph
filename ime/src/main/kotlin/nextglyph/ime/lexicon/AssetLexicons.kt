package nextglyph.ime.lexicon

import android.content.res.AssetManager
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconLanguage
import java.nio.channels.FileChannel

/**
 * Lexiques embarqués dans l'APK (`assets/fr.nglx`, `assets/en.nglx`, produits par
 * `:tools:buildLexicons`). Ils sont stockés non compressés (`noCompress` dans la configuration
 * Gradle) : le fichier est projeté en mémoire (`mmap`) à l'endroit où il se trouve dans l'APK,
 * sans copie ni lecture complète.
 */
class AssetLexicons(private val assets: AssetManager) {

    /**
     * Ouvre le lexique de [language] (`fr` ou `en`).
     *
     * @throws java.io.IOException si l'asset est absent ou compressé.
     * @throws nextglyph.engine.lexicon.LexiconFormatException si le fichier est invalide.
     * @throws IllegalArgumentException si la langue n'a pas de lexique embarqué.
     */
    fun open(language: String): Lexicon =
        assets.openFd(LexiconLanguage.fileName(language)).use { descriptor ->
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
