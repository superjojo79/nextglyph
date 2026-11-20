package nextglyph.engine.input

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRow
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.LayoutRole

/**
 * Codes de touche et états méta d'Android (`android.view.KeyEvent`), recopiés pour garder le moteur sans
 * dépendance Android. Valeurs publiques et stables de l'API.
 */
object AndroidKeyCodes {
    const val KEYCODE_DPAD_UP: Int = 19
    const val KEYCODE_DPAD_DOWN: Int = 20
    const val KEYCODE_DPAD_LEFT: Int = 21
    const val KEYCODE_DPAD_RIGHT: Int = 22
    const val KEYCODE_A: Int = 29
    const val KEYCODE_0: Int = 7
    const val KEYCODE_ALT_LEFT: Int = 57
    const val KEYCODE_TAB: Int = 61
    const val KEYCODE_ENTER: Int = 66
    const val KEYCODE_DEL: Int = 67
    const val KEYCODE_PAGE_UP: Int = 92
    const val KEYCODE_PAGE_DOWN: Int = 93
    const val KEYCODE_ESCAPE: Int = 111
    const val KEYCODE_FORWARD_DEL: Int = 112
    const val KEYCODE_CTRL_LEFT: Int = 113
    const val KEYCODE_MOVE_HOME: Int = 122
    const val KEYCODE_MOVE_END: Int = 123
    const val KEYCODE_INSERT: Int = 124
    const val KEYCODE_F1: Int = 131

    const val META_ALT_ON: Int = 0x02
    const val META_ALT_LEFT_ON: Int = 0x10
    const val META_CTRL_ON: Int = 0x1000
    const val META_CTRL_LEFT_ON: Int = 0x2000
}

/** Modificateur collant du panneau des touches PC (issue #28). */
enum class PcModifier(val keyCode: Int, val meta: Int) {
    CTRL(AndroidKeyCodes.KEYCODE_CTRL_LEFT, AndroidKeyCodes.META_CTRL_ON or AndroidKeyCodes.META_CTRL_LEFT_ON),
    ALT(AndroidKeyCodes.KEYCODE_ALT_LEFT, AndroidKeyCodes.META_ALT_ON or AndroidKeyCodes.META_ALT_LEFT_ON),
}

/**
 * Une touche du panneau PC : libellé affiché, code de touche envoyé, modificateurs imposés ([meta]) ;
 * [modifier] non nul pour `Ctrl` et `Alt`, qui ne s'envoient pas mais s'activent (§5.10).
 */
enum class PcKey(val label: String, val keyCode: Int, val meta: Int = 0, val modifier: PcModifier? = null) {
    ESCAPE("Échap", AndroidKeyCodes.KEYCODE_ESCAPE),
    TAB("Tab", AndroidKeyCodes.KEYCODE_TAB),
    CTRL("Ctrl", AndroidKeyCodes.KEYCODE_CTRL_LEFT, modifier = PcModifier.CTRL),
    ALT("Alt", AndroidKeyCodes.KEYCODE_ALT_LEFT, modifier = PcModifier.ALT),
    HOME("Début", AndroidKeyCodes.KEYCODE_MOVE_HOME),
    END("Fin", AndroidKeyCodes.KEYCODE_MOVE_END),
    PAGE_UP("PgPr", AndroidKeyCodes.KEYCODE_PAGE_UP),
    PAGE_DOWN("PgSv", AndroidKeyCodes.KEYCODE_PAGE_DOWN),
    INSERT("Inser", AndroidKeyCodes.KEYCODE_INSERT),
    DELETE("Suppr", AndroidKeyCodes.KEYCODE_FORWARD_DEL),
    F1("F1", AndroidKeyCodes.KEYCODE_F1),
    F2("F2", AndroidKeyCodes.KEYCODE_F1 + 1),
    F3("F3", AndroidKeyCodes.KEYCODE_F1 + 2),
    F4("F4", AndroidKeyCodes.KEYCODE_F1 + 3),
    F5("F5", AndroidKeyCodes.KEYCODE_F1 + 4),
    F6("F6", AndroidKeyCodes.KEYCODE_F1 + 5),
    F7("F7", AndroidKeyCodes.KEYCODE_F1 + 6),
    F8("F8", AndroidKeyCodes.KEYCODE_F1 + 7),
    F9("F9", AndroidKeyCodes.KEYCODE_F1 + 8),
    F10("F10", AndroidKeyCodes.KEYCODE_F1 + 9),
    F11("F11", AndroidKeyCodes.KEYCODE_F1 + 10),
    F12("F12", AndroidKeyCodes.KEYCODE_F1 + 11),
    LEFT("←", AndroidKeyCodes.KEYCODE_DPAD_LEFT),
    UP("↑", AndroidKeyCodes.KEYCODE_DPAD_UP),
    DOWN("↓", AndroidKeyCodes.KEYCODE_DPAD_DOWN),
    RIGHT("→", AndroidKeyCodes.KEYCODE_DPAD_RIGHT),

    /** `Ctrl+Alt+Suppr` en une touche ; souvent intercepté par le client RDP, qui a alors son propre bouton. */
    CTRL_ALT_DELETE("C+A+Sup", AndroidKeyCodes.KEYCODE_FORWARD_DEL, PcModifier.CTRL.meta or PcModifier.ALT.meta),
    ;

