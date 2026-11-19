package nextglyph.engine.layout

import java.util.Locale
import kotlin.math.abs

/** Disposition au choix de l'utilisateur, indépendante de la langue saisie (spec §5.1). */
enum class LayoutKind {
    AZERTY,
    QWERTY;

    companion object {
        /**
         * Disposition par défaut : AZERTY si la langue système est le français, QWERTY sinon.
         * Accepte un code de langue (`fr`) ou une balise de locale (`fr-CA`, `fr_BE`).
         */
        fun defaultForLanguage(language: String?): LayoutKind {
            val primary = language.orEmpty().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
            return if (primary == "fr") AZERTY else QWERTY
        }
    }
}

/**
 * Disposition d'un clavier : des rangées de largeur totale [ROW_UNITS] unités chacune.
 * Décrit la topologie et les largeurs au repos ; l'adaptation (spec §5.2) fournit d'autres
 * largeurs à [KeyboardGeometry.compute] sans changer la topologie.
 */
class KeyboardLayout(val kind: LayoutKind, rows: List<KeyRow>) {

    val rows: List<KeyRow> = rows.toList()

    init {
        require(this.rows.isNotEmpty()) { "Une disposition contient au moins une rangée." }
        this.rows.forEachIndexed { index, row ->
            require(isRowSum(row.restUnits)) { "La rangée $index fait ${row.restUnits} u au lieu de $ROW_UNITS u." }
        }
    }

    /** Largeurs au repos, par rangée puis par touche. */
    fun restWidths(): List<List<Float>> = rows.map(KeyRow::restWidths)

    companion object {
        /** Largeur de chaque rangée, en unités. */
        const val ROW_UNITS: Float = 10f

        /** Tolérance sur la somme d'une rangée (erreurs d'arrondi des flottants). */
        const val ROW_TOLERANCE: Float = 1e-3f

        /** Vrai si [units] vaut [ROW_UNITS] à la tolérance près. */
        fun isRowSum(units: Float): Boolean = abs(units - ROW_UNITS) <= ROW_TOLERANCE
    }
}
