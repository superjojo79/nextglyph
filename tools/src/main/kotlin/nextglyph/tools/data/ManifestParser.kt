package nextglyph.tools.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.net.URI
import java.net.URISyntaxException

/** Manifeste invalide : le message dit précisément quoi corriger. */
class ManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lit et valide le manifeste `sources.json`. */
object ManifestParser {

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")
    private val LANGUAGE_PATTERN = Regex("^[a-z]{2}$")

    fun parse(json: String): List<DataSource> {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            throw ManifestException("JSON invalide : ${e.message}", e)
        }
        if (!root.isJsonObject) throw ManifestException("Le manifeste doit être un objet JSON avec un champ « sources »")
        val sources = root.asJsonObject.get("sources")
        if (sources == null || !sources.isJsonArray) {
            throw ManifestException("Champ « sources » absent ou n'est pas un tableau")
        }
        if (sources.asJsonArray.isEmpty) throw ManifestException("Le manifeste ne contient aucune source")

        val parsed = sources.asJsonArray.mapIndexed { index, element -> parseSource(element, index) }
        requireUnique(parsed.map { it.id }, "id")
        requireUnique(parsed.map { it.fileName }, "nom de fichier")
        return parsed
    }

    private fun parseSource(element: JsonElement, index: Int): DataSource {
        val where = "source n°${index + 1}"
        if (!element.isJsonObject) throw ManifestException("$where : doit être un objet JSON")
        val obj = element.asJsonObject

        val id = text(obj, "id", where)
        if (!ID_PATTERN.matches(id)) throw ManifestException("$where : id « $id » invalide (minuscules, chiffres, tirets)")
        val label = "source « $id »"

        val language = text(obj, "language", label)
        if (!LANGUAGE_PATTERN.matches(language)) {
            throw ManifestException("$label : language « $language » invalide (code à deux lettres minuscules)")
        }
        val needs = needsOf(text(obj, "need", label), label)
        val url = text(obj, "url", label)
        val fileName = fileNameOf(url, label)
        val sha256 = text(obj, "sha256", label)
        if (!SHA256_PATTERN.matches(sha256)) {
            throw ManifestException("$label : sha256 mal formé (64 caractères hexadécimaux minuscules attendus)")
        }
        val size = sizeOf(obj, label)
        val license = text(obj, "license", label)
        return DataSource(id, language, needs, url, sha256, size, license, fileName)
    }

    /** « a », « b », « c », ou une combinaison séparée par « + » (ex. « a+b »). */
    private fun needsOf(raw: String, where: String): Set<Need> =
        raw.split('+').map { code ->
            Need.fromCode(code) ?: throw ManifestException("$where : need « $raw » invalide (a, b, c ou combinaison a+b)")
        }.toCollection(LinkedHashSet())

    private fun text(obj: JsonObject, key: String, where: String): String {
        val value = obj.get(key)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw ManifestException("$where : champ « $key » absent ou n'est pas une chaîne")
        }
        val text = value.asString
        if (text.isBlank()) throw ManifestException("$where : champ « $key » vide")
        return text
    }

    private fun sizeOf(obj: JsonObject, where: String): Long {
        val value = obj.get("size")
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw ManifestException("$where : champ « size » absent ou n'est pas un nombre")
        }
        val size = value.asString.toLongOrNull()
            ?: throw ManifestException("$where : « size » doit être un entier d'octets")
        if (size <= 0) throw ManifestException("$where : « size » doit être strictement positif")
        return size
    }

    private fun fileNameOf(url: String, where: String): String {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw ManifestException("$where : url invalide : ${e.reason}", e)
        }
        if (uri.scheme != "https" || uri.host.isNullOrEmpty()) {
            throw ManifestException("$where : l'url doit être de la forme https://hôte/chemin")
        }
        val name = uri.path.orEmpty().substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") {
            throw ManifestException("$where : l'url doit se terminer par un nom de fichier")
        }
        return name
    }

    private fun requireUnique(values: List<String>, label: String) {
        val duplicate = values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicate != null) throw ManifestException("Doublon de $label : « $duplicate »")
    }
}
