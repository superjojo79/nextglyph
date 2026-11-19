package nextglyph.engine.layout

/** Dimensions du clavier en dp (spec §5.1). */
object KeyboardMetrics {

    /** Hauteur de la barre de suggestions. */
    const val SUGGESTION_BAR_DP: Float = 32f

    /** Hauteur par défaut d'une rangée de touches (réglable de 34 à 46 dp, §13). */
    const val DEFAULT_ROW_HEIGHT_DP: Float = 38f

    /** Hauteur d'une touche des pavés numériques, téléphone, date/heure et PIN (§8). */
    const val PAD_ROW_HEIGHT_DP: Float = 44f

    /** Espacement vertical entre deux rangées. */
    const val ROW_GAP_DP: Float = 4f

    /** Plafond de la hauteur totale à la hauteur de rangée par défaut. */
    const val MAX_DEFAULT_TOTAL_DP: Float = 160f

    /**
     * Hauteur totale en dp : barre de suggestions, [rowCount] rangées de [rowHeightDp]
     * et l'espacement entre rangées.
     */
    fun totalHeightDp(rowCount: Int, rowHeightDp: Float = DEFAULT_ROW_HEIGHT_DP): Float {
        require(rowCount > 0) { "Il faut au moins une rangée : $rowCount." }
        require(rowHeightDp.isFinite() && rowHeightDp > 0f) { "La hauteur de rangée doit être strictement positive : $rowHeightDp." }
        return SUGGESTION_BAR_DP + rowCount * rowHeightDp + (rowCount - 1) * ROW_GAP_DP
    }
}
