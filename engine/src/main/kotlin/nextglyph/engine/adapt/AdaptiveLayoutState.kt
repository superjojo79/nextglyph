package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardLayout

/**
 * État de la disposition affichée : applique les nouvelles dispositions résolues en respectant
 * le gel pendant le toucher (spec §2.3), le report en saisie rapide et l'animation (§5.2).
 *
 * Règles :
 * - **gel** : tant qu'au moins un pointeur est posé, [frame] ne change pas, y compris en cours
 *   d'animation (l'animation reprend où elle en était au relâchement du dernier pointeur) ;
 * - **saisie rapide** : si l'intervalle entre deux frappes ([submit]) est inférieur à
 *   [AdaptationConfig.fastTypingMs], la nouvelle disposition n'est appliquée qu'après une pause
 *   de cette durée depuis la dernière frappe ; sinon elle l'est dès que plus aucun pointeur n'est posé ;
 * - **animation** : passage de l'image courante à la nouvelle disposition en
 *   [AdaptationConfig.animationMs], courbe [LayoutAnimation.easeOut] ; supprimée si l'échelle de
 *   durée des animations système vaut 0 (§13).
 *
 * Le décodage (§5.3) n'est pas concerné : il utilise toujours [frame], l'image réellement affichée.
 *
 * Usage côté vue : [pointerDown] / [pointerUp] sur les événements tactiles, [submit] après chaque
 * frappe validée, puis [advance] à chaque image tant qu'il renvoie `true` (ou au plus tard à
 * [nextUpdateTimeMs]). Toutes les dates sont en millisecondes sur une horloge monotone
 * (`SystemClock.uptimeMillis()`). Aucune allocation après la construction ; non thread-safe.
 */
class AdaptiveLayoutState(
    val layout: KeyboardLayout,
    val config: AdaptationConfig = AdaptationConfig(),
) {

    /** Image affichée : à dessiner, et à utiliser pour le décodage des taps. */
    val frame: AdaptedLayout = AdaptedLayout(layout)

    private val from = AdaptedLayout(layout)
    private val to = AdaptedLayout(layout)
    private val pending = AdaptedLayout(layout)

    /** Nombre de pointeurs actuellement posés. */
    var pointersDown: Int = 0
        private set

    /** Vrai si une disposition attend d'être appliquée (pointeur posé ou saisie rapide). */
    var hasPendingUpdate: Boolean = false
        private set

    /** Vrai pendant une animation. */
    var isAnimating: Boolean = false
        private set

    private var animationStart = 0L
    private var frozenAt = 0L
    private var lastKeystroke = NO_TIME
    private var applyAfter = NO_TIME

    /**
     * Date à partir de laquelle la disposition en attente peut être appliquée (si aucun pointeur
     * n'est posé), ou [Long.MIN_VALUE] sans disposition en attente.
     */
    val nextUpdateTimeMs: Long get() = if (hasPendingUpdate) applyAfter else NO_TIME

    /** Un pointeur se pose : la disposition est gelée. */
    fun pointerDown(nowMs: Long) {
        if (pointersDown == 0) frozenAt = nowMs
        pointersDown++
    }

    /**
     * Un pointeur se lève. Au dernier, l'animation éventuelle reprend où elle avait été gelée.
     * Sans pointeur posé (événement en double, `ACTION_CANCEL` déjà traité), l'appel est ignoré.
     */
    fun pointerUp(nowMs: Long) {
        if (pointersDown == 0) return
        pointersDown--
        if (pointersDown == 0) animationStart += maxOf(0L, nowMs - frozenAt)
    }

    /** Tous les pointeurs sont levés d'un coup (`ACTION_CANCEL`). */
    fun cancelPointers(nowMs: Long) {
        if (pointersDown == 0) return
        pointersDown = 1
        pointerUp(nowMs)
    }

    /**
     * Propose la disposition [target], calculée après la frappe validée à [keystrokeTimeMs].
     * Elle est copiée : l'appelant peut réutiliser son tampon.
     *
     * @throws IllegalArgumentException si [target] porte sur une autre disposition.
     */
    fun submit(target: AdaptedLayout, keystrokeTimeMs: Long) {
        pending.copyFrom(target)
        hasPendingUpdate = true
        val fast = lastKeystroke != NO_TIME && keystrokeTimeMs - lastKeystroke < config.fastTypingMs
        applyAfter = if (fast) keystrokeTimeMs + config.fastTypingMs else keystrokeTimeMs
        lastKeystroke = keystrokeTimeMs
    }

    /**
     * Fait avancer l'état à [nowMs] : applique la disposition en attente si c'est permis, puis
     * calcule l'image d'animation dans [frame].
     *
     * @param animatorDurationScale échelle de durée des animations système
     *   (`Settings.Global.ANIMATOR_DURATION_SCALE`) ; 0 supprime l'animation.
     * @return vrai s'il faut rappeler [advance] plus tard (animation en cours ou disposition en attente).
     * @throws IllegalArgumentException si [animatorDurationScale] est négatif ou n'est pas fini.
     */
    fun advance(nowMs: Long, animatorDurationScale: Float = 1f): Boolean {
        require(animatorDurationScale >= 0f && animatorDurationScale < Float.POSITIVE_INFINITY) {
            "L'échelle de durée doit être positive ou nulle : $animatorDurationScale."
        }
        if (pointersDown > 0) return isAnimating || hasPendingUpdate
        if (hasPendingUpdate && nowMs >= applyAfter) {
            hasPendingUpdate = false
            from.copyFrom(frame)
            to.copyFrom(pending)
            animationStart = nowMs
            isAnimating = true
        }
        if (isAnimating) {
            val duration = config.animationMs * animatorDurationScale
            val t = if (duration <= 0f) 1f else (nowMs - animationStart) / duration
            if (t >= 1f) {
                frame.copyFrom(to)
                isAnimating = false
            } else {
                LayoutAnimation.interpolate(from, to, t, frame, config.easeOutPower)
            }
        }
        return isAnimating || hasPendingUpdate
    }

    /** Remet immédiatement la disposition au repos, sans animation ni attente (changement de champ). */
    fun reset() {
        frame.setRest()
        hasPendingUpdate = false
        isAnimating = false
        pointersDown = 0
        lastKeystroke = NO_TIME
        applyAfter = NO_TIME
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
    }
}
