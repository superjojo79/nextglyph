package nextglyph.engine.learn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Une ligne de l'importation qui n'a pas été prise en compte.
 *
 * @property line numéro de ligne (à partir de 1).
 * @property reason raison, sans reproduire le contenu de la ligne.
 */
data class SkippedLine(val line: Int, val reason: String)

/**
 * Compte rendu d'une importation.
 *
 * @property imported nombre de mots importés.
 * @property skipped lignes ignorées (au plus [PersonalDictionaryTsv.MAX_REPORTED] sont détaillées).
 * @property skippedCount nombre total de lignes ignorées.
 * @property truncated le fichier a été lu en partie (trop de lignes ou trop gros).
 * @property unsupportedVersion l'en-tête annonce une version inconnue : rien n'a été importé.
 * @property unreadable le flux n'a pas pu être lu.
 */
class ImportReport(
    val imported: Int,
    val skipped: List<SkippedLine>,
    val skippedCount: Int,
    val truncated: Boolean,
    val unsupportedVersion: Boolean,
    val unreadable: Boolean,
) {
    /** Vrai si tout le fichier a été importé sans rien ignorer. */
    val isClean: Boolean get() = skippedCount == 0 && !truncated && !unsupportedVersion && !unreadable
}

/**
 * Export et import du dictionnaire personnel (spec §14, « Données apprises ») dans un format texte
 * lisible et modifiable à la main : TSV en UTF-8, une ligne par mot promu.
 *
 * ```
 * # Nextglyph dictionnaire personnel v1
 * # langue<TAB>mot<TAB>utilisations
 * fr<TAB>Zorglub<TAB>5
 * ```
 *
 * **Export** : mots promus, triés par langue puis forme ; fins de ligne `\n`. Les mots en attente et
 * les autres données apprises (refus, modèle spatial) ne sont pas exportés.
 *
 * **Import** : tolérant. Lignes vides et commentaires (`#`) ignorés ; fins de ligne `\r\n` et BOM
 * acceptés ; une ligne invalide (colonnes manquantes, langue, mot ou compteur invalide) est ignorée et
 * comptée dans le [ImportReport], sans arrêter l'importation. Le compteur est facultatif (1 par
 * défaut). Un en-tête de version inconnue (`v2`) refuse tout le fichier. Les mots importés s'ajoutent
 * au dictionnaire (un mot déjà présent voit ses compteurs additionnés) et suivent ses plafonds : le
 * dernier lu est le plus récent, et les plus anciens sont évincés s'il y a trop de mots. Taille et
 * nombre de lignes sont bornés par [LearningConfig.maxImportBytes] et [LearningConfig.maxImportLines].
 * Importer n'est pas apprendre : l'IME l'autorise même en incognito, l'utilisateur le demandant explicitement.
 */
object PersonalDictionaryTsv {

    /** Nombre maximal de lignes ignorées détaillées dans un [ImportReport]. */
    const val MAX_REPORTED: Int = 100

    /** Version du format texte. */
    const val VERSION: Int = 1

    private const val HEADER_PREFIX = "# Nextglyph dictionnaire personnel v"
    private const val BOM = '﻿'

    /** Écrit les mots promus de [dictionary] dans [out] (sans le fermer). */
    fun export(dictionary: PersonalDictionary, out: OutputStream) {
        val text = buildString {
            append(HEADER_PREFIX).append(VERSION).append('\n')
            append("# langue\tmot\tutilisations\n")
            for (word in dictionary.words()) {
                append(word.language).append('\t').append(word.form).append('\t').append(word.count).append('\n')
            }
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Importe dans [dictionary] les mots lus dans [input] (sans le fermer). Ne lève jamais d'exception. */
    fun import(input: InputStream, dictionary: PersonalDictionary): ImportReport {
        val config = dictionary.config
        val bytes = try {
            ByteCodec.readAtMost(input, config.maxImportBytes + 1)
        } catch (e: IOException) {
            return ImportReport(0, emptyList(), 0, truncated = false, unsupportedVersion = false, unreadable = true)
        }
        var truncated = bytes.size > config.maxImportBytes
        val decoded = String(bytes, 0, minOf(bytes.size, config.maxImportBytes), Charsets.UTF_8).removePrefix(BOM.toString())
        // Un fichier coupé au plafond finit au milieu d'une ligne : on abandonne cette ligne partielle.
        val text = if (truncated) decoded.substringBeforeLast('\n', "") else decoded
        val skipped = ArrayList<SkippedLine>()
        var skippedCount = 0
        var imported = 0
        fun skip(line: Int, reason: String) {
            skippedCount++
            if (skipped.size < MAX_REPORTED) skipped += SkippedLine(line, reason)
        }
        val lines = text.split('\n')
        for ((index, rawLine) in lines.withIndex()) {
            val number = index + 1
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) continue
            if (number > config.maxImportLines) {
                truncated = true
                break
            }
            if (line.startsWith("#")) {
                if (number == 1 && line.startsWith(HEADER_PREFIX) && line.removePrefix(HEADER_PREFIX).trim() != VERSION.toString()) {
                    return ImportReport(0, emptyList(), 0, truncated = false, unsupportedVersion = true, unreadable = false)
                }
                continue
            }
            val columns = line.split('\t')
            if (columns.size !in 2..3) {
                skip(number, "nombre de colonnes invalide (${columns.size})")
                continue
            }
            val count = if (columns.size == 3) columns[2].trim().toIntOrNull() else 1
            when {
                count == null -> skip(number, "nombre d'utilisations invalide")
                dictionary.importWord(columns[1].trim(), columns[0].trim(), count) -> imported++
                else -> skip(number, "langue, mot ou nombre d'utilisations invalide")
            }
        }
        return ImportReport(imported, skipped, skippedCount, truncated, unsupportedVersion = false, unreadable = false)
    }
}
