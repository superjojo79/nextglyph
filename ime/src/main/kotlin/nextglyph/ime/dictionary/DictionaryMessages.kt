package nextglyph.ime.dictionary

import nextglyph.engine.pack.PackException
import nextglyph.ime.R
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Textes des réglages « Dictionnaires » (§10.7), sans dépendance au cadre Android : correspondance d'une erreur avec sa
 * ressource et mise en forme de la version d'un paquet. Testable sur la JVM.
 */
object DictionaryMessages {

    private const val BYTES_PER_MB = 1_048_576L

    /** Ressource du message de refus d'un paquet pour le motif [reason]. */
    fun forReason(reason: PackException.Reason): Int = when (reason) {
        PackException.Reason.SIGNATURE -> R.string.dictionaries_error_signature
        PackException.Reason.INCOMPATIBLE -> R.string.dictionaries_error_incompatible
        PackException.Reason.CORRUPT -> R.string.dictionaries_error_corrupt
        PackException.Reason.OUTDATED -> R.string.dictionaries_error_outdated
        PackException.Reason.TOO_LARGE -> R.string.dictionaries_error_too_large
        PackException.Reason.IO -> R.string.dictionaries_error_io
    }

    /**
     * Ressource du message d'une erreur d'installation ou de recherche : le motif d'une [PackException], le réseau
     * indisponible pour une [IOException], sinon l'échec générique.
     */
    fun forError(error: Throwable): Int = when (error) {
        is PackException -> forReason(error.reason)
        is IOException -> R.string.dictionaries_error_network
        else -> R.string.dictionaries_error_io
    }

    /**
     * Date de construction d'une version `yyyyMMddHHmm` (UTC), selon [locale] (ex. « 24/11/2026 »). Une version qui
     * n'est pas une date valide est rendue telle quelle.
     */
    fun formatVersion(version: Long, locale: Locale): String {
        val datePart = version / 10_000L
        val year = datePart / 10_000L
        val month = datePart / 100L % 100L
        val day = datePart % 100L
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        } catch (_: DateTimeException) {
            version.toString()
        }
    }

    /** Taille arrondie au Mio le plus proche, d'au moins 1 (affichage « environ N Mo »). */
    fun megabytes(bytes: Long): Long = maxOf(1L, (bytes + BYTES_PER_MB / 2) / BYTES_PER_MB)
}
