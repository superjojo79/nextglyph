package nextglyph.engine.lexicon

import java.io.ByteArrayOutputStream

/**
 * Constantes du format binaire du lexique, version [VERSION].
 *
 * Tous les entiers multi-octets à taille fixe sont gros-boutistes. Les entiers à taille
 * variable (`varint`) sont en LEB128 non signé, sur 9 octets au plus (63 bits : tout `Long`
 * positif ou nul).
 *
 * ```
 * En-tête
 *   magic        4 octets  "NGLX"
 *   version      u16       1
 *   tagLength    u8        1..MAX_TAG_LENGTH
 *   tag          tagLength octets ASCII (étiquette de langue BCP 47, ex. "fr", "en-GB")
 *   keyCount     i32       nombre de clés
 *   formCount    i32       nombre de formes d'affichage
 *   rootOffset   i32       position du bloc racine dans la zone de données
 *   dataLength   i32       taille de la zone de données ; le fichier se termine juste après
 * Zone de données : blocs d'enfants, écrits en ordre postfixe (les enfants avant le parent)
 *   bloc         u8 count (0..28), puis count enregistrements triés par caractère
 *   enregistrement (un enfant)
 *     header     u8 : bits 0-4 index du 1er caractère de l'étiquette,
 *                     bit 5 HAS_CHILDREN, bit 6 TERMINAL, bit 7 LONG_LABEL
 *     [LONG_LABEL]   varint n ≥ 1, puis n octets : index des caractères suivants de l'étiquette
 *     [HAS_CHILDREN] u16 masse du sous-arbre quantifiée (MassCodec),
 *                    varint fréquence maximale d'une forme du sous-arbre (exacte),
 *                    varint distance (position de l'enregistrement − position du bloc enfant) ≥ 1
 *     [TERMINAL]     varint nombre de formes ≥ 1, puis pour chaque forme, par fréquence décroissante :
 *                    varint fréquence ≥ 1, varint longueur UTF-8 (0 = forme identique à la clé),
 *                    octets UTF-8 de la forme
 * ```
 *
 * Un enregistrement porte au moins l'un des drapeaux HAS_CHILDREN et TERMINAL. Les blocs
 * enfants étant toujours avant leur parent, toute descente progresse vers le début du
 * fichier et se termine, même sur un fichier corrompu.
 */
internal object LexiconFormat {

    val MAGIC: ByteArray = byteArrayOf('N'.code.toByte(), 'G'.code.toByte(), 'L'.code.toByte(), 'X'.code.toByte())
    const val VERSION: Int = 1

    const val MAX_TAG_LENGTH: Int = 35

    /** Taille de l'en-tête hors étiquette de langue. */
    const val FIXED_HEADER_SIZE: Int = 4 + 2 + 1 + 4 * 4

    const val CHAR_MASK: Int = 0x1F
    const val HAS_CHILDREN: Int = 0x20
    const val TERMINAL: Int = 0x40
    const val LONG_LABEL: Int = 0x80

    const val MAX_VARINT_BYTES: Int = 9

    /** Étiquettes de langue acceptées : sous-ensemble ASCII de BCP 47. */
    private val TAG_PATTERN = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")

    fun isValidTag(tag: String): Boolean = tag.length <= MAX_TAG_LENGTH && TAG_PATTERN.matches(tag)
}

/**
 * Quantification logarithmique sur 16 bits des masses de sous-arbre.
 *
 * Une masse `m ≥ 1` est codée `round(log2(m) · STEPS_PER_OCTAVE)`, ce qui couvre
 * `[1, 2^128]` avec une erreur relative d'au plus 0,07 %. Le calcul passe par `StrictMath`
 * pour que le codage soit identique sur toutes les plateformes.
 */
internal object MassCodec {

    const val STEPS_PER_OCTAVE: Int = 512
    const val MAX_CODE: Int = 0xFFFF

    private val LN2 = StrictMath.log(2.0)

    fun encode(mass: Double): Int {
        val code = StrictMath.round(StrictMath.log(mass) / LN2 * STEPS_PER_OCTAVE)
        return code.coerceIn(0L, MAX_CODE.toLong()).toInt()
    }

    fun decode(code: Int): Double = StrictMath.pow(2.0, code.toDouble() / STEPS_PER_OCTAVE)
}

/** Tampon d'écriture extensible, utilisé par [LexiconBuilder]. */
internal class ByteSink {

    private val out = ByteArrayOutputStream(1 shl 16)

    val size: Int get() = out.size()

    fun writeByte(value: Int) {
        out.write(value)
    }

    fun writeShort(value: Int) {
        writeByte(value ushr 8)
        writeByte(value)
    }

    fun writeInt(value: Int) {
        writeShort(value ushr 16)
        writeShort(value)
    }

    fun writeBytes(values: ByteArray) {
        out.write(values, 0, values.size)
    }

    /** Écrit [value] (≥ 0) en LEB128 non signé. */
    fun writeVarLong(value: Long) {
        var rest = value
        while (rest >= 0x80) {
            writeByte((rest and 0x7F).toInt() or 0x80)
            rest = rest ushr 7
        }
        writeByte(rest.toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
