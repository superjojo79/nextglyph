package nextglyph.engine.predict

import nextglyph.engine.lexicon.LexiconEntry
import nextglyph.engine.lexicon.LexiconSet
import java.util.Locale

/**
 * Forme insérée à la validation (spec §5.5, §5.7, §5.8, §10.6) : restitution des accents et de la
 * casse à partir des formes d'affichage des lexiques actifs.
 *
 * **Accents.** Dans chaque lexique actif qui contient la clé, l'orthographe (forme ramenée en
 * minuscules) **dominante** est celle qui représente au moins [dominance] (95 %) des occurrences
 * de la clé dans ce lexique ; les variantes rares des sources (`écolé`, 3 occurrences contre 20 593
 * pour `école`) ne rendent donc pas la restitution ambiguë. Les formes à casse implausible (`cA`) et
 * les sigles (`CA`, au moins deux lettres toutes majuscules) ne comptent pas dans cette masse (`ca`
 * → `ça`), sauf si un lexique n'a que de telles formes pour la clé.
 *
 * Les lexiques sont ensuite **pondérés** : chaque lexique `L` qui contient la clé et a une
 * orthographe dominante `s_L` apporte la masse `π_L · f_L(clé) / masse_L` (poids de la langue `π_L`,
 * fréquence de la clé rapportée à la masse du lexique) à `s_L`. L'orthographe qui porte au moins
 * [dominance] de la masse totale est restituée (`ecole` → `école`, `cafe` → `café` en français comme
 * en anglais) ; sinon la restitution est ambiguë et le texte tapé est inséré tel quel : `a`/`à`,
 * `ou`/`où` (aucune orthographe dominante dans un lexique, ce qui est toujours ambigu), ou
 * `resume`/`résumé` tant que le mot anglais sans accent pèse plus de 5 % de la masse (§10.6). Avec un
 * seul lexique, ou des lexiques d'accord, la pondération ne change rien. Pour un candidat (cas 2 du
 * §5.5) ambigu, la forme sans accent est préférée si elle existe, sinon la plus fréquente. Un mot tapé
 * avec un caractère accentué (forme forcée par appui long, §5.8) n'est jamais modifié.
 *
 * **Casse.** Parmi les formes de l'orthographe retenue, celles du lexique de la langue de la phrase
 * (§10.6, à défaut celles de tous les lexiques) : la casse de la plus fréquente l'emporte (`i` →
 * `I` et `im` → `I'm` dans une phrase anglaise, `monday` → `Monday`, mais `paris` reste en
 * minuscules car le nom commun est plus fréquent, §6.3). Puis la casse tapée s'applique : une
 * initiale majuscule (tapée ou automatique) est conservée, un mot tapé tout en majuscules le reste.
 */
internal class DisplayForms(private val dominance: Double) {

    /** Forme insérée pour un mot tapé [raw] de clé [key], mot d'au moins un lexique (cas 1). */
    fun forTypedWord(raw: String, key: String, sets: List<LexiconSet>, sentenceSet: LexiconSet?, weights: List<Double> = equalWeights(sets)): String {
        if (isForced(raw)) return raw
        val spelling = agreedSpelling(key, sets, weights) ?: return raw
        val inserted = applyTypedCase(casedForm(spelling, key, forms(key, sets), sentenceSet), raw)
        // Une apostrophe typographique tapée (’) n'est pas une différence à corriger.
        return if (straight(inserted) == straight(raw)) raw else inserted
    }

    /** Forme insérée pour le candidat [key] (cas 2), le mot tapé étant [raw]. */
    fun forCandidate(raw: String, key: String, sets: List<LexiconSet>, sentenceSet: LexiconSet?, weights: List<Double> = equalWeights(sets)): String {
        val forms = forms(key, sets)
        val spelling = agreedSpelling(key, sets, weights) ?: when {
            forms.isEmpty() || forms.any { lower(it.form) == key } -> key
            else -> lower(forms[0].form)
        }
        return applyTypedCase(casedForm(spelling, key, forms, sentenceSet), raw)
    }

