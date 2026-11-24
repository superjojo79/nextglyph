package nextglyph.engine.ngram

import nextglyph.engine.lexicon.ByteSink
import nextglyph.engine.lexicon.LexiconAlphabet
import nextglyph.engine.text.TextNormalizer
import java.util.Arrays
import java.util.Locale
import java.util.TreeMap

/**
 * Construit le fichier d'un modèle de mot suivant (format : [NgramFormat]) à partir de phrases
 * déjà découpées en mots.
 *
 * - Un mot est une **forme d'affichage** (`école`, `j'`, `I`) ; sa clé est `TextNormalizer.normalize`.
 *   Les formes de même clé sont fusionnées : la plus fréquente (puis la première dans l'ordre
 *   alphabétique) sert d'affichage. Un mot qui n'est pas une clé valide du lexique (hors alphabet,
 *   vide, avec espace, trop long) est un mot **inconnu** : il casse les contextes et n'est jamais proposé.
 * - Les mots proposés sont filtrés par `predictable` (noms propres, mots rares...) ; ils restent
 *   utilisables comme contexte.
 * - Le vocabulaire garde les [NgramPruning.maxVocabulary] mots les plus fréquents.
 * - Les probabilités sont `P(suivant | contexte)` calculées sur **toutes** les occurrences du
 *   contexte, y compris celles élaguées : les listes gardées ne somment donc pas à 1.
 *
 * - Les **confusions** (issue #55) sont des ensembles de formes que le clavier peut prendre l'une pour l'autre
 *   (`a`/`à`, `ces`/`ses`) et que les clés fusionnent ou que le repli des bigrammes élague : le modèle garde pour
 *   chacun, mot précédent par mot précédent, le nombre d'occurrences de chaque forme, sans élagage des suivants.
 *
 * Consomme de la mémoire proportionnelle au nombre de mots ajoutés (8 octets par bigramme et par
 * trigramme). Non thread-safe.
 */
