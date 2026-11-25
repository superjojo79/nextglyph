package nextglyph.engine.pack

import nextglyph.engine.lexicon.LexiconFormat
import nextglyph.engine.ngram.NgramFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Un fichier listé par le manifeste.
 *
 * @property name nom du fichier dans l'archive.
 * @property size taille en octets.
 * @property sha256 empreinte SHA-256, hexadécimal minuscule.
 */
data class PackEntry(val name: String, val size: Long, val sha256: String) {
    companion object {
        /** Entrée décrivant [file] (taille et SHA-256 de son contenu) sous le nom [name]. */
        fun of(name: String, file: File): PackEntry = PackEntry(name, file.length(), PackFormat.sha256(file))
    }
}

/**
 * Manifeste d'un paquet (`manifest.txt`) : UTF-8, fins de ligne `\n`, une ligne `clé=valeur` chacune,
 *
 * ```
 * format=1
 * version=202611241830
 * lexiconFormat=1
 * ngramFormat=2
 * file=fr-lexique.nglx 6260123 <sha256>
 * ...
 * ```
 *
 * @property version entier `yyyyMMddHHmm` (UTC) de la construction, strictement croissant.
 * @property lexiconFormat `LexiconFormat.VERSION` de l'outil qui a construit le paquet.
 * @property ngramFormat `NgramFormat.VERSION` de l'outil qui a construit le paquet.
 * @property files les fichiers de [PackFormat.PACKED_FILES], tous obligatoires.
 */
data class PackManifest(val version: Long, val lexiconFormat: Int, val ngramFormat: Int, val files: List<PackEntry>) {

    /** Texte du manifeste ; les fichiers sont écrits dans l'ordre de [PackFormat.PACKED_FILES]. */
    fun serialize(): ByteArray = buildString {
        append("format=").append(PackFormat.FORMAT).append('\n')
        append("version=").append(version).append('\n')
        append("lexiconFormat=").append(lexiconFormat).append('\n')
        append("ngramFormat=").append(ngramFormat).append('\n')
        for (entry in files.sortedBy { PackFormat.PACKED_FILES.indexOf(it.name) }) {
            append("file=").append(entry.name).append(' ').append(entry.size).append(' ').append(entry.sha256).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    /** Entrée du fichier [name], ou `null`. */
    fun entry(name: String): PackEntry? = files.firstOrNull { it.name == name }

    /** Vrai si les formats binaires sont ceux de ce moteur. */
    val compatible: Boolean get() = lexiconFormat == LexiconFormat.VERSION && ngramFormat == NgramFormat.VERSION

    companion object {
        /** Manifeste de [version] décrivant [files], avec les formats binaires de ce moteur. */
        fun create(version: Long, files: List<PackEntry>): PackManifest =
            PackManifest(version, LexiconFormat.VERSION, NgramFormat.VERSION, files)

        private val SHA256 = Regex("[0-9a-f]{64}")
        private val NUMBER = Regex("[0-9]{1,18}")

        /**
         * Lit un manifeste, strictement : clés dans l'ordre, fichiers de [PackFormat.PACKED_FILES] sans doublon
         * ni absent, aucune ligne en trop.
         *
         * @throws PackException (`CORRUPT`) si le texte n'est pas conforme.
         */
        fun parse(bytes: ByteArray): PackManifest {
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                throw corrupt("le texte n'est pas de l'UTF-8 valide", e)
            }
            if (!text.endsWith("\n")) throw corrupt("le texte doit finir par un saut de ligne")
            val lines = text.dropLast(1).split('\n')
            if (lines.size < HEADER_KEYS.size + PackFormat.PACKED_FILES.size) throw corrupt("lignes manquantes")

            val header = HEADER_KEYS.mapIndexed { index, key -> number(lines[index], key) }
            if (header[0] != PackFormat.FORMAT.toLong()) throw corrupt("format ${header[0]} non pris en charge")

            val files = lines.drop(HEADER_KEYS.size).map(::entry)
            val names = files.map { it.name }
            if (names.toSet().size != names.size) throw corrupt("fichier listé deux fois")
            if (names.toSet() != PackFormat.PACKED_FILES.toSet()) throw corrupt("la liste des fichiers n'est pas celle attendue")
            return PackManifest(header[1], header[2].toInt(), header[3].toInt(), files)
        }

        private val HEADER_KEYS = listOf("format", "version", "lexiconFormat", "ngramFormat")

        private fun number(line: String, key: String): Long {
            val value = line.removePrefix("$key=")
            if (value.length == line.length || !NUMBER.matches(value)) throw corrupt("ligne « $line » invalide, « $key=<entier> » attendu")
            val number = value.toLong()
            if (key == "version" && number == 0L) throw corrupt("la version doit être positive")
            if (key != "version" && key != "format" && number > Int.MAX_VALUE) throw corrupt("« $key » trop grand")
            return number
        }

        private fun entry(line: String): PackEntry {
            val parts = line.removePrefix("file=").split(' ')
            if (parts.size != 3 || line.length == line.removePrefix("file=").length) throw corrupt("ligne « $line » invalide")
            val (name, size, sha256) = parts
            if (!NUMBER.matches(size) || !SHA256.matches(sha256)) throw corrupt("ligne « $line » invalide")
            return PackEntry(name, size.toLong(), sha256)
        }

        private fun corrupt(message: String, cause: Throwable? = null) =
            PackException(PackException.Reason.CORRUPT, "Manifeste invalide : $message.", cause)
    }
}
