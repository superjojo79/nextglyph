package nextglyph.engine.stats

import nextglyph.engine.predict.Prudence

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
        /** Frappes, ⌫ compris (celui qui annule une validation automatique aussi). */
        val keys: Long = 0,
        /** ⌫ qui effacent ; une annulation de validation automatique n'est pas une erreur de frappe (#46). */
        val backspaces: Long = 0,
        /** Temps de frappe actif, en millisecondes. */
        val activeMs: Long = 0,
        /** Mots terminés (espace ou choix dans la barre). */
        val words: Long = 0,
        /** Validations par espace qui ont inséré un autre mot que le texte tapé (§5.5) : corrections et restitutions. */
        val autocommits: Long = 0,
        /** Validations automatiques annulées par ⌫ (§5.5). */
        val undone: Long = 0,
        /** Mots choisis dans la barre. */
        val picks: Long = 0,
        /** Accents et majuscules restitués (cas 1 du §5.5, texte inséré différent du texte tapé). */
        val restored: Long = 0,
        /** Restitutions annulées par ⌫. */
        val restoredUndone: Long = 0,
        /** Corrections (cas 2 du §5.5) en prudence prudente. */
        val cautiousCorrections: Long = 0,
        val cautiousUndone: Long = 0,
        /** Corrections en prudence normale. */
        val normalCorrections: Long = 0,
        val normalUndone: Long = 0,
        /** Corrections en prudence audacieuse. */
        val boldCorrections: Long = 0,
        val boldUndone: Long = 0,
    ) {
        init {
            require(values().all { it >= 0 }) { "Compteur négatif." }
        }

        private fun values(): List<Long> = listOf(
            chars, keys, backspaces, activeMs, words, autocommits, undone, picks,
            restored, restoredUndone, cautiousCorrections, cautiousUndone, normalCorrections, normalUndone, boldCorrections, boldUndone,
        )

        /** Mots par minute (5 caractères par mot), ou `null` sans temps de frappe. */
        val wordsPerMinute: Double? get() = if (activeMs <= 0) null else chars / WORD_CHARS / (activeMs / MS_PER_MINUTE)

        /** Part des frappes qui sont des ⌫ d'effacement, ou `null` sans frappe. */
        val correctionRate: Double? get() = ratio(backspaces, keys)

        /** Part des mots terminés grâce au clavier (validation qui corrige, ou choix dans la barre), ou `null`. */
        val acceptanceRate: Double? get() = ratio(maxOf(0, autocommits - undone) + picks, words)

        /** Part des validations automatiques annulées (objectif < 3 %, §15.2), ou `null`. */
        val undoRate: Double? get() = ratio(undone, autocommits)

        /** Corrections (cas 2) en prudence [prudence]. */
        fun corrections(prudence: Prudence): Long = when (prudence) {
            Prudence.CAUTIOUS -> cautiousCorrections
            Prudence.NORMAL -> normalCorrections
            Prudence.BOLD -> boldCorrections
        }

        /** Corrections annulées en prudence [prudence]. */
        fun correctionsUndone(prudence: Prudence): Long = when (prudence) {
            Prudence.CAUTIOUS -> cautiousUndone
            Prudence.NORMAL -> normalUndone
            Prudence.BOLD -> boldUndone
        }

        /** Part des corrections annulées en prudence [prudence], ou `null` sans correction. */
        fun correctionUndoRate(prudence: Prudence): Double? = ratio(correctionsUndone(prudence), corrections(prudence))

        /** Corrections, toutes prudences. */
        val correctionsTotal: Long get() = cautiousCorrections + normalCorrections + boldCorrections

        /** Corrections annulées, toutes prudences. */
        val correctionsUndoneTotal: Long get() = cautiousUndone + normalUndone + boldUndone

        /** Part des corrections annulées, ou `null` sans correction. */
        val correctionUndoRate: Double? get() = ratio(correctionsUndoneTotal, correctionsTotal)

        /** Part des restitutions d'accent ou de majuscule annulées, ou `null`. */
        val restoredUndoRate: Double? get() = ratio(restoredUndone, restored)

        /**
         * Forme enregistrée : `v2;chars;keys;backspaces;activeMs;words;autocommits;undone;picks;restored;restoredUndone;`
         * `cautiousCorrections;cautiousUndone;normalCorrections;normalUndone;boldCorrections;boldUndone`.
         */
        fun serialize(): String = (listOf(VERSION) + values()).joinToString(SEPARATOR)

        companion object {
            private const val VERSION = "v2"
            private const val VERSION_1 = "v1"
            private const val SEPARATOR = ";"
            private const val FIELDS_V1 = 8
            private const val FIELDS = 16
            private const val WORD_CHARS = 5.0
            private const val MS_PER_MINUTE = 60_000.0

            private fun ratio(n: Long, d: Long): Double? = if (d <= 0) null else n.toDouble() / d

            /**
             * Lit une forme enregistrée ; tolérante : absente, d'une autre version ou abîmée, donne des compteurs nuls.
             * La version 1 (8 compteurs) est lue : les compteurs ajoutés par la version 2 sont à zéro.
             */
            fun parse(stored: String?): Counters {
                val parts = stored?.split(SEPARATOR) ?: return Counters()
                val expected = when (parts[0]) {
                    VERSION -> FIELDS
                    VERSION_1 -> FIELDS_V1
                    else -> return Counters()
                }
                if (parts.size != expected + 1) return Counters()
                val n = parts.drop(1).map { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: return Counters() }.toMutableList()
                while (n.size < FIELDS) n.add(0)
                return Counters(
                    n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7],
                    n[8], n[9], n[10], n[11], n[12], n[13], n[14], n[15],
                )
            }
        }
    }

    /** Nature d'une validation par espace qui insère autre chose que le texte tapé. */
    enum class AutoKind {
        /** Accent ou majuscule restitué (cas 1 du §5.5). */
        RESTORATION,

        /** Correction d'un texte qui n'est pas un mot (cas 2 du §5.5). */
        CORRECTION,
    }

    var counters: Counters = counters
        private set

    /** Les compteurs ont changé depuis le dernier [markSaved]. */
    var changed: Boolean = false
        private set

    private var lastKeyMs: Long? = null

    // Dernière validation automatique, gardée en mémoire seulement (jamais enregistrée) pour imputer son annulation.
    private var lastAuto: Pair<AutoKind, Prudence>? = null

    /** Frappe qui insère [chars] caractères (0 pour ⌫). */
    fun onKey(chars: Int = 1) {
        val now = clock()
        val gap = lastKeyMs?.let { now - it }?.takeIf { it in 0..PAUSE_MS } ?: 0
        lastKeyMs = now
        lastAuto = null
        update { it.copy(chars = it.chars + chars, keys = it.keys + 1, activeMs = it.activeMs + gap) }
    }

    /** ⌫ qui efface (une annulation de validation automatique passe par [onUndo]). */
    fun onBackspace() {
        onKey(0)
        update { it.copy(backspaces = it.backspaces + 1) }
    }

    /**
     * Mot terminé par l'espace ; [auto] : nature de la validation si elle a inséré autre chose que le texte tapé
     * (`null` sinon), [prudence] : réglage en vigueur (compte les corrections par prudence).
     */
    fun onWord(auto: AutoKind?, prudence: Prudence = Prudence.NORMAL) {
        lastAuto = auto?.let { it to prudence }
        update { c ->
            val base = c.copy(words = c.words + 1, autocommits = c.autocommits + if (auto != null) 1 else 0)
            when (auto) {
                null -> base
                AutoKind.RESTORATION -> base.copy(restored = base.restored + 1)
                AutoKind.CORRECTION -> when (prudence) {
                    Prudence.CAUTIOUS -> base.copy(cautiousCorrections = base.cautiousCorrections + 1)
                    Prudence.NORMAL -> base.copy(normalCorrections = base.normalCorrections + 1)
                    Prudence.BOLD -> base.copy(boldCorrections = base.boldCorrections + 1)
                }
            }
        }
    }

    /**
     * ⌫ qui annule la dernière validation automatique : compte comme frappe, pas comme ⌫ d'effacement (#46) ;
     * l'annulation est imputée à la catégorie de la dernière validation si elle est encore connue.
     */
    fun onUndo() {
        val last = lastAuto
        onKey(0)
        update { c ->
            val base = c.copy(undone = c.undone + 1)
            when (last?.first) {
                null -> base
                AutoKind.RESTORATION -> base.copy(restoredUndone = base.restoredUndone + 1)
                AutoKind.CORRECTION -> when (last.second) {
                    Prudence.CAUTIOUS -> base.copy(cautiousUndone = base.cautiousUndone + 1)
                    Prudence.NORMAL -> base.copy(normalUndone = base.normalUndone + 1)
                    Prudence.BOLD -> base.copy(boldUndone = base.boldUndone + 1)
                }
            }
        }
    }

    fun onPick() {
        lastAuto = null
        update { it.copy(words = it.words + 1, picks = it.picks + 1) }
    }

    fun reset() {
        lastKeyMs = null
        lastAuto = null
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
