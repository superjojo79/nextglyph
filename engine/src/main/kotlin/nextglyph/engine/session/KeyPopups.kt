package nextglyph.engine.session

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import java.util.Locale

/** Effet d'une case de menu d'appui long. */
sealed interface PopupAction {
    /** Insère [text] comme une touche (lettre accentuée, symbole). */
    data class Text(val text: String) : PopupAction

    /** Verrouille la langue [language], ou revient au mode automatique si `null` (§10.6). */
    data class LockLanguage(val language: String?) : PopupAction

    /** Verrouille ou déverrouille la majuscule (`⇪`, §5.7). */
    data object CapsLock : PopupAction

    /** Échappatoire du §4 : passe sur la disposition classique si [enabled], revient à la disposition du champ sinon. */
    data class ClassicLayout(val enabled: Boolean) : PopupAction
}

/** Menu de l'appui long sur `?123`, selon la disposition affichée (§4, §5.6, §7.1). */
enum class SymbolsMenu {
    /** Ponctuation rapide seule. */
    QUICK_PUNCTUATION,

    /** Profil texte : ponctuation rapide, puis « Classique » (échappatoire du §4). */
    QUICK_PUNCTUATION_AND_CLASSIC,

    /** Disposition classique choisie par l'échappatoire : retour à la disposition du champ. */
    BACK_FROM_CLASSIC,

    /** Pas de menu (profil mot de passe : tous les symboles sont sur la disposition). */
    NONE,
}

/** Une case de menu : libellé affiché et effet. */
data class PopupItem(val label: String, val action: PopupAction)

/**
 * Menus d'appui long (spec §5.6, §5.8, §5.9, §10.6) et leur géométrie, sans Android :
 *
 * - voyelle ou `c` : variantes accentuées (`é è ê ë`, `ç`...), en majuscules si la majuscule est active ;
 * - `?123` : ponctuation rapide `- ( ) " ; @ #`, et l'échappatoire « Classique » du §4 selon [SymbolsMenu] ;
 * - espace : verrouiller une langue ou revenir au mode automatique ;
 * - certains symboles de la couche `?123` : symboles voisins (`-` → `_ – —`...), pour compléter
 *   les trois rangées ;
 * - `ABC` (couche `?123`) : majuscule verrouillée `⇪` (§5.7 ; la couche n'a pas de touche `⇪`).
 */
object KeyPopups {

    /** Variantes accentuées par lettre de base (minuscule), dans l'ordre d'affichage. */
    val ACCENTS: Map<Char, List<String>> = mapOf(
        'a' to listOf("à", "â", "ä", "æ"),
        'e' to listOf("é", "è", "ê", "ë"),
        'i' to listOf("î", "ï"),
        'o' to listOf("ô", "ö", "œ"),
        'u' to listOf("ù", "û", "ü"),
        'y' to listOf("ÿ"),
        'c' to listOf("ç"),
    )

    /** Ponctuation rapide de l'appui long sur `?123` (§5.6). */
    val QUICK_PUNCTUATION: List<String> = listOf("-", "(", ")", "\"", ";", "@", "#")

    /** Symboles voisins des touches de la couche `?123`. */
    val SYMBOLS: Map<String, List<String>> = mapOf(
        "-" to listOf("_", "–", "—"),
        "+" to listOf("=", "*", "×", "÷"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "/" to listOf("\\", "|"),
        "€" to listOf("$", "£", "¥"),
        "%" to listOf("‰", "^", "~"),
        "&" to listOf("§", "°"),
        "'" to listOf("\"", "«", "»", "’"),
        "," to listOf(";"),
        "." to listOf("…", "·"),
        "?" to listOf("¿"),
        "!" to listOf("¡"),
        ":" to listOf(";"),
    )

    /** Libellé de la majuscule verrouillée. */
    const val CAPS_LOCK_LABEL: String = "⇪"

    /** Libellé de la case qui passe sur la disposition classique (§4). */
    const val CLASSIC_LABEL: String = "Classique"

    /** Libellé de la case qui revient de la disposition classique à la disposition du champ (§4). */
    const val ADAPTIVE_LABEL: String = "Adaptative"

    /** Libellé de la case « mode automatique » du menu des langues. */
    const val AUTOMATIC_LABEL: String = "Auto"

    /**
     * Menu de l'appui long sur [key], ou liste vide s'il n'y en a pas.
     *
     * @param upper la prochaine lettre sera une majuscule (variantes en majuscules).
     * @param languages langues actives (menu de l'espace seulement s'il y en a plusieurs).
     * @param symbolsMenu contenu du menu de `?123`.
     */
    fun forKey(
        key: Key,
        upper: Boolean,
        languages: List<String>,
        symbolsMenu: SymbolsMenu = SymbolsMenu.QUICK_PUNCTUATION,
    ): List<PopupItem> = when (key.kind) {
        KeyKind.LETTER -> ACCENTS[key.label[0]].orEmpty().map { variant ->
            val text = if (upper) variant.uppercase(Locale.ROOT) else variant
            PopupItem(text, PopupAction.Text(text))
        }
        KeyKind.SYMBOLS -> symbolsMenu(symbolsMenu)
        KeyKind.SPACE -> if (languages.size < 2) {
            emptyList()
        } else {
            languages.map { PopupItem(it.uppercase(Locale.ROOT), PopupAction.LockLanguage(it)) } +
                PopupItem(AUTOMATIC_LABEL, PopupAction.LockLanguage(null))
        }
        KeyKind.CHARACTER -> SYMBOLS[key.label].orEmpty().map { PopupItem(it, PopupAction.Text(it)) }
        KeyKind.LETTERS -> listOf(PopupItem(CAPS_LOCK_LABEL, PopupAction.CapsLock))
        else -> emptyList()
    }

    private fun symbolsMenu(menu: SymbolsMenu): List<PopupItem> = when (menu) {
        SymbolsMenu.QUICK_PUNCTUATION -> quickPunctuation()
        SymbolsMenu.QUICK_PUNCTUATION_AND_CLASSIC -> quickPunctuation() + PopupItem(CLASSIC_LABEL, PopupAction.ClassicLayout(true))
        SymbolsMenu.BACK_FROM_CLASSIC -> listOf(PopupItem(ADAPTIVE_LABEL, PopupAction.ClassicLayout(false)))
        SymbolsMenu.NONE -> emptyList()
    }

    private fun quickPunctuation(): List<PopupItem> = QUICK_PUNCTUATION.map { PopupItem(it, PopupAction.Text(it)) }

    /**
     * Bord gauche d'un menu de [count] cases de largeur [itemWidth], centré sur [anchorX] et
     * maintenu dans `[0, viewWidth]` (aligné à gauche s'il est plus large que la vue).
     */
    fun popupLeft(anchorX: Float, itemWidth: Float, count: Int, viewWidth: Float): Float {
        val width = itemWidth * count
        return (anchorX - width / 2f).coerceAtMost(viewWidth - width).coerceAtLeast(0f)
    }

    /** Case visée à l'abscisse [x] (bornée aux cases existantes) ; −1 si le menu est vide. */
    fun indexAt(x: Float, left: Float, itemWidth: Float, count: Int): Int {
        if (count <= 0) return -1
        return ((x - left) / itemWidth).toInt().coerceIn(0, count - 1)
    }
}
