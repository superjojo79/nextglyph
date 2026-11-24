package nextglyph.engine.predict

import nextglyph.engine.ngram.NgramModel

/**
 * Une autre forme que le texte tapé, avec ce que le mot précédent en dit (issue #55).
 *
 * @property text forme proposée, avec la casse du texte tapé (`À`, `Ses`).
 * @property share part de cette forme parmi les formes de l'ensemble après le mot précédent.
 * @property total occurrences du mot précédent suivi d'une forme de l'ensemble.
 * @property auto l'ensemble peut être corrigé à la validation ([ConfusionSet.auto]).
 */
internal class Alternative(val text: String, val share: Double, val total: Int, val auto: Boolean)

/**
 * Désambiguïsation par le mot précédent (issue #55, §5.5) : pour un mot tapé qui appartient à un ensemble de formes
 * confondues (`a`/`à`, `ces`/`ses`), les autres formes classées par leur part après le mot précédent, d'après les
 * comptes du modèle de mot suivant ([NgramModel.confusionCounts]). Sans état : les modèles sont remplaçables à chaud.
 */
internal class Confusions(private val config: ConfusionConfig) {

    /** Modèles de mot suivant par code de langue ; vide : aucune désambiguïsation. */
    var models: Map<String, NgramModel> = emptyMap()

    /**
     * Autres formes que [raw] pour la langue [language], par part décroissante (puis ordre de l'ensemble), ou une liste
     * vide : mot tapé hors des ensembles, accent forcé (jamais modifié, §5.8) ou initiale majuscule (mot précédent connu : ce n'est pas un début de phrase), modèle ou mot précédent inconnu,
     * aucune occurrence.
     */
    fun alternatives(language: String, raw: String, previous: String): List<Alternative> {
        val model = models[language] ?: return emptyList()
        // Un accent tapé est voulu (§5.8) ; une majuscule en milieu de phrase aussi (`A` groupe sanguin, nom propre).
        if (DisplayForms.isForced(raw) || raw[0].isUpperCase()) return emptyList()
        for (set in config.sets[language].orEmpty()) {
            val typed = set.indexOf(raw)
            if (typed < 0) continue
            val counts = model.confusionCounts(previous, set.forms) ?: return emptyList()
            val total = counts.sum()
            if (total <= 0) return emptyList()
            return set.forms.indices
                .filter { it != typed && counts[it] > 0 }
                .sortedByDescending { counts[it] }
                .map { Alternative(DisplayForms.applyTypedCase(set.forms[it], raw), counts[it].toDouble() / total, total, set.auto) }
        }
        return emptyList()
    }

    companion object {
        /** Signes qui font d'un mot un élément technique (adresse, chemin, identifiant) lorsqu'ils le précèdent. */
        private const val TECHNICAL_SIGNS = "@/_"

        /**
         * Vrai si [raw] (mot en cours) ne doit jamais être remplacé automatiquement (§5.5) : sigle (au moins deux
         * lettres, toutes majuscules), ou mot collé à `@`, `/`, `_` ou à un point intérieur dans [textBeforeCursor].
         * Les mots mêlant lettres et chiffres ne sont jamais un mot en cours (`WordPrefix`).
         */
        fun isTechnical(textBeforeCursor: CharSequence?, raw: String): Boolean {
            if (isAcronym(raw)) return true
            if (textBeforeCursor == null || !textBeforeCursor.endsWith(raw)) return false
            val before = textBeforeCursor.length - raw.length - 1
            if (before < 0) return false
            val sign = textBeforeCursor[before]
            if (sign in TECHNICAL_SIGNS) return true
            return sign == '.' && before > 0 && textBeforeCursor[before - 1].isLetterOrDigit()
        }

        /** Vrai si [word] est un sigle : au moins deux lettres, toutes majuscules. */
        fun isAcronym(word: String): Boolean {
            val letters = word.filter(Char::isLetter)
            return letters.length >= 2 && letters.all(Char::isUpperCase)
        }
    }
}
