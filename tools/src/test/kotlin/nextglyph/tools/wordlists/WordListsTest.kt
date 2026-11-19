package nextglyph.tools.wordlists

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WordListsTest {

    private fun frequencies(result: WordListResult): Map<String, Long> = result.entries.associate { it.display to it.frequency }

    private fun counterOf(vararg sentences: List<String>): TokenCounter =
        TokenCounter().also { counter -> sentences.forEach(counter::addSentence) }

    @Nested
    inner class HappyPath {
        @Test
        fun `français - fréquence par million fois 100, plancher pour les formes hors Lexique`() {
            val result = FrenchWordList.build(
                dicollecte = listOf("le", "Paris", "paris", "l’", "a"),
                lexique = mapOf("le" to 12.34, "paris" to 10.0, "l'" to 5.0, "extra" to 0.004),
            )

            assertThat(frequencies(result)).containsExactlyInAnyOrderEntriesOf(
                mapOf("le" to 1234L, "paris" to 1000L, "Paris" to 1L, "l'" to 500L, "a" to 1L, "extra" to 1L),
            )
            assertThat(result.exclusions).isEmpty()
            assertThat(result.counters).containsEntry("formes Dicollecte", 5)
                .containsEntry("formes Lexique seul", 1).containsEntry("formes à fréquence plancher", 2)
        }

        @Test
        fun `français - sans variante minuscule, la forme capitalisée porte la fréquence`() {
            val result = FrenchWordList.build(listOf("Marseille"), mapOf("marseille" to 3.0))

            assertThat(frequencies(result)).containsOnly(java.util.AbstractMap.SimpleEntry("Marseille", 300L))
        }

        @Test
        fun `français - une forme de Lexique absente de Dicollecte est ajoutée`() {
            val result = FrenchWordList.build(listOf("chat"), mapOf("chat" to 2.0, "tchatche" to 1.0))

            assertThat(frequencies(result)).containsOnly(
                java.util.AbstractMap.SimpleEntry("chat", 200L), java.util.AbstractMap.SimpleEntry("tchatche", 100L),
            )
        }

        @Test
        fun `français séparé par licence - chaque liste ne contient que sa source`() {
            val split = FrenchWordList.split(
                dicollecte = listOf("le", "Paris", "l’", "a", "l'"),
                lexique = mapOf("le" to 12.34, "paris" to 10.0, "l'" to 5.0, "extra" to 0.004, "3d" to 1.0),
            )

            assertThat(frequencies(split.lexique)).containsExactlyInAnyOrderEntriesOf(
                mapOf("le" to 1234L, "paris" to 1000L, "l'" to 500L, "extra" to 1L),
            )
            assertThat(frequencies(split.dicollecte)).containsExactlyInAnyOrderEntriesOf(
                mapOf("le" to 1L, "Paris" to 1L, "l'" to 1L, "a" to 1L),
            )
            assertThat(split.lexique.exclusions.values.sum()).isEqualTo(1)
            assertThat(split.lexique.counters).containsEntry("formes Lexique", 5)
            assertThat(split.dicollecte.exclusions).containsEntry(FrenchWordList.EXCLUSION_DUPLICATE, 1)
            assertThat(split.dicollecte.counters).containsEntry("formes Dicollecte", 4)
        }

        @Test
        fun `français - conversion des fréquences`() {
            assertThat(FrenchWordList.scaled(1.0)).isEqualTo(100L)
            assertThat(FrenchWordList.scaled(12.5)).isEqualTo(1250L)
            assertThat(FrenchWordList.scaled(2632.369)).isEqualTo(263237L)
            assertThat(FrenchWordList.SCALE).isEqualTo(100.0)
            assertThat(FrenchWordList.FLOOR).isEqualTo(1L)
        }

        @Test
        fun `anglais - a priori par niveau`() {
            assertThat(listOf(10, 20, 35, 40, 50, 55, 60, 70, 80, 95).map(EnglishWordList::prior))
                .containsExactly(1000L, 500L, 176L, 125L, 62L, 44L, 31L, 15L, 7L, 2L)
        }

        @Test
        fun `anglais - comptage du corpus plus a priori, premier mot de phrase rattaché à la forme minuscule`() {
            val counter = counterOf(
                listOf("The", "cat", "sat"),
                listOf("I", "saw", "Tom", "and", "the", "cat"),
                listOf("Tom's", "hat"),
            )
            val scowl = listOf(
                ScowlEntry("the", 10, ScowlCategory.WORD),
                ScowlEntry("cat", 20, ScowlCategory.WORD),
                ScowlEntry("Tom", 40, ScowlCategory.WORD),
                ScowlEntry("Tom's", 50, ScowlCategory.WORD),
                ScowlEntry("dog", 60, ScowlCategory.WORD),
                ScowlEntry("it's", 10, ScowlCategory.CONTRACTION),
                ScowlEntry("ability's", 50, ScowlCategory.WORD),
            )

            val result = EnglishWordList.build(scowl, counter)

            assertThat(frequencies(result)).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "the" to 1002L, // 1 « the » + 1 « The » initial + a priori 1000
                    "cat" to 502L, // 2 + 500
                    "Tom" to 126L, // 1 + 125
                    "Tom's" to 63L, // 1 (initial) + 62
                    "dog" to 31L, // plancher du niveau 60
                    "it's" to 1000L, // contraction : jamais exclue
                ),
            )
            assertThat(result.exclusions).containsOnly(
                java.util.AbstractMap.SimpleEntry(EnglishWordList.EXCLUSION_POSSESSIVE, 1),
            )
            assertThat(result.counters).containsEntry("formes SCOWL", 7)
                .containsEntry("formes sans occurrence (plancher de niveau)", 2)
        }

        @Test
        fun `anglais - une forme capitalisée du lexique garde ses propres occurrences initiales`() {
            val counter = counterOf(listOf("Polish", "is"), listOf("polish", "it"))
            val scowl = listOf(ScowlEntry("polish", 50, ScowlCategory.WORD), ScowlEntry("Polish", 50, ScowlCategory.WORD))

            val result = EnglishWordList.build(scowl, counter)

            assertThat(frequencies(result)).containsOnly(
                java.util.AbstractMap.SimpleEntry("polish", 63L), java.util.AbstractMap.SimpleEntry("Polish", 63L),
            )
        }

        @Test
        fun `anglais - un doublon garde le niveau le plus courant`() {
            val scowl = listOf(ScowlEntry("the", 20, ScowlCategory.WORD), ScowlEntry("the", 10, ScowlCategory.WORD))

            val result = EnglishWordList.build(scowl, TokenCounter())

            assertThat(frequencies(result)).containsOnly(java.util.AbstractMap.SimpleEntry("the", 1000L))
            assertThat(result.exclusions).containsOnly(
                java.util.AbstractMap.SimpleEntry(EnglishWordList.EXCLUSION_DUPLICATE, 1),
            )
        }

        @Test
        fun `compteur - occurrences, occurrences initiales et total`() {
            val counter = counterOf(listOf("A", "b", "A"), listOf("A"))

            assertThat(counter.occurrences("A")).isEqualTo(3)
            assertThat(counter.initialOccurrences("A")).isEqualTo(2)
            assertThat(counter.initialOccurrences("b")).isZero()
            assertThat(counter.occurrences("b")).isEqualTo(1)
            assertThat(counter.totalTokens).isEqualTo(4)
        }

        @Test
        fun `tri de sortie - fréquence décroissante puis forme`() {
            val sorted = sortedForOutput(
                listOf(WordEntry("b", 5), WordEntry("a", 5), WordEntry("c", 9), WordEntry("B", 5)),
            )

            assertThat(sorted.map { it.display }).containsExactly("c", "B", "a", "b")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `français - formes hors alphabet, vides, en double comptées par motif`() {
            val result = FrenchWordList.build(
                dicollecte = listOf("ok", "ok", "x1", "€", "", "a b", "-", "ß"),
                lexique = emptyMap(),
            )

            assertThat(frequencies(result)).containsOnly(java.util.AbstractMap.SimpleEntry("ok", 1L))
            assertThat(result.exclusions).containsOnly(
                java.util.AbstractMap.SimpleEntry(FrenchWordList.EXCLUSION_DUPLICATE, 1),
                java.util.AbstractMap.SimpleEntry(WordFilter.Rejection.OUT_OF_ALPHABET.label, 3),
                java.util.AbstractMap.SimpleEntry(WordFilter.Rejection.NORMALIZER_REFUSED.label, 2),
                java.util.AbstractMap.SimpleEntry(WordFilter.Rejection.NO_LETTER.label, 1),
            )
        }

        @Test
        fun `français - fréquence nulle, négative ou illisible donne le plancher`() {
            assertThat(FrenchWordList.scaled(0.0)).isEqualTo(1L)
            assertThat(FrenchWordList.scaled(-5.0)).isEqualTo(1L)
            assertThat(FrenchWordList.scaled(Double.NaN)).isEqualTo(1L)
            assertThat(FrenchWordList.scaled(Double.NEGATIVE_INFINITY)).isEqualTo(1L)
        }

        @Test
        fun `anglais - formes hors alphabet exclues, jamais de fréquence nulle`() {
            val scowl = listOf(
                ScowlEntry("etc.", 70, ScowlCategory.ABBREVIATION),
                ScowlEntry("naïve", 50, ScowlCategory.WORD),
                ScowlEntry("3D", 50, ScowlCategory.WORD),
                ScowlEntry("ok", 95, ScowlCategory.WORD),
            )

            val result = EnglishWordList.build(scowl, TokenCounter())

            assertThat(frequencies(result)).containsOnly(
                java.util.AbstractMap.SimpleEntry("naïve", 62L), java.util.AbstractMap.SimpleEntry("ok", 2L),
            )
            assertThat(result.exclusions).containsOnly(
                java.util.AbstractMap.SimpleEntry(WordFilter.Rejection.OUT_OF_ALPHABET.label, 2),
            )
        }

        @Test
        fun `anglais - possessif jamais vu exclu, possessif vu conservé`() {
            val counter = counterOf(listOf("x", "dog's"))
            val scowl = listOf(ScowlEntry("dog's", 50, ScowlCategory.WORD), ScowlEntry("cat's", 50, ScowlCategory.WORD))

            val result = EnglishWordList.build(scowl, counter)

            assertThat(frequencies(result)).containsOnly(java.util.AbstractMap.SimpleEntry("dog's", 63L))
            assertThat(result.exclusions).containsOnly(
                java.util.AbstractMap.SimpleEntry(EnglishWordList.EXCLUSION_POSSESSIVE, 1),
            )
        }

        @Test
        fun `les motifs de rejet des exclusions sont tous comptés dans l'ordre d'apparition`() {
            val log = ExclusionLog()

            log.add("b")
            log.add("a")
            log.add("b")

            assertThat(log.asMap().entries.map { it.key to it.value }).containsExactly("b" to 2, "a" to 1)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `listes vides`() {
            val fr = FrenchWordList.build(emptyList(), emptyMap())
            val en = EnglishWordList.build(emptyList(), TokenCounter())

            assertThat(fr.entries).isEmpty()
            assertThat(en.entries).isEmpty()
            assertThat(fr.exclusions).isEmpty()
            assertThat(en.exclusions).isEmpty()
            val split = FrenchWordList.split(emptyList(), emptyMap())
            assertThat(split.lexique.entries).isEmpty()
            assertThat(split.dicollecte.entries).isEmpty()
        }

        @Test
        fun `français - fréquence énorme plafonnée à Int MAX, minuscule relevée à 1`() {
            val result = FrenchWordList.build(listOf("grand", "petit"), mapOf("grand" to 1e300, "petit" to 1e-9))

            assertThat(frequencies(result)).containsOnly(
                java.util.AbstractMap.SimpleEntry("grand", Int.MAX_VALUE.toLong()),
                java.util.AbstractMap.SimpleEntry("petit", 1L),
            )
            assertThat(MAX_FREQUENCY).isEqualTo(2147483647L)
        }

        @Test
        fun `français - arrondi à l'entier le plus proche`() {
            assertThat(FrenchWordList.scaled(0.014)).isEqualTo(1L)
            assertThat(FrenchWordList.scaled(0.016)).isEqualTo(2L)
            assertThat(FrenchWordList.scaled(0.026)).isEqualTo(3L)
            assertThat(FrenchWordList.scaled(0.024)).isEqualTo(2L)
        }

        @Test
        fun `filterEntries - fréquences ramenées entre 1 et Int MAX`() {
            val entries = filterEntries(listOf("a" to 0L, "b" to -4L, "c" to Long.MAX_VALUE, "d" to 7L), ExclusionLog())

            assertThat(entries.map { it.frequency }).containsExactly(1L, 1L, Int.MAX_VALUE.toLong(), 7L)
        }

        @Test
        fun `anglais - a priori minimal 1 pour un niveau très élevé, niveau 10 maximal`() {
            assertThat(EnglishWordList.prior(10)).isEqualTo(1000L)
            assertThat(EnglishWordList.prior(1000)).isEqualTo(1L)
            assertThat(EnglishWordList.prior(95)).isEqualTo(2L)
            assertThat(EnglishWordList.MAX_LEVEL).isEqualTo(70)
        }

        @Test
        fun `anglais - un seul caractère majuscule n'est pas rattaché à lui-même`() {
            val counter = counterOf(listOf("I", "am"))
            val scowl = listOf(ScowlEntry("I", 10, ScowlCategory.WORD))

            val result = EnglishWordList.build(scowl, counter)

            assertThat(frequencies(result)).containsOnly(java.util.AbstractMap.SimpleEntry("I", 1001L))
        }

        @Test
        fun `compteur vide`() {
            val counter = TokenCounter()

            counter.addSentence(emptyList())

            assertThat(counter.totalTokens).isZero()
            assertThat(counter.occurrences("x")).isZero()
        }

        @Test
        fun `tri de sortie - liste vide ou à un élément`() {
            assertThat(sortedForOutput(emptyList())).isEmpty()
            assertThat(sortedForOutput(listOf(WordEntry("x", 1)))).containsExactly(WordEntry("x", 1))
        }
    }
}