class NgramModelBuilder(
    val languageTag: String,
    private val pruning: NgramPruning = NgramPruning(),
    private val predictable: (String) -> Boolean = { true },
    private val confusionSets: List<List<String>> = emptyList(),
) {

    init {
        require(NgramFormat.isValidTag(languageTag)) { "Étiquette de langue invalide : « $languageTag »." }
        require(confusionSets.size <= MAX_CONFUSION_SETS) { "Au plus $MAX_CONFUSION_SETS ensembles de confusion (${confusionSets.size})." }
        val seen = HashSet<String>()
        for (set in confusionSets) {
            require(set.size in 2..NgramFormat.MAX_CONFUSION_FORMS) {
                "Un ensemble de confusion a de 2 à ${NgramFormat.MAX_CONFUSION_FORMS} formes (${set.size})."
            }
            for (form in set) {
                require(form.isNotEmpty() && form == form.lowercase(Locale.ROOT) && form.toByteArray(Charsets.UTF_8).size <= MAX_FORM_BYTES) {
                    "Une forme de confusion est en minuscules, non vide et d'au plus $MAX_FORM_BYTES octets (« $form »)."
                }
                require(seen.add(form)) { "La forme « $form » est dans deux ensembles de confusion." }
            }
        }
    }

    private val confusionForms: Map<String, Pair<Int, Int>> =
        confusionSets.withIndex().flatMap { (set, forms) -> forms.withIndex().map { (index, form) -> form to (set to index) } }.toMap()
    private val confusions = HashMap<Long, LongArray>()

    private val formIds = HashMap<String, Int>()
    private val forms = ArrayList<String>()
    private val formKeys = ArrayList<String>()
    private var formCounts = LongArray(INITIAL_FORMS)
    private val bigrams = LongList()
    private val trigrams = LongList()

    /** Nombre de mots ajoutés (mots inconnus compris). */
    var tokenCount: Long = 0
        private set

    /** Nombre de phrases non vides ajoutées. */
    var sentenceCount: Long = 0
        private set

    /**
     * Ajoute [times] fois la phrase [tokens] (formes d'affichage, dans l'ordre). Une phrase vide est ignorée.
     *
     * @throws IllegalArgumentException si [times] n'est pas strictement positif.
     * @throws IllegalStateException si plus de [MAX_FORMS] formes distinctes ont été ajoutées.
     */
    fun addSentence(tokens: List<String>, times: Int = 1) {
        require(times >= 1) { "Le nombre de répétitions doit être strictement positif ($times)." }
        if (tokens.isEmpty()) return
        val ids = IntArray(tokens.size) { formId(tokens[it]) }
        repeat(times) {
            var before = ABSENT
            var previous = START
            for (id in ids) {
                bigrams.add(pack(previous, id))
                if (before != ABSENT) trigrams.add(pack(before, previous, id))
                formCounts[id]++
                before = previous
                previous = id
            }
        }
        tokenCount += tokens.size.toLong() * times
        sentenceCount += times
    }

    /**
     * Compte, dans les [tokens] d'un même groupe de mots consécutifs (sans signe de ponctuation entre eux), les
     * formes des ensembles de confusion et le mot qui les précède ([times] fois). Un mot précédent inconnu, ou une
     * forme en tête du groupe, n'est pas compté. Indépendant de [addSentence].
     *
     * @throws IllegalArgumentException si [times] n'est pas strictement positif.
     */
    fun addConfusionContext(tokens: List<String>, times: Int = 1) {
        require(times >= 1) { "Le nombre de répétitions doit être strictement positif ($times)." }
        if (confusionForms.isEmpty()) return
        for (i in 1 until tokens.size) {
            val (set, form) = confusionForms[tokens[i].lowercase(Locale.ROOT)] ?: continue
            val previous = formId(tokens[i - 1])
            if (previous == UNKNOWN) continue
            val counts = confusions.getOrPut((set.toLong() shl 32) or previous.toLong()) { LongArray(confusionSets[set].size) }
            counts[form] += times.toLong()
        }
    }

    /** Sérialise le modèle. Peut être appelé plusieurs fois ; les ajouts suivants sont pris en compte. */
    fun build(): ByteArray {
        val vocabulary = selectVocabulary()
        val pairs = aggregateBigrams(vocabulary)
        val bigramLists = (0..vocabulary.size).map { selectBigrams(it, vocabulary, pairs) }
        val trigramEntries = selectTrigrams(vocabulary, pairs)
        return serialize(vocabulary, bigramLists, trigramEntries, confusionZone(vocabulary))
    }

    // ----- Formes -----

    private fun formId(form: String): Int {
        formIds[form]?.let { return it }
        val key = keyOf(form)
        if (key == null) {
            formIds[form] = UNKNOWN
            return UNKNOWN
        }
        check(forms.size < MAX_FORMS) { "Trop de formes distinctes (plus de $MAX_FORMS)." }
        val id = FIRST_WORD + forms.size
        forms += form
        formKeys += key
        formIds[form] = id
        if (id >= formCounts.size) formCounts = formCounts.copyOf(formCounts.size * 2)
        return id
    }

    private fun keyOf(form: String): String? {
        val key = try {
            TextNormalizer.normalize(form)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return key.takeIf(LexiconAlphabet::isValidKey)
    }

    // ----- Vocabulaire -----

    /**
     * Vocabulaire retenu : [keys] et [displays] triés par clé (identifiant final = index + 1),
     * [finalOf] donne l'identifiant final d'une forme (`NONE` hors vocabulaire), [canPredict] dit si le mot
     * (par identifiant final, `0` compris) peut être proposé.
     */
    private class Vocabulary(
        val keys: List<String>,
        val displays: List<String>,
        val finalOf: IntArray,
        val canPredict: BooleanArray,
    ) {
        val size: Int get() = keys.size
    }

    private class KeyGroup(val key: String) {
        var total = 0L
        var bestForm = ""
        var bestCount = 0L
        val formIdList = ArrayList<Int>()
    }

    private fun selectVocabulary(): Vocabulary {
        val groups = HashMap<String, KeyGroup>()
        for (index in forms.indices) {
            val id = FIRST_WORD + index
            val group = groups.getOrPut(formKeys[index]) { KeyGroup(formKeys[index]) }
            val count = formCounts[id]
            group.total += count
            group.formIdList += id
            val form = forms[index]
            if (count > group.bestCount || (count == group.bestCount && form < group.bestForm) || group.bestForm.isEmpty()) {
                group.bestCount = count
                group.bestForm = form
            }
        }
        val kept = groups.values
            .sortedWith(compareByDescending<KeyGroup> { it.total }.thenBy { it.key })
            .take(pruning.maxVocabulary)
            .sortedBy { it.key }
        val finalOf = IntArray(FIRST_WORD + forms.size) { NONE }
        finalOf[START] = NgramFormat.START_ID
        kept.forEachIndexed { index, group -> for (id in group.formIdList) finalOf[id] = index + 1 }
        val canPredict = BooleanArray(kept.size + 1)
        kept.forEachIndexed { index, group -> canPredict[index + 1] = predictable(group.bestForm) }
        return Vocabulary(kept.map { it.key }, kept.map { it.bestForm }, finalOf, canPredict)
    }

    // ----- Bigrammes -----

    /**
     * Paires (contexte, suivant) distinctes triées, avec leur nombre d'occurrences ; [starts] donne,
     * pour chaque contexte, l'index de sa première paire ; [totals] son nombre d'occurrences total.
     */
    private class Pairs(val keys: LongArray, val counts: IntArray, val starts: IntArray, val totals: LongArray) {
        fun count(context: Int, next: Int): Int {
            val index = Arrays.binarySearch(keys, pack(context, next))
            return if (index >= 0) counts[index] else 0
        }
    }

    private fun aggregateBigrams(vocabulary: Vocabulary): Pairs {
        val mapped = LongArray(bigrams.size)
        var size = 0
        for (i in 0 until bigrams.size) {
            val value = bigrams[i]
            val context = vocabulary.finalOf[first2(value)]
            if (context == NONE) continue
            mapped[size++] = pack(context, vocabulary.finalOf[second2(value)])
        }
        Arrays.sort(mapped, 0, size)
        val totals = LongArray(vocabulary.size + 1)
        val keys = LongArray(size)
        val counts = IntArray(size)
        var distinct = 0
        var i = 0
        while (i < size) {
            var j = i
            while (j < size && mapped[j] == mapped[i]) j++
            val context = first2(mapped[i])
            val next = second2(mapped[i])
            totals[context] += (j - i).toLong()
            if (next != NONE) {
                keys[distinct] = mapped[i]
                counts[distinct] = j - i
                distinct++
            }
            i = j
        }
        val starts = IntArray(vocabulary.size + 2)
        for (index in 0 until distinct) starts[first2(keys[index]) + 1]++
        for (context in 1 until starts.size) starts[context] += starts[context - 1]
        return Pairs(keys.copyOf(distinct), counts.copyOf(distinct), starts, totals)
    }

    /** Un suivant gardé : identifiant final et probabilité. */
    private class Follower(val id: Int, val probability: Double)

    private fun selectBigrams(context: Int, vocabulary: Vocabulary, pairs: Pairs): List<Follower> {
        val total = pairs.totals[context]
        val candidates = ArrayList<Pair<Int, Int>>()
        for (index in pairs.starts[context] until pairs.starts[context + 1]) {
            val next = second2(pairs.keys[index])
            if (pairs.counts[index] >= pruning.minBigramCount && vocabulary.canPredict[next]) {
                candidates += next to pairs.counts[index]
            }
        }
        return top(candidates, pruning.maxBigramFollowers, total)
    }

    private fun top(candidates: List<Pair<Int, Int>>, limit: Int, total: Long): List<Follower> =
        candidates
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { Follower(it.first, it.second.toDouble() / total) }

    // ----- Trigrammes -----

    private class TrigramEntry(val first: Int, val second: Int, val followers: List<Follower>)

    private fun selectTrigrams(vocabulary: Vocabulary, pairs: Pairs): List<TrigramEntry> {
        val mapped = LongArray(trigrams.size)
        var size = 0
        for (i in 0 until trigrams.size) {
            val value = trigrams[i]
            val a = vocabulary.finalOf[first3(value)]
            val b = vocabulary.finalOf[second3(value)]
            if (a == NONE || b == NONE) continue
            mapped[size++] = pack(a, b, vocabulary.finalOf[third3(value)])
        }
        Arrays.sort(mapped, 0, size)
        val entries = ArrayList<TrigramEntry>()
        var i = 0
        while (i < size) {
            val a = first3(mapped[i])
            val b = second3(mapped[i])
            var end = i
            while (end < size && first3(mapped[end]) == a && second3(mapped[end]) == b) end++
            val total = (end - i).toLong()
            val candidates = ArrayList<Pair<Int, Int>>()
            var j = i
            while (j < end) {
                var k = j
                while (k < end && mapped[k] == mapped[j]) k++
                val next = third3(mapped[j])
                val count = k - j
                if (next != NONE && count >= pruning.minTrigramCount && vocabulary.canPredict[next] &&
                    isInformative(count, total, pairs, b, next)
                ) {
                    candidates += next to count
                }
                j = k
            }
            val followers = top(candidates, pruning.maxTrigramFollowers, total)
            if (followers.isNotEmpty()) entries += TrigramEntry(a, b, followers)
            i = end
        }
        return entries
    }

    /** Le trigramme apporte au moins [NgramPruning.minTrigramGain] fois la probabilité du bigramme. */
    private fun isInformative(count: Int, total: Long, pairs: Pairs, context: Int, next: Int): Boolean {
        val bigram = pairs.count(context, next).toDouble() / pairs.totals[context]
        return count.toDouble() / total >= pruning.minTrigramGain * bigram
    }

    // ----- Confusions -----

    /** Zone des confusions (format : [NgramFormat]) : comptes par mot précédent du vocabulaire, au moins [NgramPruning.minConfusionCount]. */
    private fun confusionZone(vocabulary: Vocabulary): ByteArray {
        val zone = ByteSink()
        zone.writeByte(confusionSets.size)
        for ((set, forms) in confusionSets.withIndex()) {
            zone.writeByte(forms.size)
            for (form in forms) {
                val bytes = form.toByteArray(Charsets.UTF_8)
                zone.writeByte(bytes.size)
                zone.writeBytes(bytes)
            }
            val merged = TreeMap<Int, LongArray>()
            for ((key, counts) in confusions) {
                if ((key ushr 32).toInt() != set) continue
                val id = vocabulary.finalOf[(key and 0xFFFFFFFFL).toInt()]
                if (id == NONE) continue
                val into = merged.getOrPut(id) { LongArray(forms.size) }
                for (i in counts.indices) into[i] += counts[i]
            }
            val kept = merged.filterValues { it.sum() >= pruning.minConfusionCount }
            val largest = kept.values.maxOfOrNull { it.max() } ?: 0L
            val divisor = if (largest > NgramFormat.MAX_CONFUSION_COUNT) (largest + NgramFormat.MAX_CONFUSION_COUNT - 1) / NgramFormat.MAX_CONFUSION_COUNT else 1L
            zone.writeInt(kept.size)
            for ((id, counts) in kept) {
                zone.writeShort(id)
                for (count in counts) zone.writeShort((count / divisor).toInt())
            }
        }
        return zone.toByteArray()
    }

    // ----- Sérialisation -----

    private fun serialize(vocabulary: Vocabulary, bigramLists: List<List<Follower>>, trigramEntries: List<TrigramEntry>, confusionZone: ByteArray): ByteArray {
        val blob = ByteSink()
        val wordOffsets = IntArray(vocabulary.size + 1)
        for (index in 0 until vocabulary.size) {
            wordOffsets[index] = blob.size
            val key = vocabulary.keys[index]
            val display = vocabulary.displays[index]
            blob.writeByte(key.length)
            blob.writeBytes(key.toByteArray(Charsets.US_ASCII))
            if (display == key) {
                blob.writeByte(0)
            } else {
                val bytes = display.toByteArray(Charsets.UTF_8)
                blob.writeByte(bytes.size)
                blob.writeBytes(bytes)
            }
        }
        wordOffsets[vocabulary.size] = blob.size

        val bigramRecords = bigramLists.sumOf { it.size }
        val trigramRecords = trigramEntries.sumOf { it.followers.size }
        val tag = languageTag.toByteArray(Charsets.US_ASCII)
        val out = ByteSink()
        out.writeBytes(NgramFormat.MAGIC)
        out.writeShort(NgramFormat.VERSION)
        out.writeByte(tag.size)
        out.writeBytes(tag)
        out.writeInt(vocabulary.size)
        out.writeInt(blob.size)
        out.writeInt(bigramRecords)
        out.writeInt(trigramEntries.size)
        out.writeInt(trigramRecords)
        out.writeInt(confusionZone.size)

        wordOffsets.forEach(out::writeInt)
        out.writeBytes(blob.toByteArray())

        var position = 0
        for (list in bigramLists) {
            out.writeInt(position)
            position += list.size
        }
        out.writeInt(position)
        bigramLists.forEach { writeFollowers(out, it) }

        for (entry in trigramEntries) {
            out.writeInt((entry.first shl 16) or entry.second)
        }
        position = 0
        for (entry in trigramEntries) {
            out.writeInt(position)
            position += entry.followers.size
        }
        out.writeInt(position)
        trigramEntries.forEach { writeFollowers(out, it.followers) }
        out.writeBytes(confusionZone)
        return out.toByteArray()
    }

    private fun writeFollowers(out: ByteSink, followers: List<Follower>) {
        for (follower in followers) {
            out.writeShort(follower.id)
            out.writeByte(ProbabilityCodec.encode(follower.probability))
        }
    }

    // ----- Entiers empaquetés (21 bits par identifiant) -----

    private class LongList {
        private var values = LongArray(INITIAL_OCCURRENCES)
        var size = 0
            private set

        fun add(value: Long) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        operator fun get(index: Int): Long = values[index]
    }

    companion object {
        /** Nombre maximal de formes distinctes : les identifiants tiennent sur 21 bits. */
        const val MAX_FORMS: Int = (1 shl 21) - 4

        private const val MAX_CONFUSION_SETS = 255
        private const val MAX_FORM_BYTES = 255
        private const val INITIAL_FORMS = 1024
        private const val INITIAL_OCCURRENCES = 1 shl 12

        // Identifiants provisoires : mot inconnu, début de phrase, puis les formes.
        private const val UNKNOWN = 0
        private const val START = 1
        private const val FIRST_WORD = 2
        private const val ABSENT = -1

        // Identifiant final « hors vocabulaire ».
        private const val NONE = (1 shl 21) - 1
        private const val MASK = NONE.toLong()

        private fun pack(first: Int, second: Int): Long = (first.toLong() shl 21) or second.toLong()

        private fun pack(first: Int, second: Int, third: Int): Long = (first.toLong() shl 42) or (second.toLong() shl 21) or third.toLong()

        private fun first2(value: Long): Int = (value ushr 21).toInt()

        private fun second2(value: Long): Int = (value and MASK).toInt()

        private fun first3(value: Long): Int = (value ushr 42).toInt()

        private fun second3(value: Long): Int = ((value ushr 21) and MASK).toInt()

        private fun third3(value: Long): Int = (value and MASK).toInt()
    }
}
