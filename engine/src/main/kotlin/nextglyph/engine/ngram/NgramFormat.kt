package nextglyph.engine.ngram

import nextglyph.engine.lexicon.LexiconFormat

/**
 * Constantes du format binaire du modèle de mot suivant, version [VERSION] (issue #51).
 *
 * Tous les entiers sont gros-boutistes. Les identifiants de mots tiennent sur 16 bits : `0` est
 * le début de phrase `<s>`, `1..vocabCount` les mots, triés par clé (octets UTF-8 croissants).
 * Une probabilité est quantifiée sur un octet par [ProbabilityCodec].
 *
 * ```
 * En-tête
 *   magic          4 octets  "NGRM"
 *   version        u16       1
 *   tagLength      u8        1..MAX_TAG_LENGTH
 *   tag            tagLength octets ASCII (étiquette de langue BCP 47)
 *   vocabCount     i32       nombre de mots (hors début de phrase), ≤ MAX_VOCABULARY
 *   blobLength     i32       taille de la zone des mots
 *   bigramRecords  i32       nombre de suivants de la zone des bigrammes
 *   trigramCount   i32       nombre de contextes de trigrammes
 *   trigramRecords i32       nombre de suivants de la zone des trigrammes
 * Zone des mots
 *   offsets        u32 × (vocabCount + 1)   début de chaque mot dans la zone, puis la taille
 *   zone           pour chaque mot : u8 longueur de la clé, clé (ASCII), u8 longueur de la forme
 *                  d'affichage (0 = identique à la clé), forme d'affichage UTF-8
 * Zone des bigrammes (contexte = identifiant 0..vocabCount)
 *   offsets        u32 × (vocabCount + 2)   index du premier suivant de chaque contexte, puis le total
 *   suivants       (u16 identifiant ≥ 1, u8 probabilité quantifiée) × bigramRecords, par probabilité décroissante
 * Zone des trigrammes
 *   contextes      u32 × trigramCount       (identifiant 1 << 16) | identifiant 2, strictement croissants
 *   offsets        u32 × (trigramCount + 1)
 *   suivants       comme pour les bigrammes
 * ```
 *
 * Le fichier se termine juste après le dernier suivant. Les nombres, positions et identifiants
 * sont vérifiés au chargement (tailles) et à chaque lecture (offsets, identifiants).
 */
internal object NgramFormat {

    val MAGIC: ByteArray = byteArrayOf('N'.code.toByte(), 'G'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte())
    const val VERSION: Int = 1

    const val MAX_TAG_LENGTH: Int = LexiconFormat.MAX_TAG_LENGTH

    /** Identifiant du début de phrase. */
    const val START_ID: Int = 0

    /** Plus grand vocabulaire : les identifiants `1..MAX_VOCABULARY` tiennent sur 16 bits. */
    const val MAX_VOCABULARY: Int = 0xFFFF

    /** Taille d'un suivant : identifiant u16 et probabilité u8. */
    const val RECORD_SIZE: Int = 3

    /** Taille de l'en-tête hors étiquette de langue. */
    const val FIXED_HEADER_SIZE: Int = 4 + 2 + 1 + 5 * 4

    fun isValidTag(tag: String): Boolean = LexiconFormat.isValidTag(tag)
}

/**
 * Quantification d'une probabilité conditionnelle sur 8 bits : `q = round(−ln p × STEPS_PER_NEPER)`,
 * de 1 (`q = 0`) à `e^−15,9` (`q = 255`) ; erreur relative d'au plus 3,2 %. Calcul par `StrictMath`
 * pour un codage identique sur toutes les plateformes.
 */
internal object ProbabilityCodec {

    const val STEPS_PER_NEPER: Double = 16.0
    const val MAX_CODE: Int = 255

    private val DECODED = DoubleArray(MAX_CODE + 1) { StrictMath.exp(-it / STEPS_PER_NEPER) }

    fun encode(probability: Double): Int {
        if (!(probability > 0.0)) return MAX_CODE
        val code = StrictMath.round(-StrictMath.log(probability) * STEPS_PER_NEPER)
        return code.coerceIn(0L, MAX_CODE.toLong()).toInt()
    }

    fun decode(code: Int): Double = DECODED[code]
}
