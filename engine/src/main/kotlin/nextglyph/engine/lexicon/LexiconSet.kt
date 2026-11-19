package nextglyph.engine.lexicon

/**
 * Plusieurs lexiques d'une même langue lus comme un seul (spec §10.1, §10.5, §6.3).
 *
 * Usages : un lexique principal découpé en fichiers sous licences différentes (Lexique sous
 * CC BY-SA, Dicollecte sous MPL), auquel s'ajouteront le dictionnaire personnel et les contacts.
 * Aucune donnée n'est copiée : chaque requête interroge les membres puis fusionne les réponses.
 *
 * **Sémantique de fusion : les fréquences s'additionnent.** Une même forme présente dans
 * plusieurs membres a pour fréquence la somme de ses fréquences (saturée à `Long.MAX_VALUE`), et
 * la masse d'un préfixe est la somme des masses. C'est la seule règle cohérente avec
 * [distribution] : les masses d'un nœud sont des sommes de fréquences, si bien que `P(c | p)`,
 * `P(fin | p)`, [completions] et [displayForms] décrivent tous le même lexique « union ». Les
 * membres doivent donc partager une même échelle de fréquences (occurrences dans un même corpus),
 * et une source ne doit pas recopier les comptes d'une autre : un fichier de couverture
 * (Dicollecte) ne contient que les formes absentes du fichier de fréquences, ou leur apporte un
 * complément de compte, jamais une copie. Le maximum, envisagé, rendrait les masses des nœuds
 * incohérentes avec les fréquences des formes.
 *
 * [completions] est exacte quand chaque forme n'appartient qu'à un membre (cas des fichiers
 * découpés par source) ; sinon la fréquence de chaque forme renvoyée reste exacte (sommée sur
 * tous les membres), mais une forme répartie entre plusieurs membres sans figurer dans les
 * meilleures formes d'aucun peut manquer (voir `docs/DECISIONS.md`).
 *
 * Immuable et sans état : utilisable depuis plusieurs fils d'exécution comme [Lexicon].
 */
class LexiconSet(members: List<Lexicon>) {

    /** Les lexiques fusionnés, dans l'ordre fourni. */
    val members: List<Lexicon> = members.toList()

    init {
        require(this.members.isNotEmpty()) { "Un ensemble de lexiques contient au moins un lexique." }
        val languages = this.members.map { primaryLanguage(it.languageTag) }.distinct()
        require(languages.size == 1) { "Les lexiques d'un ensemble doivent être d'une même langue : $languages." }
    }

    constructor(vararg members: Lexicon) : this(members.toList())

    /** Étiquette de langue du premier membre. */
    val languageTag: String get() = members[0].languageTag

    /** Code de langue principal (`fr` pour `fr-CA`). */
    val language: String get() = primaryLanguage(languageTag)

    /** Masse totale (somme des fréquences de toutes les formes de tous les membres). */
    val totalMass: Double = members.sumOf { it.distribution("").totalMass }

    /** Un nouvel ensemble avec [lexicon] en plus (dictionnaire personnel, contacts). */
    operator fun plus(lexicon: Lexicon): LexiconSet = LexiconSet(members + lexicon)

    /**
     * Distribution de la lettre suivant [prefix] dans le lexique union : masses additionnées
     * membre par membre. Inconnue si aucun membre ne connaît [prefix].
     */
    fun distribution(prefix: String): Distribution {
        if (members.size == 1) return members[0].distribution(prefix)
        val masses = DoubleArray(LexiconAlphabet.SIZE)
        var total = 0.0
        var end = 0.0
        for (member in members) {
            val d = member.distribution(prefix)
            if (d.isUnknownPrefix) continue
            total += d.totalMass
            end += d.endProbability * d.totalMass
            for (i in masses.indices) masses[i] += d.probability(LexiconAlphabet.CHARS[i]) * d.totalMass
        }
        if (total == 0.0) return Distribution.UNKNOWN
        for (i in masses.indices) masses[i] /= total
        return Distribution(false, total, end / total, masses)
    }

    /** Masse des mots commençant par [prefix], tous membres confondus (0 si inconnu) ; voir [Lexicon.prefixMass]. */
    fun prefixMass(prefix: String): Double = members.sumOf { it.prefixMass(prefix) }

    /** Vrai si [key] est une clé (un mot) d'au moins un membre. */
    fun containsKey(key: String): Boolean = members.any { it.displayForms(key).isNotEmpty() }

    /**
     * Les formes d'affichage de la clé [key], fréquences additionnées entre membres, par
     * fréquence décroissante puis forme croissante ; vide si aucun membre ne la contient.
     */
    fun displayForms(key: String): List<LexiconEntry> {
        if (members.size == 1) return members[0].displayForms(key)
        val merged = LinkedHashMap<String, Long>()
        for (member in members) {
            for (entry in member.displayForms(key)) merged.merge(entry.form, entry.frequency, ::saturatedSum)
        }
        return merged.map { LexiconEntry(it.key, key, it.value) }.sortedWith(ENTRY_ORDER)
    }

    /**
     * Les [limit] formes les plus fréquentes dont la clé commence par [prefix], dans l'ordre de
     * [Lexicon.completions] (fréquence décroissante, puis clé et forme croissantes). Voir la
     * remarque d'exactitude dans la documentation de la classe.
     *
     * @throws IllegalArgumentException si [limit] est négatif.
     */
    fun completions(prefix: String, limit: Int): List<LexiconEntry> {
        if (members.size == 1) return members[0].completions(prefix, limit)
        require(limit >= 0) { "La limite doit être positive ou nulle ($limit)." }
        val seen = LinkedHashMap<Pair<String, String>, Long>()
        for (member in members) {
            for (entry in member.completions(prefix, limit)) seen.putIfAbsent(entry.key to entry.form, 0L)
        }
        // Fréquence exacte de chaque forme vue : somme sur tous les membres.
        val keys = seen.keys.map { it.first }.distinct()
        val result = ArrayList<LexiconEntry>(seen.size)
        for (key in keys) {
            for (entry in displayForms(key)) if ((key to entry.form) in seen) result += entry
        }
        result.sortWith(COMPLETION_ORDER)
        return if (result.size > limit) result.subList(0, limit).toList() else result
    }

    private companion object {
        val ENTRY_ORDER: Comparator<LexiconEntry> =
            compareByDescending<LexiconEntry> { it.frequency }.thenBy { it.form }

        val COMPLETION_ORDER: Comparator<LexiconEntry> =
            compareByDescending<LexiconEntry> { it.frequency }.thenBy { it.key }.thenBy { it.form }

        fun saturatedSum(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

        fun primaryLanguage(tag: String): String = tag.substringBefore('-').lowercase()
    }
}
