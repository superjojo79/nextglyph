package nextglyph.engine.pack

import nextglyph.engine.lexicon.LexiconFormat
import nextglyph.engine.ngram.NgramFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PackManifestTest {

    private val sha = "a".repeat(64)

    private fun entries(): List<PackEntry> = PackFormat.PACKED_FILES.mapIndexed { i, name -> PackEntry(name, 100L + i, sha) }

    private fun manifest() = PackManifest(202611241830, 1, 2, entries())

    private fun text(
        format: String = "format=1",
        version: String = "version=202611241830",
        lexicon: String = "lexiconFormat=1",
        ngram: String = "ngramFormat=2",
        files: List<String> = entries().map { "file=${it.name} ${it.size} $sha" },
    ): String = (listOf(format, version, lexicon, ngram) + files).joinToString("\n", postfix = "\n")

    private fun assertCorrupt(text: String) {
        assertThatThrownBy { PackManifest.parse(text.toByteArray()) }
            .isInstanceOfSatisfying(PackException::class.java) { assertThat(it.reason).isEqualTo(PackException.Reason.CORRUPT) }
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `sérialise au format exact du paquet`() {
            val expected = "format=1\nversion=202611241830\nlexiconFormat=1\nngramFormat=2\n" +
                "file=fr-lexique.nglx 100 $sha\nfile=fr-dicollecte.nglx 101 $sha\nfile=en.nglx 102 $sha\n" +
                "file=fr.ngrm 103 $sha\nfile=en.ngrm 104 $sha\nfile=NOTICE.txt 105 $sha\n"

            assertThat(String(manifest().serialize(), Charsets.UTF_8)).isEqualTo(expected)
        }

        @Test
        fun `range les fichiers dans l'ordre de PACKED_FILES à la sérialisation`() {
            val shuffled = PackManifest(1, 1, 2, entries().reversed())

            assertThat(shuffled.serialize()).isEqualTo(PackManifest(1, 1, 2, entries()).serialize())
        }

        @Test
        fun `relit ce qu'il a sérialisé`() {
            assertThat(PackManifest.parse(manifest().serialize())).isEqualTo(manifest())
        }

        @Test
        fun `retrouve une entrée par son nom`() {
            assertThat(manifest().entry("en.ngrm")).isEqualTo(PackEntry("en.ngrm", 104, sha))
        }

        @Test
        fun `crée un manifeste avec les formats du moteur`() {
            val created = PackManifest.create(5, entries())

            assertThat(created).isEqualTo(PackManifest(5, LexiconFormat.VERSION, NgramFormat.VERSION, entries()))
            assertThat(created.compatible).isTrue()
        }

        @Test
        fun `décrit un fichier par sa taille et son empreinte`() {
            val file = java.io.File.createTempFile("pack", ".bin").apply { writeText("abc"); deleteOnExit() }

            assertThat(PackEntry.of("x", file))
                .isEqualTo(PackEntry("x", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        }

        @Test
        fun `est compatible avec les formats du moteur`() {
            assertThat(PackManifest(1, LexiconFormat.VERSION, NgramFormat.VERSION, entries()).compatible).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `ignore une entrée inconnue`() {
            assertThat(manifest().entry("autre.bin")).isNull()
        }

        @Test
        fun `n'est pas compatible si le format du lexique diffère`() {
            assertThat(PackManifest(1, LexiconFormat.VERSION + 1, NgramFormat.VERSION, entries()).compatible).isFalse()
        }

        @Test
        fun `n'est pas compatible si le format des modèles diffère`() {
            assertThat(PackManifest(1, LexiconFormat.VERSION, NgramFormat.VERSION + 1, entries()).compatible).isFalse()
        }

        @Test
        fun `refuse un texte qui n'est pas de l'UTF-8`() {
            assertThatThrownBy { PackManifest.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
                .isInstanceOf(PackException::class.java).hasMessageContaining("UTF-8")
        }

        @Test
        fun `refuse l'absence de saut de ligne final`() = assertCorrupt(text().dropLast(1))

        @Test
        fun `refuse un format inconnu`() = assertCorrupt(text(format = "format=2"))

        @Test
        fun `refuse des clés dans le désordre`() = assertCorrupt(text(version = "ngramFormat=2", ngram = "version=202611241830"))

        @Test
        fun `refuse une version non numérique`() = assertCorrupt(text(version = "version=abc"))

        @Test
        fun `refuse une version à signe`() = assertCorrupt(text(version = "version=-5"))

        @Test
        fun `refuse une version nulle`() = assertCorrupt(text(version = "version=0"))

        @Test
        fun `refuse une clé sans le signe égal`() = assertCorrupt(text(version = "version202611241830"))

        @Test
        fun `refuse un format de lexique trop grand`() = assertCorrupt(text(lexicon = "lexiconFormat=2147483648"))

        @Test
        fun `refuse des fins de ligne CRLF`() = assertCorrupt(text().replace("\n", "\r\n"))

        @Test
        fun `refuse une ligne de fichier mal formée`() =
            assertCorrupt(text(files = entries().map { "file=${it.name} ${it.size}" }))

        @Test
        fun `refuse une ligne qui ne commence pas par file`() =
            assertCorrupt(text(files = entries().map { "fichier=${it.name} ${it.size} $sha" }))

        @Test
        fun `refuse une empreinte en majuscules`() =
            assertCorrupt(text(files = entries().map { "file=${it.name} ${it.size} ${sha.uppercase()}" }))

        @Test
        fun `refuse une empreinte trop courte`() =
            assertCorrupt(text(files = entries().map { "file=${it.name} ${it.size} ${sha.drop(1)}" }))

        @Test
        fun `refuse une taille négative`() =
            assertCorrupt(text(files = entries().map { "file=${it.name} -1 $sha" }))

        @Test
        fun `refuse un fichier listé deux fois`() {
            val files = entries().map { "file=${it.name} ${it.size} $sha" }

            assertCorrupt(text(files = files + files.first()))
        }

        @Test
        fun `refuse un fichier manquant`() = assertCorrupt(text(files = entries().drop(1).map { "file=${it.name} ${it.size} $sha" }))

        @Test
        fun `refuse un fichier inconnu`() {
            val files = entries().map { "file=${it.name} ${it.size} $sha" }.toMutableList()
            files[0] = "file=../evil 1 $sha"

            assertCorrupt(text(files = files))
        }

        @Test
        fun `refuse une ligne en trop à la fin`() {
            assertCorrupt(text(files = entries().map { "file=${it.name} ${it.size} $sha" } + "x=1"))
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte une version de dix-huit chiffres`() {
            val parsed = PackManifest.parse(text(version = "version=999999999999999999").toByteArray())

            assertThat(parsed.version).isEqualTo(999_999_999_999_999_999L)
        }

        @Test
        fun `refuse une version de dix-neuf chiffres`() = assertCorrupt(text(version = "version=1000000000000000000"))

        @Test
        fun `accepte une version égale à un`() {
            assertThat(PackManifest.parse(text(version = "version=1").toByteArray()).version).isEqualTo(1)
        }

        @Test
        fun `accepte le plus grand format de lexique`() {
            assertThat(PackManifest.parse(text(lexicon = "lexiconFormat=2147483647").toByteArray()).lexiconFormat).isEqualTo(Int.MAX_VALUE)
        }

        @Test
        fun `accepte une taille nulle`() {
            val files = entries().map { "file=${it.name} 0 $sha" }

            assertThat(PackManifest.parse(text(files = files).toByteArray()).files.map { it.size }).containsOnly(0L)
        }

        @Test
        fun `refuse un texte vide`() = assertCorrupt("")

        @Test
        fun `accepte les fichiers dans un autre ordre`() {
            val parsed = PackManifest.parse(text(files = entries().reversed().map { "file=${it.name} ${it.size} $sha" }).toByteArray())

            assertThat(parsed.files).hasSize(6)
        }

        @Test
        fun `lit un nom de fichier non ASCII comme inconnu`() {
            val files = entries().map { "file=${it.name} ${it.size} $sha" }.toMutableList()
            files[0] = "file=é.nglx 1 $sha"

            assertCorrupt(text(files = files))
        }
    }
}
