package nextglyph.tools.lexicons

import nextglyph.tools.wordlists.Language
import nextglyph.tools.wordlists.WordEntry
import nextglyph.tools.wordlists.sortedForOutput
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths

/** Export des listes de mots au format JSON du prototype : `[{"w": forme, "f": fréquence}]`. */
object PrototypeExport {

    const val DEFAULT_LIMIT: Int = 100_000

    /**
     * Les [limit] formes les plus fréquentes (égalités : ordre de [sortedForOutput]), une par ligne.
     *
     * @throws IllegalArgumentException si [limit] est négatif.
     */
    fun toJson(entries: List<WordEntry>, limit: Int): String {
        require(limit >= 0) { "Le nombre de formes doit être positif ou nul ($limit)." }
        val top = sortedForOutput(entries).take(limit)
        if (top.isEmpty()) return "[]\n"
        return top.joinToString(separator = ",\n", prefix = "[\n", postfix = "\n]\n") {
            """{"w":${quote(it.display)},"f":${it.frequency}}"""
        }
    }

    /** Chaîne JSON : guillemet, antislash et caractères de contrôle échappés, le reste tel quel (UTF-8). */
    fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> append(String.format("\\u%04x", ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}

/**
 * `exportPrototypeData <listes> <sortie> [N]` : écrit `<sortie>/<lang>.json` pour chaque langue
 * à partir de `<listes>/<lang>.tsv`, limité aux N formes les plus fréquentes (100 000 par défaut).
 */
object ExportPrototypeDataCli {

    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1

    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        if (args.size !in 2..3) {
            err.println("Usage : exportPrototypeData <dossier des listes> <dossier de sortie> [N]")
            return EXIT_FAILURE
        }
        val limit = if (args.size == 3) args[2].toIntOrNull() else PrototypeExport.DEFAULT_LIMIT
        if (limit == null || limit < 0) {
            err.println("N invalide « ${args[2]} » : entier positif ou nul attendu.")
            return EXIT_FAILURE
        }
        return try {
            val outDir = Paths.get(args[1])
            Files.createDirectories(outDir)
            for (language in Language.entries) {
                val tsv = Paths.get(args[0]).resolve("${language.code}.tsv")
                val entries = WordListTsv.parse(Files.readAllLines(tsv), tsv.fileName.toString())
                val target = outDir.resolve("${language.code}.json")
                Files.writeString(target, PrototypeExport.toJson(entries, limit))
                out.println("${target.fileName} : ${minOf(limit, entries.size)} formes sur ${entries.size}")
            }
            EXIT_OK
        } catch (e: LexiconToolException) {
            err.println(e.message)
            EXIT_FAILURE
        } catch (e: IOException) {
            err.println("Lecture ou écriture impossible : ${e.message}")
            EXIT_FAILURE
        }
    }
}
