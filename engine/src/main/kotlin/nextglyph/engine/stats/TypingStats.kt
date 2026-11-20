package nextglyph.engine.stats

/**
 * Mesures locales de saisie (issue #36, spec §15.4) : des compteurs, jamais de texte. Alimentées par la
 * session hors incognito, mot de passe et pavés ; affichées dans les réglages, remises à zéro à la demande.
 *
 * Le temps de frappe actif est la somme des intervalles entre deux frappes consécutives, chacun compté
 * seulement s'il est d'au plus [PAUSE_MS] (une pause plus longue n'est pas de la saisie).
 */
class TypingStats(private val clock: () -> Long, counters: Counters = Counters()) {

    /** Valeurs enregistrées. */
    data class Counters(
        /** Caractères tapés (lettres, signes, espaces). */
        val chars: Long = 0,
        /** Frappes, ⌫ compris. */
        val keys: Long = 0,
        val backspaces: Long = 0,
        /** Temps de frappe actif, en millisecondes. */
        val activeMs: Long = 0,
        /** Mots terminés (espace ou choix dans la barre). */
        val words: Long = 0,
        /** Validations par espace qui ont inséré un autre mot que le texte tapé (§5.5). */
        val autocommits: Long = 0,
        /** Validations automatiques annulées par ⌫ (§5.5). */
        val undone: Long = 0,
        /** Mots choisis dans la barre. */
        val picks: Long = 0,
    ) {
        init {
            require(listOf(chars, keys, backspaces, activeMs, words, autocommits, undone, picks).all { it >= 0 }) { "Compteur négatif." }
        }

        /** Mots par minute (5 caractères par mot), ou `null` sans temps de frappe. */
        val wordsPerMinute: Double? get() = if (activeMs <= 0) null else chars / WORD_CHARS / (activeMs / MS_PER_MINUTE)

        /** Part des frappes qui sont des ⌫, ou `null` sans frappe. */
        val correctionRate: Double? get() = ratio(backspaces, keys)

        /** Part des mots terminés grâce au clavier (validation qui corrige, ou choix dans la barre), ou `null`. */
        val acceptanceRate: Double? get() = ratio(maxOf(0, autocommits - undone) + picks, words)

        /** Part des validations automatiques annulées (objectif < 3 %, §15.2), ou `null`. */
        val undoRate: Double? get() = ratio(undone, autocommits)

        /** Forme enregistrée : `v1;chars;keys;backspaces;activeMs;words;autocommits;undone;picks`. */
        fun serialize(): String = listOf(VERSION, chars, keys, backspaces, activeMs, words, autocommits, undone, picks).joinToString(SEPARATOR)

        companion object {
            private const val VERSION = "v1"
            private const val SEPARATOR = ";"
            private const val FIELDS = 9
            private const val WORD_CHARS = 5.0
            private const val MS_PER_MINUTE = 60_000.0

            private fun ratio(n: Long, d: Long): Double? = if (d <= 0) null else n.toDouble() / d

            /** Lit une forme enregistrée ; tolérante : absente, d'une autre version ou abîmée, donne des compteurs nuls. */
            fun parse(stored: String?): Counters {
                val parts = stored?.split(SEPARATOR) ?: return Counters()
                if (parts.size != FIELDS || parts[0] != VERSION) return Counters()
                val n = parts.drop(1).map { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: return Counters() }
                return Counters(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7])
            }
        }
    }

    var counters: Counters = counters
        private set

    /** Les compteurs ont changé depuis le dernier [markSaved]. */
    var changed: Boolean = false
        private set

    private var lastKeyMs: Long? = null

    /** Frappe qui insère [chars] caractères (0 pour ⌫). */
    fun onKey(chars: Int = 1) {
        val now = clock()
        val gap = lastKeyMs?.let { now - it }?.takeIf { it in 0..PAUSE_MS } ?: 0
        lastKeyMs = now
        update { it.copy(chars = it.chars + chars, keys = it.keys + 1, activeMs = it.activeMs + gap) }
    }

    fun onBackspace() {
        onKey(0)
        update { it.copy(backspaces = it.backspaces + 1) }
    }

    /** Mot terminé par l'espace ; [autocommit] : la validation a inséré un autre mot. */
    fun onWord(autocommit: Boolean) = update { it.copy(words = it.words + 1, autocommits = it.autocommits + if (autocommit) 1 else 0) }

    fun onUndo() = update { it.copy(undone = it.undone + 1) }

    fun onPick() = update { it.copy(words = it.words + 1, picks = it.picks + 1) }

    fun reset() {
        lastKeyMs = null
        update { Counters() }
    }

    fun markSaved() {
        changed = false
    }

    private inline fun update(change: (Counters) -> Counters) {
        counters = change(counters)
        changed = true
    }

    companion object {
        /** Intervalle maximal entre deux frappes compté comme de la saisie. */
        const val PAUSE_MS: Long = 5_000L
    }
}
