package nextglyph.engine.lexicon

import nextglyph.engine.text.TextNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

class LexiconCompletionsTest {

    private val sample = LexiconFixtures.sample()

    private fun forms(entries: List<LexiconEntry>) = entries.map { it.form }

    /** Complétions attendues, par parcours exhaustif. */
    private fun bruteForce(entries: List<Pair<String, Long>>, prefix: String, limit: Int): List<LexiconEntry> {
        val merged = entries.groupBy { it.first }.map { (form, list) -> form to list.sumOf { it.second } }
        return merged
            .map { (form, frequency) -> LexiconEntry(form, TextNormalizer.normalize(form), frequency) }
            .filter { it.key.startsWith(prefix) }
            .sortedWith(compareByDescending<LexiconEntry> { it.frequency }.thenBy { it.key }.thenBy { it.form })
            .take(limit)
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `renvoie les meilleures complétions par fréquence décroissante`() {
            val result = sample.completions("", 3)

            assertThat(result).containsExactly(
                LexiconEntry("a", "a", 50),
                LexiconEntry("chat", "chat", 40),
                LexiconEntry("à", "a", 30),
            )
        }

        @Test
        fun `inclut le préfixe lui-même s'il est un mot`() {
            assertThat(forms(sample.completions("chat", 10))).containsExactly("chat", "chats")
        }

        @Test
        fun `complète depuis un nœud interne ou le milieu d'une étiquette`() {
            assertThat(forms(sample.completions("ch", 10))).containsExactly("chat", "chien", "chats")
            assertThat(forms(sample.completions("c", 10))).containsExactly("chat", "chien", "chats")
            assertThat(forms(sample.completions("peut-e", 10))).containsExactly("peut-être")
            assertThat(forms(sample.completions("a", 10))).containsExactly("a", "à", "abri", "aujourd'hui")
        }

        @Test
        fun `renvoie toutes les formes quand la limite dépasse leur nombre`() {
            assertThat(forms(sample.completions("", 100))).containsExactly(
                "a", "chat", "à", "chien", "peut-être", "abri", "aujourd'hui", "chats",
            )
        }

        @Test
        fun `renvoie les formes d'une clé par fréquence décroissante`() {
            assertThat(sample.displayForms("a")).containsExactly(LexiconEntry("a", "a", 50), LexiconEntry("à", "a", 30))
            assertThat(sample.displayForms("chat")).containsExactly(LexiconEntry("chat", "chat", 40))
        }

        @Test
        fun `coïncide avec un parcours exhaustif sur un lexique synthétique`() {
            val entries = SyntheticLexicon.entries(3_000, seed = 3)
            val lexicon = LexiconFixtures.lexicon(entries)
            val random = Random(5)

            repeat(400) {
                val key = TextNormalizer.normalize(entries.random(random).first)
                val prefix = key.substring(0, random.nextInt(key.length + 1))
                val limit = random.nextInt(1, 12)

                assertThat(lexicon.completions(prefix, limit)).isEqualTo(bruteForce(entries, prefix, limit))
            }
        }

        @Test
        fun `départage les fréquences égales par clé puis par forme`() {
            val lexicon = LexiconFixtures.lexicon(listOf("b" to 5L, "ab" to 5L, "ac" to 1L, "là" to 5L, "la" to 5L))

            assertThat(forms(lexicon.completions("", 4))).containsExactly("ab", "b", "la", "là")
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest(name = "limite {0}")
        @ValueSource(ints = [-1, Int.MIN_VALUE])
        fun `refuse une limite négative`(limit: Int) {
            assertThatIllegalArgumentException()
                .isThrownBy { sample.completions("a", limit) }
                .withMessageContaining("$limit")
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["x", "chx", "chatsx", "A", "é"])
        fun `un préfixe inconnu n'a aucune complétion`(prefix: String) {
            assertThat(sample.completions(prefix, 5)).isEmpty()
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["", "c", "ch", "ab", "chatons", "x", "Chat", "à"])
        fun `une clé absente, partielle ou non normalisée n'a aucune forme`(key: String) {
            assertThat(sample.displayForms(key)).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une limite nulle ne renvoie rien`() {
            assertThat(sample.completions("", 0)).isEmpty()
        }

        @Test
        fun `une limite de 1 renvoie la meilleure forme`() {
            assertThat(sample.completions("c", 1)).containsExactly(LexiconEntry("chat", "chat", 40))
        }

        @Test
        fun `un lexique vide n'a aucune complétion`() {
            val empty = LexiconBuilder("fr").build()

            assertThat(empty.completions("", 10)).isEmpty()
            assertThat(empty.displayForms("a")).isEmpty()
        }

        @Test
        fun `un mot sans continuation se complète lui-même`() {
            assertThat(sample.completions("chats", 5)).containsExactly(LexiconEntry("chats", "chats", 10))
        }

        @Test
        fun `les fréquences extrêmes sont restituées exactement`() {
            val max = Long.MAX_VALUE
            val lexicon = LexiconFixtures.lexicon(listOf("aa" to max - 1, "ab" to max, "a" to 1L))

            assertThat(lexicon.completions("a", 3)).containsExactly(
                LexiconEntry("ab", "ab", max),
                LexiconEntry("aa", "aa", max - 1),
                LexiconEntry("a", "a", 1),
            )
        }

        @Test
        fun `une clé de longueur maximale se complète et se retrouve`() {
            val key = "oe".repeat(64)
            val lexicon = LexiconFixtures.lexicon(listOf("Œ".repeat(64) to 2L, "oe" to 1L))

            assertThat(lexicon.completions("oe", 5).map { it.key }).containsExactly(key, "oe")
            assertThat(lexicon.displayForms(key).single().form).isEqualTo("Œ".repeat(64))
        }

        @Test
        fun `la limite maximale renvoie toutes les formes`() {
            assertThat(sample.completions("", Int.MAX_VALUE)).hasSize(8)
        }
    }
}
