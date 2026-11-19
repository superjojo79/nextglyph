package nextglyph.tools.wordlists

import nextglyph.tools.wordlists.ArchiveFixtures.lexique
import nextglyph.tools.wordlists.ArchiveFixtures.lexiqueRow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class SourceReadersTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `Lexique - garde la fréquence maximale par forme, en minuscules, apostrophe droite`() {
            val text = lexique(
                lexiqueRow("Le", "10.5"),
                lexiqueRow("le", "12.25"),
                lexiqueRow("l’", "3"),
                lexiqueRow("rare", "0.003"),
            )

            val result = LexiqueReader.read(text.lineSequence())

            assertThat(result.perMillion).containsOnly(
                entry("le", 12.25), entry("l'", 3.0), entry("rare", 0.003),
            )
            assertThat(result.malformedRows).isZero()
            assertThat(result.zeroFrequencyRows).isZero()
        }

        @Test
        fun `Lexique - les colonnes sont repérées par leur nom, dans n'importe quel ordre`() {
            val text = "11_FreqOrtho\tautre\t1_Mot\n7.5\tx\tmot\n"

            assertThat(LexiqueReader.read(text.lineSequence()).perMillion).containsOnly(entry("mot", 7.5))
        }

        @Test
        fun `Dicollecte - ignore le préambule, lit la colonne Flexion, normalise l'apostrophe`() {
            val text = ArchiveFixtures.dicollecte("Paris", "paris", "l’", "aujourd'hui")

            val result = DicollecteReader.read(text.lineSequence())

            assertThat(result.forms).containsExactlyInAnyOrder("Paris", "paris", "l'", "aujourd'hui")
            assertThat(result.malformedRows).isZero()
        }

        @Test
        fun `SCOWL - le niveau vient du suffixe et la catégorie du nom de fichier`() {
            val files = ArchiveFixtures.scowlFiles(
                "scowl-2020.12.07/final/english-words.10" to "the\nof\n",
                "scowl-2020.12.07/final/american-words.35" to "color\n",
                "scowl-2020.12.07/final/english-upper.50" to "Monday\n",
                "scowl-2020.12.07/final/english-proper-names.60" to "Paris\n",
                "scowl-2020.12.07/final/english-contractions.10" to "don't\n",
                "scowl-2020.12.07/final/english-abbreviations.70" to "etc.\n",
            )

            val result = ScowlReader.read(files, 70)

            assertThat(result.files).isEqualTo(6)
            assertThat(result.skippedAboveLevel).isZero()
            assertThat(result.entries).containsExactlyInAnyOrder(
                ScowlEntry("the", 10, ScowlCategory.WORD),
                ScowlEntry("of", 10, ScowlCategory.WORD),
                ScowlEntry("color", 35, ScowlCategory.WORD),
                ScowlEntry("Monday", 50, ScowlCategory.WORD),
                ScowlEntry("Paris", 60, ScowlCategory.WORD),
                ScowlEntry("don't", 10, ScowlCategory.CONTRACTION),
                ScowlEntry("etc.", 70, ScowlCategory.ABBREVIATION),
            )
        }

        @Test
        fun `SCOWL - décode l'ISO-8859-1`() {
            val files = ArchiveFixtures.scowlFiles("final/english-words.10" to "café\nnaïve\n")

            assertThat(ScowlReader.read(files, 70).entries.map { it.form }).containsExactly("café", "naïve")
        }

        @Test
        fun `SCOWL - reconnaît les noms de fichiers`() {
            assertThat(ScowlReader.isScowlFile("scowl-2020.12.07/final/english-words.10")).isTrue()
            assertThat(ScowlReader.isScowlFile("final/american-proper-names.95")).isTrue()
        }

        @Test
        fun `archives - lecture zip, bzip2 et tar gz`(@TempDir dir: Path) {
            val zip = dir.resolve("a.zip")
            ArchiveFixtures.zip(zip, mapOf("x/other.txt" to "non", "x/wanted.txt" to "ligne1\nligne2\n"))
            val bz2 = dir.resolve("a.bz2")
            ArchiveFixtures.bzip2(bz2, "été\ndeux\n")
            val tgz = dir.resolve("a.tar.gz")
            ArchiveFixtures.tarGz(tgz, mapOf("d/keep.txt" to "k".toByteArray(), "d/skip.txt" to "s".toByteArray()))

            val zipLines = Archives.withZipEntryLines(zip, { it.endsWith("wanted.txt") }) { it.toList() }
            val bzLines = Archives.withBzip2Lines(bz2) { it.toList() }
            val tar = Archives.readTarGz(tgz) { it.endsWith("keep.txt") }

            assertThat(zipLines).containsExactly("ligne1", "ligne2")
            assertThat(bzLines).containsExactly("été", "deux")
            assertThat(tar.keys).containsExactly("d/keep.txt")
            assertThat(String(tar.getValue("d/keep.txt"))).isEqualTo("k")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `Lexique - fichier vide`() {
            assertThatThrownBy { LexiqueReader.read(emptySequence()) }
                .isInstanceOf(WordListException::class.java).hasMessageContaining("vide")
        }

        @Test
        fun `Lexique - colonne attendue absente`() {
            assertThatThrownBy { LexiqueReader.read("a\tb\n1\t2\n".lineSequence()) }
                .isInstanceOf(WordListException::class.java).hasMessageContaining("1_Mot").hasMessageContaining("11_FreqOrtho")
            assertThatThrownBy { LexiqueReader.read("1_Mot\tb\nx\t2\n".lineSequence()) }
                .isInstanceOf(WordListException::class.java)
            assertThatThrownBy { LexiqueReader.read("a\t11_FreqOrtho\nx\t2\n".lineSequence()) }
                .isInstanceOf(WordListException::class.java)
        }

        @Test
        fun `Lexique - lignes mal formées et fréquences nulles sont comptées`() {
            val text = lexique(
                lexiqueRow("ok", "2"),
                "trop\tcourt",
                lexiqueRow("illisible", "abc"),
                lexiqueRow("nan", "NaN"),
                lexiqueRow("", "5"),
                lexiqueRow("zero", "0"),
                lexiqueRow("negatif", "-1"),
                "",
            )

            val result = LexiqueReader.read(text.lineSequence())

            assertThat(result.perMillion).containsOnly(entry("ok", 2.0))
            assertThat(result.malformedRows).isEqualTo(4)
            assertThat(result.zeroFrequencyRows).isEqualTo(2)
        }

        @Test
        fun `Dicollecte - en-tête absent`() {
            assertThatThrownBy { DicollecteReader.read("rien\nici\n".lineSequence()) }
                .isInstanceOf(WordListException::class.java).hasMessageContaining("Flexion")
            assertThatThrownBy { DicollecteReader.read("id\tfid\tautre\n".lineSequence()) }
                .isInstanceOf(WordListException::class.java)
        }

        @Test
        fun `Dicollecte - lignes sans forme comptées, lignes vides ignorées, doublons fusionnés`() {
            val text = "id\tfid\tFlexion\n0\t1\tchat\n\n0\t1\t\n0\n0\t1\tchat\n"

            val result = DicollecteReader.read(text.lineSequence())

            assertThat(result.forms).containsExactly("chat")
            assertThat(result.malformedRows).isEqualTo(2)
        }

        @Test
        fun `SCOWL - aucune liste reconnue`() {
            val files = ArchiveFixtures.scowlFiles("final/british-words.10" to "colour\n", "README" to "x")

            assertThatThrownBy { ScowlReader.read(files, 70) }
                .isInstanceOf(WordListException::class.java).hasMessageContaining("SCOWL")
            assertThatThrownBy { ScowlReader.read(emptyMap(), 70) }.isInstanceOf(WordListException::class.java)
        }

        @Test
        fun `SCOWL - noms de fichiers non reconnus`() {
            assertThat(ScowlReader.isScowlFile("final/english-words.txt")).isFalse()
            assertThat(ScowlReader.isScowlFile("english-words.10")).isFalse()
            assertThat(ScowlReader.isScowlFile("final/english-words.10.bak")).isFalse()
            assertThat(ScowlReader.isScowlFile("final/french-words.10")).isFalse()
            assertThat(ScowlReader.isScowlFile("xfinal/english-words.10")).isFalse()
        }

        @Test
        fun `zip - aucune entrée attendue`(@TempDir dir: Path) {
            val zip = dir.resolve("a.zip")
            ArchiveFixtures.zip(zip, mapOf("x.txt" to "a"))

            assertThatThrownBy { Archives.withZipEntryLines(zip, { false }) { it.toList() } }
                .isInstanceOf(WordListException::class.java).hasMessageContaining("a.zip")
        }

        @Test
        fun `zip - un dossier n'est jamais choisi`(@TempDir dir: Path) {
            val zip = dir.resolve("a.zip")
            ArchiveFixtures.zip(zip, mapOf("dossier/" to "", "dossier/f.txt" to "x\n"))

            assertThat(Archives.withZipEntryLines(zip, { it.startsWith("dossier") }) { it.toList() }).containsExactly("x")
        }

        @Test
        fun `archives corrompues - exception d'entrée-sortie`(@TempDir dir: Path) {
            val bad = dir.resolve("bad")
            Files.writeString(bad, "pas une archive")

            assertThatThrownBy { Archives.withZipEntryLines(bad, { true }) { it.toList() } }.isInstanceOf(IOException::class.java)
            assertThatThrownBy { Archives.withBzip2Lines(bad) { it.toList() } }.isInstanceOf(IOException::class.java)
            assertThatThrownBy { Archives.readTarGz(bad) { true } }.isInstanceOf(IOException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `Lexique - en-tête seul, fréquence minuscule et énorme`() {
            assertThat(LexiqueReader.read(lexique().lineSequence()).perMillion).isEmpty()

            val result = LexiqueReader.read(lexique(lexiqueRow("tiny", "1e-300"), lexiqueRow("huge", "1e300")).lineSequence())

            assertThat(result.perMillion).containsOnly(entry("tiny", 1e-300), entry("huge", 1e300))
        }

        @Test
        fun `Lexique - une ligne qui a exactement le nombre de colonnes nécessaire est acceptée`() {
            val text = "1_Mot\t11_FreqOrtho\nmot\t4\nmot2\n"

            val result = LexiqueReader.read(text.lineSequence())

            assertThat(result.perMillion).containsOnly(entry("mot", 4.0))
            assertThat(result.malformedRows).isEqualTo(1)
        }

        @Test
        fun `Lexique - BOM devant l'en-tête`() {
            val text = "﻿1_Mot\t11_FreqOrtho\nmot\t4\n"

            assertThat(LexiqueReader.read(text.lineSequence()).perMillion).containsOnly(entry("mot", 4.0))
        }

        @Test
        fun `Dicollecte - en-tête sans aucune ligne`() {
            val result = DicollecteReader.read("id\tfid\tFlexion\n".lineSequence())

            assertThat(result.forms).isEmpty()
            assertThat(result.malformedRows).isZero()
        }

        @Test
        fun `Dicollecte - la colonne Flexion peut être la première`() {
            val result = DicollecteReader.read("id\tFlexion\n0\tmot\n".lineSequence())

            assertThat(result.forms).containsExactly("mot")
        }

        @Test
        fun `SCOWL - niveau égal au maximum retenu, niveau juste au-dessus ignoré`() {
            val files = ArchiveFixtures.scowlFiles(
                "final/english-words.70" to "a\nb\n",
                "final/english-words.80" to "c\n\n  \nd\n",
            )

            val result = ScowlReader.read(files, 70)

            assertThat(result.entries.map { it.form }).containsExactly("a", "b")
            assertThat(result.skippedAboveLevel).isEqualTo(2)
            assertThat(ScowlReader.read(files, 69).entries).isEmpty()
            assertThat(ScowlReader.read(files, 80).entries).hasSize(4)
        }

        @Test
        fun `SCOWL - niveau à un chiffre ou trois chiffres`() {
            val files = ArchiveFixtures.scowlFiles("final/english-words.5" to "a\n", "final/english-words.100" to "b\n")

            assertThat(ScowlReader.read(files, 100).entries.map { it.level }).containsExactlyInAnyOrder(5, 100)
        }

        @Test
        fun `tar gz - archive sans entrée retenue`(@TempDir dir: Path) {
            val tgz = dir.resolve("a.tar.gz")
            ArchiveFixtures.tarGz(tgz, mapOf("d/skip.txt" to ByteArray(0)))

            assertThat(Archives.readTarGz(tgz) { false }).isEmpty()
            assertThat(Archives.readTarGz(tgz) { true }["d/skip.txt"]).isEmpty()
        }
    }

    private fun entry(key: String, value: Double) = java.util.AbstractMap.SimpleEntry(key, value)
}
