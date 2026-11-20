package nextglyph.ime.view

/**
 * Marge sous la dernière rangée (spec §13) : le plus grand des encarts système du bas, plus la marge
 * réglée par l'utilisateur (§14).
 *
 * Plusieurs encarts sont lus car aucun ne suffit seul : en navigation par gestes avec les boutons
 * « changer de clavier » et « masquer le clavier », la barre dessinée par le système dans la fenêtre du
 * clavier est déclarée comme `captionBar()`, pas comme `navigationBars()` (constaté sur Galaxy S25 Ultra,
 * issue #20). Les encarts se superposent depuis le bord bas : on retient le plus grand, sans les additionner.
 */
object BottomInset {

    /**
     * @param systemInsetsPx encarts du bas fournis par le système, en pixels (négatifs traités comme 0).
     * @param userMarginPx marge réglée, en pixels (négative traitée comme 0).
     */
    fun resolve(systemInsetsPx: List<Int>, userMarginPx: Int): Int =
        systemInsetsPx.fold(0) { acc, inset -> maxOf(acc, inset) } + maxOf(userMarginPx, 0)
}
