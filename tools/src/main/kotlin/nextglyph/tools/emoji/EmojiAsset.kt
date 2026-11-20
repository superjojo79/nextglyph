package nextglyph.tools.emoji

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.io.PrintStream

/** Fichier `data-by-group.json` illisible ou mal formé. */
class EmojiDataException(message: String) : Exception(message)

/**
 * Construit l'asset `emoji.txt` du clavier (issue #26) à partir de `data-by-group.json` du paquet npm
 * `unicode-emoji-json` (MIT, données Unicode ; `docs/DATA.md`).
 *
 * Une ligne par émoji : `groupe<TAB>émoji<TAB>version d'emoji`, dans l'ordre Unicode. Le groupe
 * « Component » (teintes de peau, cheveux) est écarté : ce ne sont pas des émojis à insérer seuls.
 */
object EmojiAsset {

    /** Groupe écarté. */
    const val COMPONENT_GROUP: String = "component"

    fun build(json: String): List<String> {
        val groups = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            throw EmojiDataException("JSON illisible : ${e.message}")
        }
        if (groups !is JsonArray) throw EmojiDataException("Tableau de groupes attendu.")
        val lines = mutableListOf<String>()
        for ((index, element) in groups.withIndex()) {
            val group = element as? JsonObject ?: throw EmojiDataException("Groupe $index : objet attendu.")
            val slug = text(group, "slug", "groupe $index")
            if (slug == COMPONENT_GROUP) continue
            val emojis = group.get("emojis") as? JsonArray ?: throw EmojiDataException("Groupe $slug : tableau « emojis » attendu.")
            for ((i, e) in emojis.withIndex()) {
                val emoji = e as? JsonObject ?: throw EmojiDataException("Groupe $slug, émoji $i : objet attendu.")
                val where = "groupe $slug, émoji $i"
                val text = text(emoji, "emoji", where)
                val version = text(emoji, "emoji_version", where)
                if (version.toFloatOrNull() == null) throw EmojiDataException("$where : version « $version » invalide.")
                lines += "$slug\t$text\t$version"
            }
        }
        if (lines.isEmpty()) throw EmojiDataException("Aucun émoji.")
        return lines
    }

    private fun text(obj: JsonObject, name: String, where: String): String {
        val value = obj.get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isEmpty()) {
            throw EmojiDataException("$where : champ « $name » absent ou vide.")
        }
        return value.asString
    }
}

/** Ligne de commande : `<data-by-group.json> <emoji.txt>`. Codes de sortie : 0 succès, 1 erreur de données, 2 usage. */
class BuildEmojiCli {
    fun run(args: Array<String>, out: PrintStream, err: PrintStream): Int {
        if (args.size != 2) {
            err.println("Usage : buildEmoji <data-by-group.json> <emoji.txt>")
            return 2
        }
        val input = File(args[0])
        if (!input.isFile) {
            err.println("Fichier introuvable : ${input.path}")
            return 1
        }
        return try {
            val lines = EmojiAsset.build(input.readText())
            val output = File(args[1])
            output.parentFile?.mkdirs()
            output.writeText(lines.joinToString("\n", postfix = "\n"))
            out.println("${lines.size} émojis écrits dans ${output.path}")
            0
        } catch (e: EmojiDataException) {
            err.println(e.message)
            1
        }
    }
}
