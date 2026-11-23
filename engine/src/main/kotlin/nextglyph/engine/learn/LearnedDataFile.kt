package nextglyph.engine.learn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** Nature d'un problème rencontré à la lecture d'un fichier d'état appris. */
enum class LoadIssueKind {
    /** Le flux n'a pas pu être lu (`IOException`). */
    UNREADABLE,

    /** Le fichier dépasse [LearningConfig.maxFileBytes]. */
    TOO_LARGE,

    /** L'en-tête n'est pas celui d'un fichier Nextglyph (ou le fichier est vide). */
    BAD_MAGIC,

    /** Version de format inconnue (fichier plus récent) : rien n'est lu. */
    UNSUPPORTED_VERSION,

    /** Le fichier s'arrête au milieu d'une section. */
    TRUNCATED,

    /** La somme de contrôle d'une section ne correspond pas : elle (et la suite) est ignorée. */
    CHECKSUM,

    /** Une section est intacte mais son contenu est invalide ou la section apparaît deux fois : elle est ignorée. */
    INVALID_SECTION,

    /** Des octets suivent la dernière section annoncée. */
    TRAILING_DATA,
}

/** Un problème de lecture : sa nature et un message pour le journal (sans aucun texte de l'utilisateur). */
data class LoadIssue(val kind: LoadIssueKind, val message: String)

/**
 * Résultat d'une lecture : l'état lu ([data], vide pour ce qui n'a pas pu être lu) et les problèmes
 * rencontrés. Une lecture ne lève jamais d'exception.
 */
class LoadResult(val data: LearnedData, val issues: List<LoadIssue>) {
    /** Vrai si tout le fichier a été lu sans aucun problème. */
    val isClean: Boolean get() = issues.isEmpty()
}

/**
 * Fichier d'état appris (spec §10.5, §5.5, §5.3) : dictionnaire personnel, refus et modèle spatial en
 * un seul fichier binaire, versionné et compact. L'IME fournit les flux ; aucune API Android.
 *
 * ```
 * fichier  = "NGLS" version:u16  sections:u8  section*
 * section  = tag:u8  version:u8  longueur:u32  charge utile  crc32:u32   (CRC sur tag, version, longueur, charge)
 * ```
 *
 * Entiers en gros-boutiste ; `varInt` = LEB128 ; chaînes = longueur `varInt` + UTF-8.
 *
 * | Tag | Section | Charge utile |
 * |---|---|---|
 * | 1 | Dictionnaire personnel | `n` promus puis `m` en attente (`varInt`), du moins récent au plus récent ; chaque mot : langue (chaîne), nombre de formes, puis (forme, compteur) |
 * | 2 | Refus | `n` (`varInt`), puis (texte tapé, texte inséré, compteur), du moins récent au plus récent |
 * | 3 | Modèle spatial | version 2 : 26 lettres `a`–`z` puis 4 touches de fonction (`SpatialModel.LEARNED_KEYS` : espace, entrée, ⌫, `?123`) ; chaque entrée : frappes (`varInt`), moyennes x et y, variances x et y (`double`). La version 1 (26 lettres seules) est encore lue, touches vides |
 *
 * **Lecture tolérante.** Chaque section porte sa somme de contrôle : un octet altéré est toujours
 * détecté. La lecture s'arrête à la première section illisible (sa longueur n'est plus fiable) en
 * gardant celles déjà lues et validées ; ce qui n'a pas été lu reste vide, et chaque problème est
 * signalé dans [LoadResult.issues] (l'IME peut le journaliser, sans texte, et prévenir l'utilisateur).
 * Une section de tag inconnu et de somme correcte est ignorée (fichier d'une version plus récente de
 * la même version de fichier). Une version de fichier inconnue donne un état vide.
 * Les compteurs et les tailles sont validés contre les plafonds de [LearningConfig] avant toute allocation.
 */
object LearnedDataFile {