    /** Touche de disposition de largeur [width]. */
    fun key(width: Float = 1f): Key = Key(KeyKind.PC, label, width)

    companion object {
        private val byLabel = entries.associateBy { it.label }

        /** Touche PC de libellé [label], ou `null`. */
        fun of(label: String): PcKey? = byLabel[label]
    }
}

/** Un événement clavier à envoyer : code de touche et état méta. */
data class PcKeyEvent(val keyCode: Int, val meta: Int)

/**
 * Modificateurs collants du panneau PC (issue #28) : un tap sur `Ctrl` ou `Alt` l'active pour la prochaine
 * touche, un deuxième tap rapide le verrouille, un tap sur un modificateur verrouillé le relâche. Pendant qu'un
 * modificateur est actif, les lettres et chiffres tapés sur les autres couches partent aussi en événements
 * clavier (`Ctrl+C`...).
 */
class PcModifiers {

    private val state = mutableMapOf<PcModifier, State>()
    private val lastTap = mutableMapOf<PcModifier, Long>()

    /** État d'un modificateur. */
    enum class State { OFF, ONCE, LOCKED }

    /** État de [modifier]. */
    fun state(modifier: PcModifier): State = state[modifier] ?: State.OFF

    /** Au moins un modificateur est actif. */
    val active: Boolean get() = state.values.any { it != State.OFF }

    /** Tap sur [modifier] à l'instant [nowMs] ; deux taps à moins de [doubleTapMs] verrouillent. */
    fun tap(modifier: PcModifier, nowMs: Long, doubleTapMs: Long) {
        val previous = lastTap[modifier]
        val quick = previous != null && nowMs - previous in 0..doubleTapMs
        state[modifier] = when (state(modifier)) {
            State.OFF -> State.ONCE
            State.ONCE -> if (quick) State.LOCKED else State.OFF
            State.LOCKED -> State.OFF
        }
        lastTap[modifier] = nowMs
    }

    /**
     * Événement de la touche [key] avec les modificateurs actifs, puis relâche ceux qui ne valaient que pour
     * une touche. Un modificateur ne produit pas d'événement : `null`, après l'avoir basculé par [tap].
     */
    fun press(key: PcKey, nowMs: Long, doubleTapMs: Long): PcKeyEvent? {
        key.modifier?.let {
            tap(it, nowMs, doubleTapMs)
            return null
        }
        return event(key.keyCode, key.meta)
    }

    /**
     * Événement d'un caractère tapé pendant qu'un modificateur est actif (`Ctrl+C`), ou `null` s'il n'y en a
     * pas ou si le caractère n'a pas de touche (seuls `a`–`z` et `0`–`9` en ont).
     */
    fun character(c: Char): PcKeyEvent? {
        if (!active) return null
        val code = keyCodeOf(c) ?: return null
        return event(code, 0)
    }

    /** Relâche tous les modificateurs (changement de champ). */
    fun reset() {
        state.clear()
        lastTap.clear()
    }

    private fun event(keyCode: Int, meta: Int): PcKeyEvent {
        var all = meta
        for ((modifier, s) in state) if (s != State.OFF) all = all or modifier.meta
        state.replaceAll { _, s -> if (s == State.ONCE) State.OFF else s }
        return PcKeyEvent(keyCode, all)
    }

    companion object {
        /** Code de touche de la lettre ou du chiffre [c] (insensible à la casse), ou `null`. */
        fun keyCodeOf(c: Char): Int? {
            val lower = c.lowercaseChar()
            return when (lower) {
                in 'a'..'z' -> AndroidKeyCodes.KEYCODE_A + (lower - 'a')
                in '0'..'9' -> AndroidKeyCodes.KEYCODE_0 + (lower - '0')
                else -> null
            }
        }
    }
}

/** Disposition du panneau PC (§5.10, issue #28). */
object PcLayout {
    /**
     * 3 rangées de 10 u, affichées à la place de la couche `?123` :
     *
     * ```
     * Échap Tab Ctrl Alt Début Fin PgPr PgSv Inser Suppr
     * F1 … F10
     * ABC(1,5) F11 F12 ← ↑ ↓ → C+A+Sup(1,5) ⌫
     * ```
     */
    val LAYOUT: KeyboardLayout = KeyboardLayout(
        LayoutKind.QWERTY,
        listOf(
            row(PcKey.ESCAPE, PcKey.TAB, PcKey.CTRL, PcKey.ALT, PcKey.HOME, PcKey.END, PcKey.PAGE_UP, PcKey.PAGE_DOWN, PcKey.INSERT, PcKey.DELETE),
            row(PcKey.F1, PcKey.F2, PcKey.F3, PcKey.F4, PcKey.F5, PcKey.F6, PcKey.F7, PcKey.F8, PcKey.F9, PcKey.F10),
            KeyRow(
                listOf(Key(KeyKind.LETTERS, "ABC", WIDE)) +
                    listOf(PcKey.F11, PcKey.F12, PcKey.LEFT, PcKey.UP, PcKey.DOWN, PcKey.RIGHT).map { it.key() } +
                    listOf(PcKey.CTRL_ALT_DELETE.key(WIDE), Key(KeyKind.BACKSPACE, "⌫", 1f)),
            ),
        ),
        LayoutRole.PC_KEYS,
    )

    private const val WIDE = 1.5f

    private fun row(vararg keys: PcKey) = KeyRow(keys.map { it.key() })
}
