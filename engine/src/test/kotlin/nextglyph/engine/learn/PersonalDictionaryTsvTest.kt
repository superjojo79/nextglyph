package nextglyph.engine.learn

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class PersonalDictionaryTsvTest {

    private val dictionary = PersonalDictionary()

    private fun exported(source: PersonalDictionary): String =
        ByteArrayOutputStream().also { PersonalDictionaryTsv.export(source, it) }.toString(Charsets.UTF_8)

    private fun import(text: String, into: PersonalDictionary = dictionary, bytes: ByteArray = text.toByteArray(Charsets.UTF_8)): ImportReport =
        PersonalDictionaryTsv.import(ByteArrayInputStream(bytes), into)

    @Nested
    inner class HappyPath {

        @Test
        fun `un import propre n'a ni version inconnue, ni lecture impossible, ni troncature`() {
            val report = import("fr\tZorglub\t2\n")

            assertThat(report.unsupportedVersion).isFalse()
            assertThat(report.unreadable).isFalse()
            assertThat(report.truncated).isFalse()
            assertThat(report.skippedCount).isZero()
            assertThat(report.isClean).isTrue()
        }

        @Test
        fun `l'export vide le flux`() {
            var flushed = false
            val out = object : ByteArrayOutputStream() {
                override fun flush() {
                    flushed = true
                }
            }

            PersonalDictionaryTsv.export(dictionary, out)

            assertThat(flushed).isTrue()
        }

        @Test
        fun `l'export est un TSV lisible, trié par langue puis forme`() {
            dictionary.importWord("Zorglub", "fr", 5)
            dictionary.importWord("blorp", "fr", 2)
            dictionary.importWord("quux", "en", 3)
            dictionary.observe("enattente", "fr", true, nextglyph.engine.predict.PredictionSettings())

            assertThat(exported(dictionary)).isEqualTo(
                "# Nextglyph dictionnaire personnel v1\n" +
                    "# langue\tmot\tutilisations\n" +
                    "en\tquux\t3\n" +
                    "fr\tblorp\t2\n" +
                    "fr\tZorglub\t5\n",
            )
        }

        @Test
        fun `un dictionnaire vide s'exporte avec ses seuls commentaires`() {
            assertThat(exported(dictionary)).isEqualTo("# Nextglyph dictionnaire personnel v1\n# langue\tmot\tutilisations\n")
        }

        @Test
        fun `l'export puis l'import redonnent le même dictionnaire`() {
            dictionary.importWord("Zorglub", "fr", 5)
            dictionary.importWord("naïve-mot", "fr", 2)
            dictionary.importWord("quux", "en", 3)
            val copy = PersonalDictionary()

            val report = import(exported(dictionary), copy)

            assertThat(report.isClean).isTrue()
            assertThat(report.imported).isEqualTo(3)
            assertThat(copy.words()).isEqualTo(dictionary.words())
        }

        @Test
        fun `l'import accepte les fins de ligne Windows, un BOM, des commentaires et des lignes vides`() {
            val text = "﻿# Nextglyph dictionnaire personnel v1\r\n\r\n# commentaire\r\nfr\tZorglub\t4\r\n  \r\nen\tquux\t1\r\n"

            val report = import(text)

            assertThat(report.isClean).isTrue()
            assertThat(dictionary.words().map { it.form to it.count }).containsExactlyInAnyOrder("Zorglub" to 4, "quux" to 1)
        }

        @Test
        fun `le nombre d'utilisations est facultatif`() {
            val report = import("fr\tZorglub\n")

            assertThat(report.imported).isEqualTo(1)
            assertThat(dictionary.words().single().count).isEqualTo(1)
        }

        @Test
        fun `un fichier sans en-tête est accepté`() {
            assertThat(import("fr\tZorglub\t2").imported).isEqualTo(1)
        }

        @Test
        fun `les espaces autour des colonnes sont ignorés`() {
            assertThat(import("fr \t Zorglub \t 3 ").imported).isEqualTo(1)
            assertThat(dictionary.words().single().count).isEqualTo(3)
        }

        @Test
        fun `un mot déjà présent voit ses compteurs additionnés`() {
            dictionary.importWord("Zorglub", "fr", 2)

            import("fr\tZorglub\t3\n")

            assertThat(dictionary.words().single().count).isEqualTo(5)
        }

        @Test
        fun `l'import ne ferme pas les flux et l'export vide le sien`() {
            var closed = false
            val out = object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                }
            }

            PersonalDictionaryTsv.export(dictionary, out)

            assertThat(closed).isFalse()
            assertThat(out.size()).isPositive()
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `les lignes invalides sont ignorées et décrites, sans arrêter l'import`() {
            val text = "fr\tZorglub\t2\n" +
                "seul\n" +
                "fr\ta\t2\n" +
                "fr\tblorp\tbeaucoup\n" +
                "fr\tblorp\t0\n" +
                "xx9\tblorp\t1\n" +
                "fr\tun\tdeux\ttrois\n" +
                "en\tquux\t1\n"

            val report = import(text)

            assertThat(report.imported).isEqualTo(2)
            assertThat(report.skippedCount).isEqualTo(6)
            assertThat(report.skipped.map { it.line }).containsExactly(2, 3, 4, 5, 6, 7)
            assertThat(report.skipped[0].reason).contains("colonnes")
            assertThat(report.skipped[2].reason).contains("utilisations")
            assertThat(report.skipped[1].reason).contains("langue, mot")
            assertThat(report.isClean).isFalse()
        }

        @Test
        fun `une version inconnue refuse tout le fichier`() {
            val report = import("# Nextglyph dictionnaire personnel v2\nfr\tZorglub\t2\n")

            assertThat(report.unsupportedVersion).isTrue()
            assertThat(report.imported).isZero()
            assertThat(dictionary.size).isZero()
            assertThat(report.isClean).isFalse()
        }

        @Test
        fun `un flux illisible est signalé`() {
            val failing = object : InputStream() {
                override fun read(): Int = throw IOException("disque")
            }

            val report = PersonalDictionaryTsv.import(failing, dictionary)

            assertThat(report.unreadable).isTrue()
            assertThat(report.isClean).isFalse()
        }

        @Test
        fun `des octets qui ne sont pas de l'UTF-8 ne font rien planter`() {
            val garbage = ByteArray(300) { (it * 37).toByte() }

            val report = import("", bytes = garbage)

            assertThat(report.imported).isZero()
        }

        @Test
        fun `un mot importé en dehors des règles est refusé`() {
            val report = import("fr\thttps://x.fr\t2\nfr\tab cd\t2\nfr\t${"a".repeat(33)}\t2\n")

            assertThat(report.imported).isZero()
            assertThat(report.skippedCount).isEqualTo(3)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un fichier vide n'importe rien`() {
            val report = import("")

            assertThat(report.isClean).isTrue()
            assertThat(report.imported).isZero()
        }

        @Test
        fun `seules les cent premières lignes ignorées sont détaillées`() {
            val text = (1..150).joinToString("\n") { "mauvaise ligne $it" }

            val report = import(text)

            assertThat(report.skippedCount).isEqualTo(150)
            assertThat(report.skipped).hasSize(PersonalDictionaryTsv.MAX_REPORTED)
        }

        @Test
        fun `le nombre de lignes lues est borné`() {
            val limited = PersonalDictionary(LearningConfig(maxImportLines = 3))

            val report = import("fr\taaa\t1\nfr\tbbb\t1\nfr\tccc\t1\nfr\tddd\t1\n", limited)

            assertThat(report.imported).isEqualTo(3)
            assertThat(report.truncated).isTrue()
            assertThat(report.isClean).isFalse()
        }

        @Test
        fun `exactement maxImportLines lignes ne sont pas tronquées`() {
            val limited = PersonalDictionary(LearningConfig(maxImportLines = 3))

            val report = import("fr\taaa\t1\nfr\tbbb\t1\nfr\tccc\t1", limited)

            assertThat(report.imported).isEqualTo(3)
            assertThat(report.truncated).isFalse()
        }

        @Test
        fun `la taille du fichier est bornée`() {
            val limited = PersonalDictionary(LearningConfig(maxImportBytes = 16))
            val text = "fr\taaa\t1\nfr\tbbb\t1\nfr\tccc\t1\n"

            val report = import(text, limited)

            assertThat(report.truncated).isTrue()
            assertThat(report.imported).isEqualTo(1)
        }

        @Test
        fun `un fichier de exactement maxImportBytes n'est pas tronqué`() {
            val text = "fr\taaa\t1\nfr\tbbb\t1\n"
            val limited = PersonalDictionary(LearningConfig(maxImportBytes = text.length))

            val report = import(text, limited)

            assertThat(report.truncated).isFalse()
            assertThat(report.imported).isEqualTo(2)
        }

        @Test
        fun `l'import suit les plafonds du dictionnaire`() {
            val small = PersonalDictionary(LearningConfig(maxWords = 2))

            import("fr\taaa\t1\nfr\tbbb\t1\nfr\tccc\t1\n", small)

            assertThat(small.words().map { it.form }).containsExactly("bbb", "ccc")
        }

        @Test
        fun `un en-tête de la bonne version ne gêne pas, ailleurs qu'en première ligne un commentaire v2 est un simple commentaire`() {
            val report = import("fr\taaa\t1\n# Nextglyph dictionnaire personnel v2\nfr\tbbb\t1\n")

            assertThat(report.isClean).isTrue()
            assertThat(report.imported).isEqualTo(2)
        }
    }
}