    /**
     * Orthographe qui porte au moins [dominance] de la masse pondérée des lexiques [sets] (poids [weights],
     * alignés sur [sets]) qui contiennent [key], ou `null` si elle est ambiguë.
     */
    private fun agreedSpelling(key: String, sets: List<LexiconSet>, weights: List<Double>): String? {
        val bySpelling = HashMap<String, Double>()
        var total = 0.0
        for ((index, set) in sets.withIndex()) {
            val all = set.displayForms(key)
            if (all.isEmpty()) continue
            val counted = all.filter { isPlausibleCase(it.form) && !isAcronym(it.form) }.ifEmpty { all }
            val perSpelling = HashMap<String, Double>()
            for (entry in counted) perSpelling.merge(lower(entry.form), entry.frequency.toDouble(), Double::plus)
            val mass = perSpelling.values.sum()
            val (spelling, top) = perSpelling.entries.maxBy { it.value }.toPair()
            if (top < dominance * mass) return null
            val share = weights[index] * mass / set.totalMass
            bySpelling.merge(spelling, share, Double::plus)
            total += share
        }
        if (bySpelling.isEmpty()) return null
        val (spelling, share) = bySpelling.entries.maxBy { it.value }.toPair()
        return if (share >= dominance * total) spelling else null
    }

    private fun forms(key: String, sets: List<LexiconSet>): List<LexiconEntry> {
        val merged = LinkedHashMap<String, Long>()
        for (set in sets) for (entry in set.displayForms(key)) merged.merge(entry.form, entry.frequency, Long::plus)
        return merged.map { LexiconEntry(it.key, key, it.value) }.sortedWith(compareByDescending<LexiconEntry> { it.frequency }.thenBy { it.form })
    }

    private fun casedForm(spelling: String, key: String, all: List<LexiconEntry>, sentenceSet: LexiconSet?): String {
        val preferred = sentenceSet?.displayForms(key)?.filter { lower(it.form) == spelling }.orEmpty()
        val pool = preferred.ifEmpty { all.filter { lower(it.form) == spelling } }
        return pool.firstOrNull()?.form?.takeIf(::isPlausibleCase) ?: spelling
    }

    companion object {
        /** Vrai si [raw] contient une lettre hors de `a`–`z` (accent ou ligature tapés volontairement). */
        fun isForced(raw: String): Boolean = raw.any { it.isLetter() && it.lowercaseChar() !in 'a'..'z' }

        /**
         * Vrai si la casse de [form] peut être restituée : minuscules, ou initiale majuscule (`I'm`,
         * `Monday`, `McDonald`). Les formes à majuscule interne seule (`daV`, `nA`) sont du bruit des
         * sources : la forme en minuscules leur est préférée.
         */
        fun isPlausibleCase(form: String): Boolean = form.isEmpty() || form[0].isUpperCase() || form == lower(form)

        /** Sigle : au moins deux lettres, toutes majuscules (`CA`, `USA`). */
        private fun isAcronym(form: String): Boolean {
            val letters = form.filter(Char::isLetter)
            return letters.length >= 2 && letters.all(Char::isUpperCase)
        }

        private fun equalWeights(sets: List<LexiconSet>): List<Double> = List(sets.size) { 1.0 }

        /** Applique à [form] la casse tapée dans [raw] (initiale, ou tout en majuscules). */
        fun applyTypedCase(form: String, raw: String): String {
            val letters = raw.filter(Char::isLetter)
            return when {
                letters.length >= 2 && letters.all(Char::isUpperCase) -> form.uppercase(Locale.ROOT)
                letters.isNotEmpty() && letters[0].isUpperCase() -> form.replaceFirstChar { it.titlecase(Locale.ROOT) }
                else -> form
            }
        }

        private const val APOSTROPHE = '\''

        private fun straight(text: String): String = text.replace('’', APOSTROPHE).replace('ʼ', APOSTROPHE)

        private fun lower(form: String): String = form.lowercase(Locale.ROOT)
    }
}
