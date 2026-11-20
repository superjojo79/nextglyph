package nextglyph.engine.input

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRow
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.LayoutRole

/** Action d'édition de l'éditeur (`InputConnection.performContextMenuAction`). */
enum class ContextAction { SELECT_ALL, COPY, CUT, PASTE }

/**
 * Une touche du panneau Navigation (issue #37) : soit un événement clavier ([keyCode], avec [meta]
 * imposé), soit une action d'édition ([context]), soit la bascule du mode Sélection ([keyCode] et
 * [context] nuls).
 */
enum class NavKey(val label: String, val keyCode: Int? = null, val meta: Int = 0, val context: ContextAction? = null) {
    UP("↑", AndroidKeyCodes.KEYCODE_DPAD_UP),
    DOWN("↓", AndroidKeyCodes.KEYCODE_DPAD_DOWN),
    LEFT("←", AndroidKeyCodes.KEYCODE_DPAD_LEFT),
    RIGHT("→", AndroidKeyCodes.KEYCODE_DPAD_RIGHT),
    WORD_LEFT("Mot ←", AndroidKeyCodes.KEYCODE_DPAD_LEFT, PcModifier.CTRL.meta),
    WORD_RIGHT("Mot →", AndroidKeyCodes.KEYCODE_DPAD_RIGHT, PcModifier.CTRL.meta),
    HOME("Début", AndroidKeyCodes.KEYCODE_MOVE_HOME),
    END("Fin", AndroidKeyCodes.KEYCODE_MOVE_END),
    DELETE("Suppr", AndroidKeyCodes.KEYCODE_FORWARD_DEL),
    SELECT("Sélect."),
    SELECT_ALL("Tout", context = ContextAction.SELECT_ALL),
    COPY("Copier", context = ContextAction.COPY),
    CUT("Couper", context = ContextAction.CUT),
    PASTE("Coller", context = ContextAction.PASTE),
    ;

    /** Touche de disposition de largeur [width]. */
    fun key(width: Float): Key = Key(KeyKind.NAV, label, width)

    /** La touche déplace le curseur (et étend la sélection en mode Sélection). */
    val moves: Boolean get() = keyCode != null && this != DELETE

    companion object {
        private val byLabel = entries.associateBy { it.label }

        /** Touche de libellé [label], ou `null`. */
        fun of(label: String): NavKey? = byLabel[label]
    }
}

/** Ce que produit une touche du panneau Navigation. */
sealed interface NavCommand {
    data class Event(val event: PcKeyEvent) : NavCommand

    data class Context(val action: ContextAction) : NavCommand
}

/**
 * Mode Sélection du panneau Navigation (issue #37) : actif, les déplacements portent `Maj` et étendent la
 * sélection, comme `Maj` + flèches sur un clavier physique. Copier, couper, coller, tout sélectionner et
 * `Suppr` le désactivent.
 */
class NavSelection {

    var selecting: Boolean = false
        private set

    /** Appui sur [key] : la commande à exécuter, ou `null` pour la bascule du mode Sélection. */
    fun press(key: NavKey): NavCommand? {
        val keyCode = key.keyCode
        val context = key.context
        return when {
            key == NavKey.SELECT -> {
                selecting = !selecting
                null
            }
            context != null -> {
                selecting = false
                NavCommand.Context(context)
            }
            keyCode != null -> {
                val shift = if (key.moves && selecting) META_SHIFT else 0
                if (!key.moves) selecting = false
                NavCommand.Event(PcKeyEvent(keyCode, key.meta or shift))
            }
            else -> null
        }
    }

    /** Retour au mode normal (changement de champ, fermeture du panneau). */
    fun reset() {
        selecting = false
    }

    companion object {
        /** `KeyEvent.META_SHIFT_ON or META_SHIFT_LEFT_ON`. */
        const val META_SHIFT: Int = 0x01 or 0x40
    }
}

/** Disposition du panneau Navigation (issue #37). */
object NavLayout {
    /**
     * 3 rangées de 10 u, affichées à la place des lettres :
     *
     * ```
     * Mot ←(1,5)  ↑(2)  Mot →(1,5) | Tout(1,5) Copier(1,75) Couper(1,75)
     * ←(1,5)      ↓(2)  →(1,5)     | Sélect.(1,5) Coller(3,5)
     * ABC(1,5) Début(2) Fin(1,5)   | Suppr(2) ⌫(3)
     * ```
     */
    val LAYOUT: KeyboardLayout = KeyboardLayout(
        LayoutKind.QWERTY,
        listOf(
            KeyRow(
                listOf(
                    NavKey.WORD_LEFT.key(SIDE), NavKey.UP.key(MIDDLE), NavKey.WORD_RIGHT.key(SIDE),
                    NavKey.SELECT_ALL.key(SIDE), NavKey.COPY.key(EDIT), NavKey.CUT.key(EDIT),
                ),
            ),
            KeyRow(
                listOf(
                    NavKey.LEFT.key(SIDE), NavKey.DOWN.key(MIDDLE), NavKey.RIGHT.key(SIDE),
                    NavKey.SELECT.key(SIDE), NavKey.PASTE.key(PASTE_WIDTH),
                ),
            ),
            KeyRow(
                listOf(
                    Key(KeyKind.LETTERS, "ABC", SIDE), NavKey.HOME.key(MIDDLE), NavKey.END.key(SIDE),
                    NavKey.DELETE.key(MIDDLE), Key(KeyKind.BACKSPACE, "⌫", BACKSPACE_WIDTH),
                ),
            ),
        ),
        LayoutRole.NAV_KEYS,
    )

    private const val SIDE = 1.5f
    private const val MIDDLE = 2f
    private const val EDIT = 1.75f
    private const val PASTE_WIDTH = 3.5f
    private const val BACKSPACE_WIDTH = 3f
}
