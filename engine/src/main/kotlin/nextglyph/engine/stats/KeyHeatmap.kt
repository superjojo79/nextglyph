package nextglyph.engine.stats

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.learn.LearningPolicy
import nextglyph.engine.learn.SpatialModel
import nextglyph.engine.predict.PredictionSettings
import kotlin.math.floor
import kotlin.math.min

/**
 * Carte de frappe (issue #46, spec §15.5) : pour chacune des 30 places du modèle spatial (lettres `a`–`z`,
 * puis [SpatialModel.LEARNED_KEYS] : espace, entrée, ⌫, `?123`), des **compteurs seulement** : nombre de
 * frappes, nombre d'erreurs et grille de densité de [COLS] × [ROWS] cases couvrant les décalages
 * dx, dy ∈ [-0,5 ; 0,5] (fraction de la largeur de la touche et de la hauteur de rangée, comme `TapOffset`).
 *
 * **Vie privée.** Rien d'ordonné n'est jamais enregistré ni sérialisé : ni séquence, ni horodatage, ni
 * texte. [serialize] ne contient que les compteurs ; deux saisies des mêmes frappes dans un ordre différent
 * donnent exactement la même chaîne. Rien n'est compté quand [LearningPolicy.allowsTaps] est faux
 * (incognito, mot de passe, code PIN, pavés numériques).
 *
 * **Attribution des erreurs.** Une erreur est un ⌫ qui efface un caractère tapé juste avant : il compte
 * pour la touche de ce caractère. Pour cela, et seulement en mémoire vive, une petite pile des dernières
 * places tapées (au plus [MAX_RECENT]) est tenue : elle n'a **aucun accesseur**, n'est jamais sérialisée et est
 * vidée par [forgetRecent], [reset], [resetDensity], [resetErrors] et [parse]. Un caractère inséré sans
 * position connue ([onUntracked]) y laisse un marqueur vide pour que les ⌫ restent alignés ; ⌫ lui-même n'y
 * est pas empilé (il n'insère rien). L'annulation d'une validation automatique, l'effacement d'un mot,
 * un curseur déplacé ou un champ changé appellent [forgetRecent] : ce n'est pas une erreur de frappe.
 *
 * **Borne.** Quand les frappes (ou les erreurs) d'une place atteignent [MAX_TAPS], elles sont divisées par
 * deux (entier), erreurs et cases comprises : le stockage est borné et le récent pèse davantage.
 *
 * Non thread-safe : fil de l'interface, comme `TypingStats`.
 */
class KeyHeatmap {

    /** Une place lue : [letter] pour une lettre (sinon `null`), [kind] (`LETTER` pour une lettre). */
    class KeyHeat internal constructor(
        val letter: Char?,
        val kind: KeyKind,
        val taps: Int,
        val errors: Int,
        cells: IntArray,
    ) {
        /** Copie de la grille de densité, rangée par rangée ([COLS] × [ROWS] valeurs). */
        val cells: IntArray = cells.copyOf()

        /** Part de frappes suivies d'une erreur, ou `null` si moins de [MIN_TAPS_FOR_RATE] frappes, ou pour ⌫. */
        val errorRate: Double? =
            if (kind == KeyKind.BACKSPACE || taps < MIN_TAPS_FOR_RATE) null else min(1.0, errors.toDouble() / taps)
    }

    private val taps = IntArray(PLACES)
    private val errors = IntArray(PLACES)
    private val cells = Array(PLACES) { IntArray(CELLS) }

    /** Dernières places tapées (marqueur [UNTRACKED] pour un caractère sans position). Privée, jamais exposée. */
    private val recent = ArrayDeque<Int>()

    /** Les compteurs ont changé depuis le dernier [markSaved]. */
    var changed: Boolean = false
        private set

    /** Plus grande valeur de case, toutes places confondues (échelle commune du dessin). */
    val maxCell: Int get() = cells.maxOf { it.max() }

    /** Aucune frappe n'est comptée. */
    val isEmpty: Boolean get() = taps.all { it == 0 }

    /** Compte la frappe sur [letter] (`a`–`z`) décalée de ([dx], [dy]). Faux si rien n'est compté. */
    fun onTap(letter: Char, dx: Double, dy: Double, settings: PredictionSettings): Boolean =
        if (letter in 'a'..'z') record(letter - 'a', dx, dy, settings) else false

    /** Compte la frappe sur la touche [kind] ([SpatialModel.LEARNED_KEYS]). Faux si rien n'est compté. */
    fun onTap(kind: KeyKind, dx: Double, dy: Double, settings: PredictionSettings): Boolean {
        val index = SpatialModel.LEARNED_KEYS.indexOf(kind)
        return if (index < 0) false else record(LETTERS + index, dx, dy, settings)
    }

    /** Un caractère est inséré sans position connue (signe, glissé, menu, émoji…). */
    fun onUntracked() = push(UNTRACKED)

    /** Un ⌫ efface un caractère (pas une annulation) : l'erreur est comptée pour la touche tapée en dernier. */
    fun onErase() {
        val place = recent.removeLastOrNull() ?: return
        if (place == UNTRACKED) return
        errors[place]++
        changed = true
        halveIfFull(place)
    }

