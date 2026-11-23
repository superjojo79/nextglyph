package nextglyph.ime.view

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * Nœuds d'accessibilité virtuels du clavier (spec §13) : chaque touche et chaque case de la barre est un
 * nœud que TalkBack explore, annonce et active (double appui, ou « relâcher pour saisir »). Fondé sur
 * l'API du cadre Android (`AccessibilityNodeProvider`), sans dépendance.
 */
internal class KeyboardAccessibility(private val view: View, private val host: Host) : AccessibilityNodeProvider() {

    /** Ce que la vue expose : des nœuds numérotés de 0 à [count] − 1. */
    interface Host {
        val count: Int

        /** Rectangle du nœud [id] dans la vue ; faux s'il n'existe pas. */
        fun bounds(id: Int, out: Rect): Boolean

        fun description(id: Int): String?

        /** État (`stateDescription`), par exemple « verrouillée » pour `⇧`. */
        fun state(id: Int): String?

        /** Nœud sous le point ([x], [y]), ou [View.NO_ID]. */
        fun nodeAt(x: Float, y: Float): Int

        fun click(id: Int): Boolean

        /** Faux pour une vue en lecture seule (carte de frappe) : les nœuds ne se déclarent pas activables. */
        val clickable: Boolean get() = true
    }

    private var focused = View.NO_ID
    private var hovered = View.NO_ID
    private val rect = Rect()
    private val location = IntArray(2)

    /** Nœud qui a le focus d'accessibilité, ou [View.NO_ID]. */
    val focusedId: Int get() = focused

    @Suppress("DEPRECATION") // obtain() : seule API disponible dès l'API 26.
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(view)
            view.onInitializeAccessibilityNodeInfo(info)
            for (id in 0 until host.count) if (host.bounds(id, rect)) info.addChild(view, id)
            return info
        }
        if (!host.bounds(virtualViewId, rect)) return null
        val info = AccessibilityNodeInfo.obtain(view, virtualViewId)
        info.packageName = view.context.packageName
        info.className = KEY_CLASS
        info.setParent(view)
        info.setBoundsInParent(rect)
        view.getLocationOnScreen(location)
        rect.offset(location[0], location[1])
        info.setBoundsInScreen(rect)
        info.contentDescription = host.description(virtualViewId)
        val state = host.state(virtualViewId)
        if (state != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.stateDescription = state else info.contentDescription = "${info.contentDescription}, $state"
        }
        info.isEnabled = true
        info.isVisibleToUser = true
        info.isClickable = host.clickable
        info.isFocusable = true
        if (host.clickable) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        if (focused == virtualViewId) {
            info.isAccessibilityFocused = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_VIEW_ID) return view.performAccessibilityAction(action, arguments)
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focused == virtualViewId) return false
                if (focused != View.NO_ID) send(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                focused = virtualViewId
                view.invalidate()
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focused != virtualViewId) return false
                focused = View.NO_ID
                view.invalidate()
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                val done = host.click(virtualViewId)
                if (done) send(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                done
            }
            else -> false
        }
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? =
        if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focused != View.NO_ID) createAccessibilityNodeInfo(focused) else null

    /** Exploration au toucher : annonce le nœud survolé. Vrai si l'événement est traité. */
    fun onHover(event: MotionEvent): Boolean {
        val id = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> host.nodeAt(event.x, event.y)
            MotionEvent.ACTION_HOVER_EXIT -> View.NO_ID
            else -> return false
        }
        if (id != hovered) {
            val previous = hovered
            hovered = id
            if (id != View.NO_ID) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (previous != View.NO_ID) send(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        }
        return true
    }

    /** Les nœuds ont changé (disposition, barre, majuscule) : TalkBack relit l'arborescence. */
    fun invalidateNodes() {
        if (focused >= host.count) focused = View.NO_ID
        send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** Annonce [text] (§13 : mot inséré par la validation par espace). */
    @Suppress("DEPRECATION") // announceForAccessibility : l'annonce reste le moyen prévu pour un IME.
    fun announce(text: String) = view.announceForAccessibility(text)

    @Suppress("DEPRECATION") // AccessibilityEvent.obtain : le constructeur n'existe qu'à partir de l'API 30.
    private fun send(id: Int, type: Int): Boolean {
        val parent = view.parent ?: return false
        // Sans service d'accessibilité actif, AccessibilityManager lève une exception à l'envoi.
        val manager = view.context.getSystemService(AccessibilityManager::class.java)
        if (!view.isAttachedToWindow || manager == null || !manager.isEnabled) return false
        val event = AccessibilityEvent.obtain(type)
        event.packageName = view.context.packageName
        if (id == HOST_VIEW_ID) {
            view.onInitializeAccessibilityEvent(event)
        } else {
            event.className = KEY_CLASS
            event.setSource(view, id)
            event.contentDescription = host.description(id)
            event.isEnabled = true
        }
        return parent.requestSendAccessibilityEvent(view, event)
    }

    private companion object {
        /** Classe annoncée : un bouton (« bouton » n'est pas lu par TalkBack dans un clavier). */
        const val KEY_CLASS = "android.widget.Button"
    }
}
