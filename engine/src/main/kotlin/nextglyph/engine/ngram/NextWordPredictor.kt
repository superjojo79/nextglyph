package nextglyph.engine.ngram

import nextglyph.engine.predict.LanguageState
import nextglyph.engine.predict.Suggestion
import java.util.Locale

/**
 * Réglages de [NextWordPredictor].
 *
 * @property groupMinProbability probabilité minimale (`P(w1 | contexte) · P(w2 | contexte, w1)`, mélangée
 *   entre langues) pour proposer un groupe de deux mots dans la troisième case.
 * @property candidates nombre de mots demandés à chaque modèle pour le classement.
 */
data class NextWordConfig(
    val groupMinProbability: Double = 0.12,
    val candidates: Int = 5,
) {
    init {
        require(groupMinProbability in 0.0..1.0) { "La probabilité minimale d'un groupe doit être dans [0, 1] ($groupMinProbability)." }
        require(candidates >= SLOTS) { "Au moins $SLOTS candidats sont nécessaires ($candidates)." }
    }

    companion object {
        /** Nombre de cases de la barre. */
        const val SLOTS: Int = 3
    }
}

/**
 * Prédiction du mot suivant (issue #51) : au plus trois propositions après le dernier mot saisi,
 * mélangées entre les langues actives selon leur poids (`score = Σ π_L · P_L(mot | contexte)`, §10.6).
 *
 * Aucune donnée de l'utilisateur n'est retenue : le texte est lu à chaque appel. Sans modèle pour
 * les langues actives, ou sans contexte exploitable, la liste est vide.
 *
 * @param models modèles par code de langue (`fr`, `en`), comme les lexiques.
 */
class NextWordPredictor(
    models: Map<String, NgramModel>,
    private val config: NextWordConfig = NextWordConfig(),
) {
    private val models: Map<String, NgramModel> = LinkedHashMap(models)

    /** Langues pour lesquelles un modèle est disponible. */
    val languages: Set<String> get() = models.keys

    /** Vrai si au moins un modèle est disponible. */
    val isAvailable: Boolean get() = models.isNotEmpty()

    /**
     * Propositions classées (la première au centre de la barre) après [textBefore].
     *
     * - La troisième est le meilleur groupe de deux mots s'il atteint [NextWordConfig.groupMinProbability],
     *   sinon le troisième meilleur mot. Un mot qui finit par une apostrophe (`j'`) est collé au suivant.
     * - [capitalize] met la première lettre en majuscule (début de phrase) ; `I` reste `I`.
     * - Le texte lu est borné à [NextWordContext.LOOKBACK] caractères.
     */
    fun suggest(textBefore: CharSequence?, language: LanguageState, capitalize: Boolean): List<Suggestion> {
        val scores = HashMap<String, Double>()
        val groups = HashMap<String, Double>()
        for ((tag, model) in models) {
            val weight = language.weight(tag)
            if (weight <= 0.0) continue
            val context = NextWordContext.read(textBefore, tag == FRENCH || tag.startsWith("$FRENCH-")) ?: continue
            val words = model.next(context, config.candidates)
            for (word in words) scores.merge(word.text, weight * word.probability, Double::plus)
            for (first in words.take(NextWordConfig.SLOTS)) {
                for (second in model.next(context + first.text, NextWordConfig.SLOTS)) {
                    groups.merge(join(first.text, second.text), weight * first.probability * second.probability, Double::plus)
                }
            }
        }
        val singles = scores.entries.sortedWith(RANKING).take(NextWordConfig.SLOTS).map { it.key }
        val group = groups.entries.filter { it.value >= config.groupMinProbability && it.key !in singles }.minWithOrNull(RANKING)
        val ranked = if (group != null && singles.size >= 2) singles.take(2) + group.key else singles
        return ranked.map { Suggestion(if (capitalize) capitalized(it) else it) }
    }

    private fun join(first: String, second: String): String = if (first.endsWith('\'')) first + second else "$first $second"

    private fun capitalized(text: String): String = text.replaceFirstChar { it.titlecase(Locale.ROOT) }

    private companion object {
        const val FRENCH = "fr"

        val RANKING: Comparator<Map.Entry<String, Double>> =
            compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
    }
}
