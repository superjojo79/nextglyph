package nextglyph.engine.ngram

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NgramModelBuilderTest {

    private fun texts(model: NgramModel, vararg context: String, limit: Int = 10) =
        model.next(context.toList(), limit).map { it.text }

    @Nested
    inner class HappyPath {
        @Test
        fun `le fichier produit est identique à chaque construction`() {
            val builder = NgramFixtures.builder()

            assertThat(builder.build()).isEqualTo(builder.build())
            assertThat(NgramFixtures.bytes()).isEqualTo(NgramFixtures.bytes())
        }

        @Test
        fun `l'ordre d'ajout des phrases ne change pas le fichier`() {
            val forward = NgramFixtures.bytes(NgramFixtures.FRENCH)
            val backward = NgramFixtures.bytes(NgramFixtures.FRENCH.reversed())

            assertThat(forward).isEqualTo(backward)
        }

        @Test
        fun `une phrase répétée compte autant d'occurrences`() {
            val once = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL)
            repeat(3) { once.addSentence(listOf("a", "b")) }
            val times = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL).apply { addSentence(listOf("a", "b"), 3) }

            assertThat(times.build()).isEqualTo(once.build())
            assertThat(times.tokenCount).isEqualTo(6)
            assertThat(times.sentenceCount).isEqualTo(3)
        }

        @Test
        fun `les formes de même clé sont fusionnées, la plus fréquente est affichée`() {
            val model = NgramFixtures.model(listOf("à ou" to 3, "a ou" to 1, "ou où" to 1))

            // « ou » et « où » ont la même clé : la forme la plus fréquente s'affiche.
            assertThat(texts(model, "ou")).containsExactly("ou")
            assertThat(texts(model, "a")).containsExactly("ou")
            assertThat(texts(model, "à")).containsExactly("ou")
            assertThat(texts(model)).containsExactly("à", "ou")
        }

        @Test
        fun `à fréquence égale, la première forme dans l'ordre alphabétique est affichée`() {
            val model = NgramFixtures.model(listOf("b é" to 1, "b e" to 1))

            assertThat(texts(model, "b")).containsExactly("e")
        }

        @Test
        fun `les suivants non proposables restent des contextes et comptent dans les probabilités`() {
            val model = NgramFixtures.model(
                listOf("je suis Tom" to 3, "je suis là" to 1, "Tom est là" to 2),
                predictable = { it != "Tom" },
            )

            val words = model.next(listOf("je", "suis"), 3)

            assertThat(words.map { it.text }).containsExactly("là")
            assertThat(words[0].probability).isLessThan(0.3)
            assertThat(texts(model, "tom")).containsExactly("est")
            assertThat(model.contains("Tom")).isTrue()
            assertThat(texts(model)).doesNotContain("Tom")
        }

        @Test
        fun `les comptes du vocabulaire gardent les mots les plus fréquents`() {
            val sentences = listOf("a b" to 10, "a c" to 5, "a d" to 1)
            val model = NgramFixtures.model(sentences, pruning = NgramFixtures.KEEP_ALL.copy(maxVocabulary = 3))

            assertThat(model.vocabularySize).isEqualTo(3)
            assertThat(model.contains("a")).isTrue()
            assertThat(model.contains("b")).isTrue()
            assertThat(model.contains("c")).isTrue()
            assertThat(model.contains("d")).isFalse()
            assertThat(texts(model, "a")).containsExactly("b", "c")
        }

        @Test
        fun `un mot hors vocabulaire compte dans les totaux sans être proposé`() {
            val model = NgramFixtures.model(
                listOf("x y z" to 4, "y z" to 1, "x y w" to 4),
                pruning = NgramFixtures.KEEP_ALL.copy(maxVocabulary = 3),
            )

            assertThat(model.contains("w")).isFalse()
            val words = model.next(listOf("x", "y"), 5)
            assertThat(words.map { it.text }).containsExactly("z")
            assertThat(words[0].probability).isBetween(0.45, 0.55)
        }

        @Test
        fun `les mots inconnus cassent les contextes et ne sont jamais proposés`() {
            val model = NgramFixtures.model(listOf("je 3D suis" to 3, "je suis là" to 1))

            assertThat(model.contains("3D")).isFalse()
            assertThat(texts(model, "je")).containsExactly("suis")
            assertThat(texts(model, "suis")).containsExactly("là")
        }

        @Test
        fun `les compteurs de la construction`() {
            val builder = NgramFixtures.builder(listOf("a b c" to 2, "d" to 1))
            builder.addSentence(emptyList())

            assertThat(builder.tokenCount).isEqualTo(7)
            assertThat(builder.sentenceCount).isEqualTo(3)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une étiquette de langue invalide est refusée`() {
            assertThatThrownBy { NgramModelBuilder("1x") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Étiquette de langue invalide")
            assertThatThrownBy { NgramModelBuilder("") }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `un nombre de répétitions nul ou négatif est refusé`() {
            val builder = NgramModelBuilder("fr")

            assertThatThrownBy { builder.addSentence(listOf("a"), 0) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("répétitions")
            assertThatThrownBy { builder.addSentence(listOf("a"), -1) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `une phrase vide est ignorée`() {
            val builder = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL)

            builder.addSentence(emptyList())

            assertThat(builder.tokenCount).isZero()
            assertThat(builder.sentenceCount).isZero()
        }

        @Test
        fun `des formes invalides sont tenues pour des mots inconnus sans exception`() {
            val builder = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL)

            builder.addSentence(listOf("", "a b", "x1", "ß", "ok", "x".repeat(70)))
            val model = NgramModel.load(java.nio.ByteBuffer.wrap(builder.build()))

            assertThat(model.vocabularySize).isEqualTo(1)
            assertThat(model.contains("ok")).isTrue()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un bigramme à exactement le seuil est gardé, un de moins est élagué`() {
            val pruning = NgramFixtures.KEEP_ALL.copy(minBigramCount = 3)
            val model = NgramFixtures.model(listOf("a b" to 3, "a c" to 2), pruning = pruning)

            assertThat(texts(model, "a")).containsExactly("b")
        }

        @Test
        fun `un trigramme à exactement le seuil est gardé, un de moins est élagué`() {
            val sentences = listOf("x a b" to 3, "y a c" to 3, "y a b" to 2)

            val count = { min: Int -> NgramFixtures.model(sentences, pruning = NgramFixtures.KEEP_ALL.copy(minTrigramCount = min)).trigramCount }

            // Contextes : (début x) a:3, (début y) a:5, (x a) b:3, (y a) c:3 et b:2.
            assertThat(count(2)).isEqualTo(5)
            assertThat(count(3)).isEqualTo(4)
            assertThat(count(4)).isEqualTo(1)
            assertThat(count(5)).isEqualTo(1)
            assertThat(count(6)).isZero()
        }

        @Test
        fun `le nombre de suivants est limité exactement`() {
            val sentences = (1..9).map { "a w${('a' + it)}" to (20 - it) }
            val limited = NgramFixtures.model(sentences, pruning = NgramFixtures.KEEP_ALL.copy(maxBigramFollowers = 8))
            val roomy = NgramFixtures.model(sentences, pruning = NgramFixtures.KEEP_ALL.copy(maxBigramFollowers = 9))

            assertThat(texts(limited, "a")).hasSize(8).doesNotContain("wj")
            assertThat(texts(roomy, "a")).hasSize(9).contains("wj")
            assertThat(texts(limited, "a")).startsWith("wb", "wc")
        }

        @Test
        fun `le nombre de suivants de trigramme est limité exactement`() {
            val sentences = (1..5).map { "x a w${('a' + it)}" to (20 - it) }
            val pruning = NgramFixtures.KEEP_ALL.copy(maxTrigramFollowers = 4, maxBigramFollowers = 1)

            val model = NgramFixtures.model(sentences, pruning = pruning)

            // (début x) a, plus quatre des cinq suivants de (x a).
            assertThat(model.trigramCount).isEqualTo(1 + 4)
            assertThat(texts(model, "x", "a")).containsExactly("wb", "wc", "wd", "we")
        }

        @Test
        fun `un trigramme qui n'apprend rien de plus que le bigramme est élagué`() {
            val sentences = listOf("x a b" to 5, "y a b" to 5, "x a c" to 1, "y a c" to 1)

            val strict = NgramFixtures.model(sentences, pruning = NgramFixtures.KEEP_ALL.copy(minTrigramGain = 1.5))
            val neutral = NgramFixtures.model(sentences, pruning = NgramFixtures.KEEP_ALL.copy(minTrigramGain = 1.0))

            assertThat(strict.trigramCount).isZero()
            assertThat(neutral.trigramCount).isEqualTo(6)
        }

        @Test
        fun `un trigramme plus informatif que le bigramme est gardé`() {
            val sentences = listOf("x a b" to 9, "x a c" to 1, "y a c" to 9, "y a b" to 1)
            val pruning = NgramFixtures.KEEP_ALL.copy(minTrigramGain = 1.5)

            val model = NgramFixtures.model(sentences, pruning = pruning)

            assertThat(model.next(listOf("x", "a"), 1)[0].text).isEqualTo("b")
            assertThat(model.next(listOf("y", "a"), 1)[0].text).isEqualTo("c")
            assertThat(model.trigramContextCount).isEqualTo(2)
            assertThat(model.trigramCount).isEqualTo(2)
        }

        @Test
        fun `le vocabulaire maximal est accepté`() {
            val builder = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL.copy(maxVocabulary = 2))
            builder.addSentence(listOf("a", "b", "c"))

            val model = NgramModel.load(java.nio.ByteBuffer.wrap(builder.build()))

            assertThat(model.vocabularySize).isEqualTo(2)
        }

        @Test
        fun `une clé de 64 caractères est acceptée, de 65 elle devient un mot inconnu`() {
            val builder = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL)

            builder.addSentence(listOf("a".repeat(64), "a".repeat(65), "b"))
            val model = NgramModel.load(java.nio.ByteBuffer.wrap(builder.build()))

            assertThat(model.vocabularySize).isEqualTo(2)
            assertThat(model.contains("a".repeat(64))).isTrue()
            assertThat(model.contains("a".repeat(65))).isFalse()
        }

        @Test
        fun `une étiquette de langue régionale est acceptée`() {
            val model = NgramFixtures.model(tag = "en-GB")

            assertThat(model.languageTag).isEqualTo("en-GB")
        }
    }
}
