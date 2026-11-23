package nextglyph.engine.predict

import nextglyph.engine.adapt.TapSpatialModel

/**
 * Frappe d'une lettre du mot en cours : lettre décodée (`a`–`z`) et position par rapport au centre de
 * sa touche au repos, en fraction de sa largeur au repos ([dx]) et de la hauteur de rangée ([dy]),
 * comme `TapOffset`.
 */
data class TypedTap(val letter: Char, val dx: Double, val dy: Double)

/**
 * Frappes du mot en cours (une entrée par caractère tapé, `null` si la position est inconnue :
 * apostrophe, trait d'union, lettre sans position) et modèle spatial appris. En mémoire seulement :
 * jamais enregistré ni transmis ailleurs qu'à [Predictor.predict].
 */
class WordTaps(val taps: List<TypedTap?>, val spatial: TapSpatialModel = TapSpatialModel.NONE) {

    /**
     * Les `key.length` dernières entrées si elles s'alignent sur [key] (au moins autant d'entrées que de
     * caractères, chaque frappe connue porte la lettre `key[i]`) et si au moins une est connue ; sinon `null`.
     */
    fun alignedTo(key: String): List<TypedTap?>? {
        if (key.isEmpty() || taps.size < key.length) return null
        val aligned = taps.subList(taps.size - key.length, taps.size)
        var known = false
        for (i in key.indices) {
            val tap = aligned[i] ?: continue
            if (tap.letter != key[i]) return null
            known = true
        }
        return if (known) aligned else null
    }
}