    /** Oublie les dernières frappes : plus aucune erreur ne leur sera attribuée. */
    fun forgetRecent() = recent.clear()

    /** Remet à zéro frappes et cases (« Effacer les données apprises »). */
    fun resetDensity() {
        taps.fill(0)
        cells.forEach { it.fill(0) }
        recent.clear()
        changed = true
    }

    /** Remet à zéro les erreurs (« Réinitialiser les mesures »). */
    fun resetErrors() {
        errors.fill(0)
        recent.clear()
        changed = true
    }

    /** Remet tout à zéro. */
    fun reset() {
        resetDensity()
        resetErrors()
    }

    fun markSaved() {
        changed = false
    }

    /** Les 30 places, dans l'ordre : lettres `a`–`z`, puis [SpatialModel.LEARNED_KEYS]. */
    fun keys(): List<KeyHeat> = List(PLACES) { i ->
        if (i < LETTERS) KeyHeat('a' + i, KeyKind.LETTER, taps[i], errors[i], cells[i])
        else KeyHeat(null, SpatialModel.LEARNED_KEYS[i - LETTERS], taps[i], errors[i], cells[i])
    }

    /** Forme enregistrée : `h1;` puis 30 blocs `frappes,erreurs,c0,…,c47` séparés par `;`. Compteurs seuls. */
    fun serialize(): String = buildString {
        append(VERSION)
        for (i in 0 until PLACES) {
            append(SEPARATOR).append(taps[i]).append(FIELD).append(errors[i])
            for (c in cells[i]) append(FIELD).append(c)
        }
    }

    private fun record(place: Int, dx: Double, dy: Double, settings: PredictionSettings): Boolean {
        if (!LearningPolicy.allowsTaps(settings)) return false
        if (!dx.isFinite() || !dy.isFinite() || dx !in -HALF..HALF || dy !in -HALF..HALF) return false
        val col = min(COLS - 1, floor((dx + HALF) * COLS).toInt())
        val row = min(ROWS - 1, floor((dy + HALF) * ROWS).toInt())
        taps[place]++
        cells[place][row * COLS + col]++
        changed = true
        if (place != BACKSPACE_PLACE && place != SYMBOLS_PLACE) push(place)
        halveIfFull(place)
        return true
    }

    private fun push(place: Int) {
        recent.addLast(place)
        while (recent.size > MAX_RECENT) recent.removeFirst()
    }

    private fun halveIfFull(place: Int) {
        if (taps[place] < MAX_TAPS && errors[place] < MAX_TAPS) return
        taps[place] /= 2
        errors[place] /= 2
        val c = cells[place]
        for (i in c.indices) c[i] /= 2
    }

    companion object {
        /** Colonnes de la grille de densité. */
        const val COLS: Int = 8

        /** Rangées de la grille de densité. */
        const val ROWS: Int = 6

        /** Frappes d'une place à partir desquelles ses compteurs sont divisés par deux. */
        const val MAX_TAPS: Int = 2000

        /** Frappes minimales pour afficher un taux d'erreur. */
        const val MIN_TAPS_FOR_RATE: Int = 20

        /** Taille de la pile mémoire des dernières frappes (jamais enregistrée). */
        const val MAX_RECENT: Int = 32

        private const val LETTERS = 26
        private val PLACES = LETTERS + SpatialModel.LEARNED_KEYS.size
        private val BACKSPACE_PLACE = LETTERS + SpatialModel.LEARNED_KEYS.indexOf(KeyKind.BACKSPACE)
        private val SYMBOLS_PLACE = LETTERS + SpatialModel.LEARNED_KEYS.indexOf(KeyKind.SYMBOLS)
        private const val CELLS = COLS * ROWS
        private const val UNTRACKED = -1
        private const val HALF = 0.5
        private const val VERSION = "h1"
        private const val SEPARATOR = ";"
        private const val FIELD = ","

        /** Lit une forme enregistrée ; tolérante : absente, d'une autre version, abîmée ou incohérente, donne une carte vide. */
        fun parse(stored: String?): KeyHeatmap {
            val map = KeyHeatmap()
            val parts = stored?.split(SEPARATOR) ?: return map
            if (parts.size != PLACES + 1 || parts[0] != VERSION) return map
            val blocks = ArrayList<IntArray>(PLACES)
            for (block in parts.drop(1)) {
                val fields = block.split(FIELD)
                if (fields.size != 2 + CELLS) return map
                val n = IntArray(fields.size) { fields[it].toIntOrNull()?.takeIf { v -> v >= 0 } ?: return map }
                if (n[0] >= MAX_TAPS || n[1] >= MAX_TAPS) return map
                if (n.drop(2).sumOf { it.toLong() } > n[0]) return map
                blocks += n
            }
            blocks.forEachIndexed { i, n ->
                map.taps[i] = n[0]
                map.errors[i] = n[1]
                n.copyInto(map.cells[i], 0, 2, n.size)
            }
            return map
        }
    }
}
