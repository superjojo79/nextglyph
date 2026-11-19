package nextglyph.tools.wordlists

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** Accès aux archives téléchargées par `fetchData` (zip, bz2, tar.gz). */
object Archives {

    /** Lit, ligne à ligne (UTF-8), la première entrée du zip dont le nom satisfait [matcher]. */
    fun <T> withZipEntryLines(zip: Path, matcher: (String) -> Boolean, block: (Sequence<String>) -> T): T =
        ZipFile(zip.toFile()).use { file ->
            val entry = file.entries().asSequence().firstOrNull { !it.isDirectory && matcher(it.name) }
                ?: throw WordListException("Aucune entrée attendue dans ${zip.fileName}")
            BufferedReader(InputStreamReader(file.getInputStream(entry), Charsets.UTF_8)).use { block(it.lineSequence()) }
        }

    fun <T> withBzip2Lines(file: Path, block: (Sequence<String>) -> T): T =
        BufferedReader(
            InputStreamReader(BZip2CompressorInputStream(BufferedInputStream(Files.newInputStream(file))), Charsets.UTF_8),
        ).use { block(it.lineSequence()) }

    /** Entrées de fichiers ordinaires d'un `.tar.gz` dont le nom satisfait [matcher], avec leur contenu. */
    fun readTarGz(file: Path, matcher: (String) -> Boolean): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(Files.newInputStream(file)))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isFile && matcher(entry.name)) result[entry.name] = tar.readAllBytes()
            }
        }
        return result
    }
}

/** Fréquences par million de Lexique 4.10 (sous-titres de films), par forme en minuscules. */
data class LexiqueFrequencies(val perMillion: Map<String, Double>, val malformedRows: Int, val zeroFrequencyRows: Int)

object LexiqueReader {

    private const val FORM_COLUMN = "1_Mot"
    private const val FREQUENCY_COLUMN = "11_FreqOrtho"

    /**
     * La fréquence de la forme (`11_FreqOrtho`, par million de mots) est répétée sur chaque
     * ligne de la forme (une par lemme et catégorie) ; on garde le maximum. Une ligne trop
     * courte ou à fréquence illisible est comptée comme mal formée ; une fréquence nulle ou
     * négative est ignorée (comptée à part).
     */
    fun read(lines: Sequence<String>): LexiqueFrequencies {
        val iterator = lines.iterator()
        if (!iterator.hasNext()) throw WordListException("Lexique vide : en-tête absent")
        val header = iterator.next().split('\t').map { it.trim().removePrefix("﻿") }
        val formIndex = header.indexOf(FORM_COLUMN)
        val frequencyIndex = header.indexOf(FREQUENCY_COLUMN)
        if (formIndex < 0 || frequencyIndex < 0) {
            throw WordListException("Lexique : colonnes $FORM_COLUMN et $FREQUENCY_COLUMN attendues dans l'en-tête")
        }
        val needed = maxOf(formIndex, frequencyIndex) + 1
        val result = HashMap<String, Double>()
        var malformed = 0
        var zero = 0
        for (line in iterator) {
            if (line.isBlank()) continue
            val columns = line.split('\t')
            val form = columns.getOrNull(formIndex)?.trim().orEmpty()
            val frequency = columns.getOrNull(frequencyIndex)?.trim()?.toDoubleOrNull()
            when {
                columns.size < needed || form.isEmpty() || frequency == null || frequency.isNaN() -> malformed++
                frequency <= 0.0 -> zero++
                else -> result.merge(canonicalApostrophes(form).lowercase(Locale.ROOT), frequency, ::maxOf)
            }
        }
        return LexiqueFrequencies(result, malformed, zero)
    }
}

/** Formes fléchies du « lexique des formes fléchies » de Dicollecte (MPL-2.0). */
data class DicollecteForms(val forms: Set<String>, val malformedRows: Int)

object DicollecteReader {

    private const val FORM_COLUMN = "Flexion"

    /**
     * Le fichier commence par un préambule (licence, statistiques de corpus), puis une ligne
     * d'en-tête `id<TAB>fid<TAB>Flexion...`, puis une ligne par (forme, lemme, étiquettes).
     */
    fun read(lines: Sequence<String>): DicollecteForms {
        val iterator = lines.iterator()
        var formIndex = -1
        while (iterator.hasNext() && formIndex < 0) {
            val line = iterator.next()
            if (line.startsWith("id\t")) formIndex = line.split('\t').indexOf(FORM_COLUMN)
        }
        if (formIndex < 0) throw WordListException("Lexique Dicollecte : en-tête avec colonne $FORM_COLUMN introuvable")
        val forms = LinkedHashSet<String>()
        var malformed = 0
        for (line in iterator) {
            if (line.isBlank()) continue
            val form = line.split('\t').getOrNull(formIndex)?.trim().orEmpty()
            if (form.isEmpty()) malformed++ else forms += canonicalApostrophes(form)
        }
        return DicollecteForms(forms, malformed)
    }
}

enum class ScowlCategory { WORD, CONTRACTION, ABBREVIATION }

data class ScowlEntry(val form: String, val level: Int, val category: ScowlCategory)

data class ScowlWords(val entries: List<ScowlEntry>, val skippedAboveLevel: Int, val files: Int)

/**
 * Listes SCOWL 2020.12.07 déjà développées (`final/american-words.50`, `final/english-upper.60`...) :
 * le suffixe numérique est le niveau (10 très courant... 95 rare).
 */
object ScowlReader {

    private val FILE_NAME = Regex("(?:^|/)final/(english|american)-(words|upper|proper-names|contractions|abbreviations)\\.(\\d+)$")

    fun isScowlFile(name: String): Boolean = FILE_NAME.containsMatchIn(name)

    /** Les formes de niveau supérieur à [maxLevel] sont ignorées (comptées). Encodage ISO-8859-1. */
    fun read(files: Map<String, ByteArray>, maxLevel: Int): ScowlWords {
        val entries = ArrayList<ScowlEntry>()
        var skipped = 0
        var used = 0
        for ((name, bytes) in files.entries.sortedBy { it.key }) {
            val match = FILE_NAME.find(name) ?: continue
            used++
            val level = match.groupValues[3].toInt()
            val category = when (match.groupValues[2]) {
                "contractions" -> ScowlCategory.CONTRACTION
                "abbreviations" -> ScowlCategory.ABBREVIATION
                else -> ScowlCategory.WORD
            }
            for (raw in String(bytes, Charsets.ISO_8859_1).lineSequence()) {
                val form = raw.trim()
                if (form.isEmpty()) continue
                if (level > maxLevel) skipped++ else entries += ScowlEntry(canonicalApostrophes(form), level, category)
            }
        }
        if (used == 0) throw WordListException("Aucune liste SCOWL (final/english-words.NN...) dans l'archive")
        return ScowlWords(entries, skipped, used)
    }
}
