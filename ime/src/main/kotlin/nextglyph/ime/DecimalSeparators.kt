package nextglyph.ime

import java.text.DecimalFormatSymbols
import java.util.Locale

/** Séparateur décimal de la locale, ramené à `,` ou `.` (seuls caractères gérés par `DecimalSeparatorPolicy`). */
object DecimalSeparators {
    fun preferred(locale: Locale?): Char {
        val separator = DecimalFormatSymbols.getInstance(locale ?: Locale.ROOT).decimalSeparator
        return if (separator == '.') '.' else ','
    }
}
