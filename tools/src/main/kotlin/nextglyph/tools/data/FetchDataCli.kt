package nextglyph.tools.data

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths

/** Ligne de commande de `:tools:fetchData` : `<manifeste> <dossier de sortie>`. Retourne le code de sortie. */
object FetchDataCli {

    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1
    const val EXIT_USAGE = 2

    fun run(args: Array<String>, downloader: Downloader, out: PrintStream, err: PrintStream): Int {
        if (args.size != 2) {
            err.println("Usage : fetchData <sources.json> <dossier de sortie>")
            return EXIT_USAGE
        }
        val manifest = Paths.get(args[0])
        if (!Files.isRegularFile(manifest)) {
            err.println("Manifeste introuvable : $manifest")
            return EXIT_FAILURE
        }
        return try {
            val sources = ManifestParser.parse(Files.readString(manifest))
            val outcomes = DataFetcher(downloader) { out.println(it) }.fetch(sources, Paths.get(args[1]))
            val downloaded = outcomes.count { it is FetchOutcome.Downloaded }
            out.println("${outcomes.size} fichier(s) prêt(s) dans ${args[1]} ($downloaded téléchargé(s))")
            EXIT_OK
        } catch (e: ManifestException) {
            err.println("Manifeste invalide ($manifest) : ${e.message}")
            EXIT_FAILURE
        } catch (e: DataFetchException) {
            err.println(e.message)
            EXIT_FAILURE
        }
    }
}
