package nextglyph.tools.wordlists

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CorpusTest {

    private fun sentences(count: Int) = (0 until count).map { Sentence(it.toLong(), "Phrase numéro $it.") }

    @Nested
    inner class HappyPath {
        @Test
        fun `lecture Tatoeba - identifiant, langue, texte`() {
            val result = TatoebaReader.read(sequenceOf("12\tfra\tBonjour.", "7\tfra\tSalut !"), Language.FR)

            assertThat(result.sentences).containsExactly(Sentence(12, "Bonjour."), Sentence(7, "Salut !"))
            assertThat(result.malformed).isZero()
            assertThat(result.otherLanguage).isZero()
        }

        @Test
        fun `le texte peut contenir des tabulations supplémentaires, conservées`() {
            val result = TatoebaReader.read(sequenceOf("1\teng\ta\tb"), Language.EN)

            assertThat(result.sentences).containsExactly(Sentence(1, "a\tb"))
        }

        @Test
        fun `mix - valeur de référence SplitMix64`() {
            assertThat(SentencePartition.mix(0)).isEqualTo(-2152535657050944081L)
            assertThat(SentencePartition.mix(1)).isEqualTo(-7995527694508729151L)
        }

        @Test
        fun `partition - part de test de 10 pour cent, valeurs figées`() {
            val tests = (0L until 10000L).filter(SentencePartition::isTest)

            assertThat(tests).hasSize(994)
            assertThat(tests.take(6)).containsExactly(8L, 12L, 55L, 74L, 81L, 84L)
            assertThat(SentencePartition.TEST_PERCENT).isEqualTo(10)
        }

        @Test
        fun `partition - sans fuite, sans perte, stable et indépendante de l'ordre`() {
            val input = sentences(2000)

            val first = CorpusSplitter.split(input)
            val again = CorpusSplitter.split(input)
            val reversed = CorpusSplitter.split(input.reversed())

            assertThat(first).isEqualTo(again).isEqualTo(reversed)
            assertThat(first.train.toSet() intersect first.test.toSet()).isEmpty()
            assertThat(first.train.size + first.test.size).isEqualTo(2000)
            assertThat(first.test.size).isBetween(150, 250)
            assertThat(first.test).allMatch { text -> SentencePartition.isTest(text.substringAfter("numéro ").removeSuffix(".").toLong()) }
        }

        @Test
        fun `les phrases sortent triées par identifiant`() {
            val split = CorpusSplitter.split(listOf(Sentence(30, "c"), Sentence(10, "a"), Sentence(20, "b")))

            assertThat(split.train + split.test).containsExactlyInAnyOrder("a", "b", "c")
            assertThat(split.train).isSortedAccordingTo(compareBy<String> { it })
        }

        @Test
        fun `corpus mixte - blocs alternés de taille variable, français d'abord`() {
            val fr = (0 until 200).map { "fr$it" }
            val en = (0 until 200).map { "en$it" }

            val mixed = MixedCorpus.interleave(fr, en)

            assertThat(mixed.sentences).hasSameSizeAs(mixed.languages)
            assertThat(mixed.languages.first()).isEqualTo(Language.FR)
            assertThat(mixed.sentences.filter { it.startsWith("fr") }).isEqualTo(fr.take(mixed.languages.count { it == Language.FR }))
            assertThat(mixed.sentences.filter { it.startsWith("en") }).isEqualTo(en.take(mixed.languages.count { it == Language.EN }))
            assertThat(mixed.languages.toSet()).containsExactlyInAnyOrder(Language.FR, Language.EN)
        }

        @Test
        fun `corpus mixte - les tailles de blocs varient entre 1 et la taille maximale`() {
            val fr = (0 until 400).map { "fr$it" }
            val en = (0 until 400).map { "en$it" }

            val mixed = MixedCorpus.interleave(fr, en)

            val sizes = mutableListOf<Int>()
            var run = 1
            for (i in 1 until mixed.languages.size) {
                if (mixed.languages[i] == mixed.languages[i - 1]) run++ else { sizes += run; run = 1 }
            }
            assertThat(sizes.toSet().size).isGreaterThan(3)
            assertThat(sizes.max()).isLessThanOrEqualTo(MixedCorpus.MAX_BLOCK)
            assertThat(sizes.min()).isGreaterThanOrEqualTo(1)
            assertThat(sizes.max()).isEqualTo(MixedCorpus.MAX_BLOCK)
        }

        @Test
        fun `corpus mixte - tailles des premiers blocs figées pour la graine par défaut`() {
            val fr = (0 until 200).map { "fr$it" }
            val en = (0 until 200).map { "en$it" }

            val mixed = MixedCorpus.interleave(fr, en)

            val sizes = mutableListOf<Int>()
            var run = 1
            for (i in 1 until mixed.languages.size) {
                if (mixed.languages[i] == mixed.languages[i - 1]) run++ else { sizes += run; run = 1 }
            }
            assertThat(sizes.take(8)).containsExactly(4, 1, 1, 3, 2, 6, 2, 3)
            assertThat(mixed.sentences.take(5)).containsExactly("fr0", "fr1", "fr2", "fr3", "en0")
        }

        @Test
        fun `corpus mixte - reproductible, et la graine change le découpage`() {
            val fr = (0 until 100).map { "fr$it" }
            val en = (0 until 100).map { "en$it" }

            assertThat(MixedCorpus.interleave(fr, en)).isEqualTo(MixedCorpus.interleave(fr, en))
            assertThat(MixedCorpus.interleave(fr, en, seed = 1).sentences)
                .isNotEqualTo(MixedCorpus.interleave(fr, en, seed = 2).sentences)
        }

        @Test
        fun `écriture - UTF-8, fins de ligne LF, dossiers parents créés`(@TempDir dir: Path) {
            val target = dir.resolve("a/b/out.txt")

            writeLines(target, listOf("été", "x"))

            assertThat(Files.readAllBytes(target)).isEqualTo("été\nx\n".toByteArray(Charsets.UTF_8))
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `lignes mal formées et autre langue sont comptées et ignorées`() {
            val lines = sequenceOf(
                "1\tfra\tBon",
                "x\tfra\tid non numérique",
                "-5\tfra\tid négatif",
                "3\tfra",
                "4\tfra\t   ",
                "5\teng\tHello",
                "",
                "   ",
                "6\tfra\tOk",
            )

            val result = TatoebaReader.read(lines, Language.FR)

            assertThat(result.sentences.map { it.id }).containsExactly(1L, 6L)
            assertThat(result.malformed).isEqualTo(4)
            assertThat(result.otherLanguage).isEqualTo(1)
        }

        @Test
        fun `les doublons exacts de texte sont écartés, y compris à espaces différents`() {
            val split = CorpusSplitter.split(
                listOf(Sentence(1, "Bonjour  le monde"), Sentence(2, "Bonjour le monde"), Sentence(3, "Bonjour\tle monde")),
            )

            assertThat(split.train.size + split.test.size).isEqualTo(1)
            assertThat(split.duplicates).isEqualTo(2)
        }

        @Test
        fun `un doublon ne peut pas fuir de l'entraînement vers le test`() {
            val ids = (0L until 400L)
            val input = ids.map { Sentence(it, "même phrase ${it % 50}") }

            val split = CorpusSplitter.split(input)

            assertThat(split.train.toSet() intersect split.test.toSet()).isEmpty()
            assertThat(split.train.size + split.test.size).isEqualTo(50)
        }

        @Test
        fun `corpus mixte - une langue vide s'arrête après le premier bloc français`() {
            val fr = (0 until 30).map { "fr$it" }

            val mixed = MixedCorpus.interleave(fr, emptyList())

            assertThat(mixed.sentences).isNotEmpty().allMatch { it.startsWith("fr") }
            assertThat(mixed.sentences.size).isBetween(1, MixedCorpus.MAX_BLOCK)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `fichier vide`() {
            val result = TatoebaReader.read(emptySequence(), Language.EN)

            assertThat(result.sentences).isEmpty()
            assertThat(result.malformed).isZero()
            assertThat(CorpusSplitter.split(emptyList())).isEqualTo(CorpusSplit(emptyList(), emptyList(), 0))
        }

        @Test
        fun `identifiant zéro accepté, identifiant maximal accepté`() {
            val result = TatoebaReader.read(sequenceOf("0\teng\tA", "9223372036854775807\teng\tB"), Language.EN)

            assertThat(result.sentences.map { it.id }).containsExactly(0L, Long.MAX_VALUE)
        }

        @Test
        fun `partition - un résidu de 9 est du test, un résidu de 10 est de l'entraînement`() {
            assertThat(SentencePartition.isTest(473)).isTrue()
            assertThat(Math.floorMod(SentencePartition.mix(473), 100L)).isEqualTo(9L)
            assertThat(SentencePartition.isTest(45)).isFalse()
            assertThat(Math.floorMod(SentencePartition.mix(45), 100L)).isEqualTo(10L)
        }

        @Test
        fun `partition - identifiants négatifs sans exception`() {
            assertThat(SentencePartition.isTest(-1)).isIn(true, false)
            assertThat(Math.floorMod(SentencePartition.mix(Long.MIN_VALUE), 100L)).isBetween(0L, 99L)
        }

        @Test
        fun `une seule phrase`() {
            val split = CorpusSplitter.split(listOf(Sentence(8, "Seule.")))

            assertThat(split.test).containsExactly("Seule.")
            assertThat(split.train).isEmpty()
        }

        @Test
        fun `corpus mixte - listes vides`() {
            assertThat(MixedCorpus.interleave(emptyList(), emptyList()).sentences).isEmpty()
        }

        @Test
        fun `corpus mixte - s'arrête quand la langue dont c'est le tour est épuisée`() {
            val mixed = MixedCorpus.interleave(listOf("fr0"), (0 until 50).map { "en$it" })

            assertThat(mixed.languages.count { it == Language.FR }).isEqualTo(1)
            assertThat(mixed.sentences.first()).isEqualTo("fr0")
            assertThat(mixed.sentences.size).isBetween(2, 1 + MixedCorpus.MAX_BLOCK)
        }

        @Test
        fun `écriture d'une liste vide - fichier vide`(@TempDir dir: Path) {
            writeLines(dir.resolve("vide.txt"), emptyList())

            assertThat(Files.size(dir.resolve("vide.txt"))).isZero()
        }
    }
}