    /** Version du format de fichier. */
    const val VERSION: Int = 1

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'G'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte())
    private const val SECTIONS = 3
    private const val TAG_PERSONAL = 1
    private const val TAG_REJECTIONS = 2
    private const val TAG_SPATIAL = 3
    private const val SECTION_VERSION = 1

    /** Version de la section du modèle spatial : 2 depuis l'ajout des touches de fonction (1 : lettres seules). */
    private const val SPATIAL_VERSION = 2
    private const val MAX_LANGUAGE_BYTES = 8
    private const val MAX_FORM_BYTES = 256
    private const val MAX_TEXT_BYTES = 4 * PersistentRejectionStore.MAX_TEXT_LENGTH

    /** Écrit l'état [data] dans [out] (sans le fermer) ; l'écriture est d'un bloc, après construction en mémoire. */
    fun write(data: LearnedData, out: OutputStream) {
        val file = ByteWriter()
        file.bytes(MAGIC)
        file.u16(VERSION)
        file.u8(SECTIONS)
        section(file, TAG_PERSONAL, SECTION_VERSION, personalPayload(data.personal))
        section(file, TAG_REJECTIONS, SECTION_VERSION, rejectionsPayload(data.rejections))
        section(file, TAG_SPATIAL, SPATIAL_VERSION, spatialPayload(data.spatial))
        out.write(file.toByteArray())
        out.flush()
    }

    /** Lit un état depuis [input] (sans le fermer), au plus [LearningConfig.maxFileBytes] octets. Ne lève jamais d'exception. */
    fun read(input: InputStream, config: LearningConfig = LearningConfig()): LoadResult {
        val bytes = try {
            ByteCodec.readAtMost(input, config.maxFileBytes + 1)
        } catch (e: IOException) {
            return LoadResult(LearnedData(config), listOf(LoadIssue(LoadIssueKind.UNREADABLE, "Lecture impossible : ${e.javaClass.simpleName}.")))
        }
        return decode(bytes, config)
    }

    /** Comme [read], sur un fichier déjà en mémoire. */
    fun decode(bytes: ByteArray, config: LearningConfig = LearningConfig()): LoadResult {
        val data = LearnedData(config)
        val issues = ArrayList<LoadIssue>()
        fun fail(kind: LoadIssueKind, message: String): LoadResult {
            issues += LoadIssue(kind, message)
            return LoadResult(data, issues)
        }
        if (bytes.size > config.maxFileBytes) return fail(LoadIssueKind.TOO_LARGE, "Fichier de plus de ${config.maxFileBytes} octets.")
        if (bytes.size < HEADER_BYTES || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return fail(LoadIssueKind.BAD_MAGIC, "Ce n'est pas un fichier d'état Nextglyph.")
        }
        val reader = ByteReader(bytes, MAGIC.size)
        val version = reader.u16()
        if (version != VERSION) return fail(LoadIssueKind.UNSUPPORTED_VERSION, "Version $version non prise en charge (attendue : $VERSION).")

        val count = reader.u8()
        val seen = HashSet<Int>()
        repeat(count) {
            val start = reader.position
            val tag: Int
            val sectionVersion: Int
            val length: Int
            try {
                tag = reader.u8()
                sectionVersion = reader.u8()
                val declared = reader.u32()
                if (declared > reader.remaining) return fail(LoadIssueKind.TRUNCATED, "Section $tag tronquée à l'octet $start.")
                length = declared.toInt()
            } catch (e: IllegalArgumentException) {
                return fail(LoadIssueKind.TRUNCATED, "En-tête de section tronqué à l'octet $start.")
            }
            val payload = reader.bytes(length)
            if (reader.remaining < CRC_BYTES) return fail(LoadIssueKind.TRUNCATED, "Section $tag sans somme de contrôle.")
            val crc = reader.u32()
            if (crc != checksum(bytes, start, SECTION_HEADER_BYTES + length)) {
                return fail(LoadIssueKind.CHECKSUM, "Somme de contrôle invalide pour la section $tag à l'octet $start.")
            }
            if (!seen.add(tag)) {
                issues += LoadIssue(LoadIssueKind.INVALID_SECTION, "Section $tag en double.")
                return@repeat
            }
            try {
                when (tag) {
                    TAG_PERSONAL -> readPersonal(known(sectionVersion, SECTION_VERSION, payload), data.personal, config)
                    TAG_REJECTIONS -> readRejections(known(sectionVersion, SECTION_VERSION, payload), data.rejections, config)
                    TAG_SPATIAL -> {
                        require(sectionVersion in 1..SPATIAL_VERSION) { "version de section $sectionVersion non prise en charge" }
                        readSpatial(ByteReader(payload), data.spatial, if (sectionVersion == 1) SPATIAL_LETTERS else SPATIAL_LETTERS + SpatialModel.LEARNED_KEYS.size)
                    }
                    else -> Unit // Section d'une version plus récente : ignorée.
                }
            } catch (e: IllegalArgumentException) {
                issues += LoadIssue(LoadIssueKind.INVALID_SECTION, "Section $tag invalide : ${e.message}")
            }
        }
        if (reader.remaining > 0) issues += LoadIssue(LoadIssueKind.TRAILING_DATA, "${reader.remaining} octets après la dernière section.")
        return LoadResult(data, issues)
    }

    private fun known(sectionVersion: Int, expected: Int, payload: ByteArray): ByteReader {
        require(sectionVersion == expected) { "version de section $sectionVersion non prise en charge" }
        return ByteReader(payload)
    }

    private const val CRC_BYTES = 4
    private const val SECTION_HEADER_BYTES = 6
    private const val HEADER_BYTES = 7

    private fun section(file: ByteWriter, tag: Int, version: Int, payload: ByteArray) {
        val section = ByteWriter()
        section.u8(tag)
        section.u8(version)
        section.u32(payload.size.toLong())
        section.bytes(payload)
        val body = section.toByteArray()
        file.bytes(body)
        file.u32(checksum(body, 0, body.size))
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Long =
        CRC32().apply { update(bytes, offset, length) }.value

    private fun personalPayload(dictionary: PersonalDictionary): ByteArray {
        val (promoted, pending) = dictionary.snapshot()
        val w = ByteWriter()
        for (list in listOf(promoted, pending)) {
            w.varInt(list.size)
            for (word in list) {
                w.string(word.language)
                w.varInt(word.forms.size)
                for ((form, count) in word.forms) {
                    w.string(form)
                    w.varInt(count)
                }
            }
        }
        return w.toByteArray()
    }

    private fun readPersonal(r: ByteReader, dictionary: PersonalDictionary, config: LearningConfig) {
        fun words(max: Int): List<StoredWord> = List(r.varInt(max)) {
            val language = r.string(MAX_LANGUAGE_BYTES)
            val forms = List(r.varInt(config.maxFormsPerWord)) { r.string(MAX_FORM_BYTES) to r.varInt(config.maxCount) }
            StoredWord(language, forms)
        }
        val promoted = words(config.maxWords)
        val pending = words(config.maxPending)
        r.requireEnd()
        dictionary.restore(promoted, pending)
    }

    private fun rejectionsPayload(store: PersistentRejectionStore): ByteArray {
        val entries = store.snapshot()
        val w = ByteWriter()
        w.varInt(entries.size)
        for ((typed, inserted, count) in entries) {
            w.string(typed)
            w.string(inserted)
            w.varInt(count)
        }
        return w.toByteArray()
    }

    private fun readRejections(r: ByteReader, store: PersistentRejectionStore, config: LearningConfig) {
        val entries = List(r.varInt(config.maxRejections)) {
            Triple(r.string(MAX_TEXT_BYTES), r.string(MAX_TEXT_BYTES), r.varInt(config.maxRejectionCount))
        }
        r.requireEnd()
        store.restore(entries)
    }

    private fun spatialPayload(model: SpatialModel): ByteArray {
        val w = ByteWriter()
        for (s in model.snapshot()) {
            w.varInt(s.samples)
            w.double(s.meanX)
            w.double(s.meanY)
            w.double(s.varX)
            w.double(s.varY)
        }
        return w.toByteArray()
    }

    private fun readSpatial(r: ByteReader, model: SpatialModel, entries: Int) {
        val stats = List(entries) {
            LetterStats(r.varInt(Int.MAX_VALUE), r.double(), r.double(), r.double(), r.double())
        }
        r.requireEnd()
        model.restore(stats)
    }

    private const val SPATIAL_LETTERS = 26
}
