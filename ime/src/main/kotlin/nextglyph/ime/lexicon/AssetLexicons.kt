package nextglyph.ime.lexicon

import android.content.res.AssetManager
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconLanguage
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.pack.InstalledPack
import nextglyph.engine.pack.PackFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Lexiques embarqués dans l'APK (`assets/fr-lexique.nglx`, `assets/fr-dicollecte.nglx`,
 * `assets/en.nglx`, produits par `:tools:buildLexicons`). Ils sont stockés non compressés
 * (`noCompress` dans la configuration Gradle) : chaque fichier est projeté en mémoire (`mmap`) à
 * l'endroit où il se trouve dans l'APK, sans copie ni lecture complète. Les modèles de mot suivant
 * (`assets/fr.ngrm`, `assets/en.ngrm`, #51) sont projetés de la même façon.
 *
 * Source à deux étages (issue #62, §10.7) : si un paquet de dictionnaires est installé ([pack]), ses fichiers
 * sont projetés à la place des assets, langue par langue. Si l'un des fichiers du paquet échoue (absent, tronqué,
 * illisible), toute la langue repart des assets et [onPackFailure] reçoit la langue et l'erreur : le clavier ne reste
 * jamais sans dictionnaire.
 *
 * @param pack paquet installé (`PackStore.current()`), ou `null` pour n'utiliser que les assets.
 * @param onPackFailure rappel d'un échec de lecture du paquet pour une langue (journalisation).
 */
class AssetLexicons(
    private val assets: AssetManager,
    private val pack: InstalledPack? = null,
    private val onPackFailure: (String, Exception) -> Unit = { _, _ -> },
) {

    /**
     * Ouvre les fichiers de [language] (`fr` : un par licence) et les fusionne en un [LexiconSet].
     *
     * @throws java.io.IOException si un asset est absent ou compressé.
     * @throws nextglyph.engine.lexicon.LexiconFormatException si un fichier est invalide.
     * @throws IllegalArgumentException si la langue n'a pas de lexique embarqué.
     */
    fun openSet(language: String): LexiconSet {
        val names = LexiconLanguage.files(language).map { it.name }
        return preferPack(
            language,
            pack?.let { installed -> { LexiconLanguage.assemble(language, names.map { loadFile(installed.file(it)) }) } },
            { LexiconLanguage.assemble(language, names.map(::open)) },
            onPackFailure,
        )
    }

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

    /** Ouvre le fichier d'asset [fileName] comme modèle de mot suivant. */
    fun openNextWord(fileName: String): NgramModel =
        assets.openFd(fileName).use { descriptor ->
            descriptor.createInputStream().channel.use { channel ->
                loadNextWord(channel, descriptor.startOffset, descriptor.declaredLength)
            }
        }

    /**
     * Modèles de mot suivant des [languages] (`fr.ngrm`, `en.ngrm`), du paquet installé s'il se charge, sinon des
     * assets. Un modèle illisible est signalé à [onFailure] et simplement absent du résultat : la prédiction du mot
     * suivant est alors désactivée pour cette langue seule.
     */
    fun openNextWords(languages: List<String>, onFailure: (String, Exception) -> Unit = { _, _ -> }): Map<String, NgramModel> {
        val models = LinkedHashMap<String, NgramModel>()
        for (language in languages) {
            try {
                val name = nextWordFileName(language)
                models[language] = preferPack(
                    language,
                    pack?.let { installed -> { loadNextWordFile(installed.file(name)) } },
                    { openNextWord(name) },
                    onPackFailure,
                )
            } catch (error: Exception) {
                onFailure(language, error)
            }
        }
        return models
    }

    companion object {
        /** Nom de l'asset du modèle de mot suivant de [language]. */
        fun nextWordFileName(language: String): String = PackFormat.nextWordFileName(language)

        /**
         * Charge depuis [fromPack] s'il est donné ; s'il échoue, signale l'erreur à [onFailure] et charge depuis
         * [fromAssets], dont l'échec se propage.
         */
        fun <T> preferPack(language: String, fromPack: (() -> T)?, fromAssets: () -> T, onFailure: (String, Exception) -> Unit): T {
            if (fromPack != null) {
                try {
                    return fromPack()
                } catch (error: Exception) {
                    onFailure(language, error)
                }
            }
            return fromAssets()
        }

        /** Projette tout le fichier [file] (paquet de dictionnaires) et le charge comme lexique. */
        fun loadFile(file: File): Lexicon =
            RandomAccessFile(file, "r").use { load(it.channel, 0L, it.length()) }

        /** Projette tout le fichier [file] (paquet de dictionnaires) et le charge comme modèle de mot suivant. */
        fun loadNextWordFile(file: File): NgramModel =
            RandomAccessFile(file, "r").use { loadNextWord(it.channel, 0L, it.length()) }

        /** Projette [length] octets de [channel] à partir de [offset] et les charge comme modèle de mot suivant. */
        fun loadNextWord(channel: FileChannel, offset: Long, length: Long): NgramModel =
            NgramModel.load(channel.map(FileChannel.MapMode.READ_ONLY, offset, length))

        /** Projette [length] octets de [channel] à partir de [offset] et les charge comme lexique. */
        fun load(channel: FileChannel, offset: Long, length: Long): Lexicon =
            Lexicon.load(channel.map(FileChannel.MapMode.READ_ONLY, offset, length))
    }
}
