package nextglyph.engine.lexicon

import java.util.Locale

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
 * [completions] renvoie, pour chaque clé vue parmi les meilleures complétions d'au moins un
 * membre, toutes ses formes fusionnées (fréquences exactes, sommées sur tous les membres). Elle est
 * exacte quand chaque forme n'appartient qu'à un membre (cas des fichiers découpés par source) ;
 * sinon une clé répartie entre plusieurs membres sans figurer dans les meilleures d'aucun peut
 * manquer (voir `docs/DECISIONS.md`).
 *
 * **Membres sans casse** ([caseless]) : un fichier de fréquences qui ne distingue pas la casse
 * (Lexique 4.10, toutes ses formes en minuscules) donne la fréquence d'une *orthographe*, pas d'une
 * forme. À la fusion, la fréquence de son orthographe `s` va à la forme `s` des autres membres si
 * elle existe (`paris` plutôt que `Paris`), sinon à chacune de leurs variantes de casse de `s`
 * (`Marseille`), sinon reste à `s`. C'est la règle qu'appliquait la liste française unique avant
 * son découpage par licence (`docs/DATA.md`) : formes et casse viennent de Dicollecte, fréquences
 * de Lexique, sans qu'aucun fichier ne contienne de données de l'autre source.
 *
 * Immuable et sans état : utilisable depuis plusieurs fils d'exécution comme [Lexicon].
 *
 * @param caseless membres de [members] dont les formes sont des orthographes sans casse.
 */
class LexiconSet(members: List<Lexicon>, caseless: Collection<Lexicon> = emptyList()) {

    /** Les lexiques fusionnés, dans l'ordre fourni. */
    val members: List<Lexicon> = members.toList()

    /** Les membres sans casse (voir la documentation de la classe). */
    val caseless: List<Lexicon> = caseless.toList()

    private val cased: List<Lexicon> = this.members.filter { member -> this.caseless.none { it === member } }

    init {
        require(this.members.isNotEmpty()) { "Un ensemble de lexiques contient au moins un lexique." }
        val languages = this.members.map { primaryLanguage(it.languageTag) }.distinct()
        require(languages.size == 1) { "Les lexiques d'un ensemble doivent être d'une même langue : $languages." }
        require(this.caseless.all { c -> this.members.any { it === c } }) { "Un membre sans casse doit faire partie de l'ensemble." }
    }

    constructor(vararg members: Lexicon) : this(members.toList())

    /** Étiquette de langue du premier membre. */
    val languageTag: String get() = members[0].languageTag

    /** Code de langue principal (`fr` pour `fr-CA`). */
    val language: String get() = primaryLanguage(languageTag)

    /** Masse totale (somme des fréquences de toutes les formes de tous les membres). */
    val totalMass: Double = members.sumOf { it.distribution("").totalMass }

    /** Un nouvel ensemble avec [lexicon] en plus (dictionnaire personnel, contacts). */
    operator fun plus(lexicon: Lexicon): LexiconSet = LexiconSet(members + lexicon, caseless)

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
        for (member in cased) {
            for (entry in member.displayForms(key)) merged.merge(entry.form, entry.frequency, ::saturatedSum)
        }
        if (caseless.isNotEmpty()) {
            val casedForms = merged.keys.toList()
            for (member in caseless) {
                for (entry in member.displayForms(key)) {
                    val spelling = entry.form.lowercase(Locale.ROOT)
                    val variants = casedForms.filter { it.lowercase(Locale.ROOT) == spelling }
                    val targets = when {
                        spelling in variants -> listOf(spelling)
                        variants.isNotEmpty() -> variants
                        else -> listOf(entry.form)
                    }
                    for (target in targets) merged.merge(target, entry.frequency, ::saturatedSum)
                }
            }
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
        val keys = LinkedHashSet<String>()
        for (member in members) {
            for (entry in member.completions(prefix, limit)) keys += entry.key
        }
        // Formes fusionnées de chaque clé vue, à leur fréquence exacte (somme sur tous les membres).
        val result = ArrayList<LexiconEntry>()
        for (key in keys) result += displayForms(key)
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
