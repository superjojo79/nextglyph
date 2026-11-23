package nextglyph.engine.predict

import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DisplayFormsTest {

    private val display = DisplayForms(0.95)
    private val fr = LexiconSet(
        LexiconFixtures.lexicon(
            listOf(
                "école" to 1000L, "écolé" to 3L, "a" to 50L, "à" to 50L, "mère" to 990L, "mere" to 10L,
                "café" to 30L, "daV" to 5L, "paris" to 20L, "Paris" to 1L, "pécher" to 10L, "pêcher" to 30L,
            ),
            "fr",
        ),
    )
    private val en = LexiconSet(LexiconFixtures.lexicon(listOf("café" to 10L, "mere" to 10L, "I" to 100L, "i" to 1L, "McDonald" to 5L), "en"))

    /** Français de masse 10 000 où [word] pèse 100 (donc 1 %). */
    private fun bilingualFrench(word: String) = LexiconSet(LexiconFixtures.lexicon(listOf(word to 100L, "x" to 9900L), "fr"))

    /** Anglais de masse 1 000 où [word] pèse 100 (donc 10 %). */
    private fun bilingualEnglish(word: String) = LexiconSet(LexiconFixtures.lexicon(listOf(word to 100L, "y" to 900L), "en"))

    @Nested
    inner class HappyPath {
        @ParameterizedTest(name = "« {0} » → « {1} »")
        @CsvSource(
            "ecole, école",
            "Ecole, École",
            "ECOLE, ÉCOLE",
            "mere, mère",
            "cafe, café",
            "a, a",
            "paris, paris",
            "pecher, pecher",
            "dav, dav",
        )
        fun `mot tapé, français seul`(raw: String, expected: String) {
            assertThat(display.forTypedWord(raw, raw.lowercase(), listOf(fr), fr)).isEqualTo(expected)
        }

        @Test
        fun `bilingue - les lexiques doivent s'accorder`() {
            assertThat(display.forTypedWord("cafe", "cafe", listOf(fr, en), fr)).isEqualTo("café")
            assertThat(display.forTypedWord("mere", "mere", listOf(fr, en), fr)).isEqualTo("mere")
        }

        @Test
        fun `casse - langue de la phrase d'abord`() {
            assertThat(display.forTypedWord("i", "i", listOf(en), en)).isEqualTo("I")
            assertThat(display.forTypedWord("mcdonald", "mcdonald", listOf(en), en)).isEqualTo("McDonald")
        }

        @Test
        fun `candidat - orthographe dominante, sinon sans accent, sinon la plus fréquente`() {
            assertThat(display.forCandidate("ecol", "ecole", listOf(fr), fr)).isEqualTo("école")
            assertThat(display.forCandidate("x", "a", listOf(fr), fr)).isEqualTo("a")
            assertThat(display.forCandidate("pechet", "pecher", listOf(fr), fr)).isEqualTo("pêcher")
            assertThat(display.forCandidate("Ecol", "ecole", listOf(fr), fr)).isEqualTo("École")
        }


        @Test
        fun `sigles et casses implausibles ignorés - ça`() {
            val french = LexiconSet(LexiconFixtures.lexicon(listOf("ça" to 870L, "CA" to 100L, "cA" to 30L), "fr"))

            assertThat(display.forTypedWord("ca", "ca", listOf(french), french)).isEqualTo("ça")
            assertThat(display.forCandidate("ca", "ca", listOf(french), french)).isEqualTo("ça")
        }

        @Test
        fun `bilingue pondéré - anglais de faible poids, le français est restitué`() {
            val fr2 = bilingualFrench("rôle")
            val en2 = bilingualEnglish("role")

            val inserted = display.forTypedWord("role", "role", listOf(fr2, en2), fr2, listOf(0.999, 0.001))

            assertThat(inserted).isEqualTo("rôle")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `forme forcée par un accent tapé - inchangée`() {
            assertThat(display.forTypedWord("écolé", "ecole", listOf(fr), fr)).isEqualTo("écolé")
            assertThat(DisplayForms.isForced("ecole")).isFalse()
            assertThat(DisplayForms.isForced("Œuvre")).isTrue()
        }

        @Test
        fun `clé absente - texte tapé ou clé`() {
            assertThat(display.forTypedWord("zz", "zz", listOf(fr), fr)).isEqualTo("zz")
            assertThat(display.forCandidate("zz", "zzz", listOf(fr), null)).isEqualTo("zzz")
        }

        @Test
        fun `casse invraisemblable des sources - ignorée`() {
            assertThat(DisplayForms.isPlausibleCase("daV")).isFalse()
            assertThat(DisplayForms.isPlausibleCase("Monday")).isTrue()
            assertThat(DisplayForms.isPlausibleCase("monday")).isTrue()
            assertThat(DisplayForms.isPlausibleCase("")).isTrue()
            assertThat(display.forCandidate("da", "dav", listOf(fr), fr)).isEqualTo("dav")
        }


        @Test
        fun `bilingue pondéré - anglais de fort poids, le texte tapé est gardé`() {
            val fr2 = bilingualFrench("rôle")
            val en2 = bilingualEnglish("role")

            assertThat(display.forTypedWord("role", "role", listOf(fr2, en2), fr2, listOf(0.5, 0.5))).isEqualTo("role")
        }

        @Test
        fun `bilingue - un lexique sans orthographe dominante rend la restitution ambiguë`() {
            val fr2 = LexiconSet(LexiconFixtures.lexicon(listOf("ou" to 50L, "où" to 50L, "x" to 900L), "fr"))
            val en2 = LexiconSet(LexiconFixtures.lexicon(listOf("où" to 100L, "y" to 900L), "en"))

            assertThat(display.forTypedWord("ou", "ou", listOf(fr2, en2), fr2, listOf(0.999, 0.001))).isEqualTo("ou")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `seuil de dominance - 95 pour cent exactement`() {
            val exact = LexiconSet(LexiconFixtures.lexicon(listOf("été" to 95L, "ete" to 5L), "fr"))
            val below = LexiconSet(LexiconFixtures.lexicon(listOf("été" to 94L, "ete" to 6L), "fr"))

            assertThat(display.forTypedWord("ete", "ete", listOf(exact), exact)).isEqualTo("été")
            assertThat(display.forTypedWord("ete", "ete", listOf(below), below)).isEqualTo("ete")
        }

        @Test
        fun `casse tapée - une lettre majuscule, sans lettre`() {
            assertThat(DisplayForms.applyTypedCase("i'm", "I")).isEqualTo("I'm")
            assertThat(DisplayForms.applyTypedCase("école", "E")).isEqualTo("École")
            assertThat(DisplayForms.applyTypedCase("abc", "'")).isEqualTo("abc")
            assertThat(DisplayForms.applyTypedCase("abc", "aB")).isEqualTo("abc")
        }

        @Test
        fun `apostrophe typographique - pas une différence`() {
            val set = LexiconSet(LexiconFixtures.lexicon(listOf("don't" to 10L), "en"))

            assertThat(display.forTypedWord("don’t", "don't", listOf(set), set)).isEqualTo("don’t")
            assertThat(display.forTypedWord("donʼt", "don't", listOf(set), set)).isEqualTo("donʼt")
        }

        @Test
        fun `langue de la phrase sans la clé - formes de tous les lexiques`() {
            assertThat(display.forTypedWord("i", "i", listOf(en), fr)).isEqualTo("I")
        }


        @Test
        fun `clé qui n'a que des sigles - calcul inchangé`() {
            val sigles = LexiconSet(LexiconFixtures.lexicon(listOf("USA" to 100L, "UsA" to 1L), "en"))

            assertThat(display.forTypedWord("usa", "usa", listOf(sigles), sigles)).isEqualTo("USA")
        }

        @Test
        fun `poids nul d'un lexique - sa masse ne compte pas`() {
            val fr2 = bilingualFrench("rôle")
            val en2 = bilingualEnglish("role")

            assertThat(display.forTypedWord("role", "role", listOf(fr2, en2), fr2, listOf(1.0, 0.0))).isEqualTo("rôle")
        }

        @Test
        fun `un seul lexique - la pondération ne change rien`() {
            assertThat(display.forTypedWord("ecole", "ecole", listOf(fr), fr, listOf(0.01))).isEqualTo("école")
        }
    }
}
