package nextglyph.tools.analysis

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CandidatesTest {

    private fun lexicon(vararg words: Pair<String, Long>): Lexicon {
        val builder = LexiconBuilder("fr")
        for ((w, f) in words) builder.add(w, f)
        return builder.build()
    }

    // Masse totale 250.
    private val lex = lexicon("bonjour" to 90, "bonne" to 10, "le" to 100, "la" to 50)
    private val source = CandidateSource(lex, KeyboardLayout.AZERTY)

    private fun decide(src: CandidateSource, typed: String) = CandidateMixer.combine(listOf(1.0 to src.lookup(typed)))

    @Nested
    inner class HappyPath {
        @Test
        fun `préfixe connu - complétions normalisées par la masse totale`() {
            val c = source.lookup("bon")

            assertThat(c.isWord).isFalse()
            assertThat(c.knownPrefix).isTrue()
            assertThat(c.total).isCloseTo(100.0 / 250, within(1e-3))
            assertThat(c.top.map { it.key }).containsExactly("bonjour", "bonne")
            assertThat(c.top[0].score).isCloseTo(90.0 / 250, within(1e-3))
            assertThat(c.top[1].score).isCloseTo(10.0 / 250, within(1e-3))
            val d = decide(source, "bon")
            assertThat(d.top).isEqualTo("bonjour")
            assertThat(d.p1).isCloseTo(0.9, within(1e-3))
            assertThat(d.p2).isCloseTo(0.1, within(1e-3))
        }

        @Test
        fun `un mot du lexique est signalé (cas 1 du paragraphe 5_5)`() {
            assertThat(source.lookup("le").isWord).isTrue()
            assertThat(decide(source, "le").isWord).isTrue()
        }

        @Test
        fun `substitution par une voisine - bonjoir donne bonjour au poids 0,01`() {
            assertThat(source.edits("bonjoir")).containsExactlyEntriesOf(mapOf("bonjour" to 0.01))
            val c = source.lookup("bonjoir")

            assertThat(c.knownPrefix).isFalse()
            assertThat(c.isWord).isFalse()
            assertThat(c.total).isCloseTo(0.01 * 90 / 250, within(1e-5))
            assertThat(decide(source, "bonjoir")).isEqualTo(Decision(false, "bonjour", 1.0, 0.0))
        }

        @Test
        fun `substitution non voisine, omission, caractère en trop, inversion`() {
            assertThat(source.edits("bonjoar")).containsExactlyEntriesOf(mapOf("bonjour" to 0.0005))
            assertThat(source.edits("bonjur")).containsExactlyEntriesOf(mapOf("bonjour" to 0.002))
            assertThat(source.edits("bonjjour")).containsExactlyEntriesOf(mapOf("bonjour" to 0.002))
            assertThat(source.edits("bnojour")).containsExactlyEntriesOf(mapOf("bonjour" to 0.002))
        }

        @Test
        fun `apostrophe manquante presque gratuite - jai donne j'ai`() {
            val elision = CandidateSource(lexicon("j'ai" to 10, "jardin" to 10), KeyboardLayout.AZERTY)

            assertThat(elision.edits("jai")).containsEntry("j'ai", 0.5)
            assertThat(CandidateMixer.combine(listOf(1.0 to elision.lookup("jai"))).top).isEqualTo("j'ai")
        }

        @Test
        fun `correction des préfixes connus - ke pour le`() {
            val l = lexicon("le" to 200, "kebab" to 1)
            val plain = CandidateSource(l, KeyboardLayout.AZERTY)
            val correcting = CandidateSource(l, KeyboardLayout.AZERTY, correctKnownPrefixes = true)

            assertThat(decide(plain, "ke")).isEqualTo(Decision(false, "kebab", 1.0, 0.0))
            assertThat(correcting.edits("ke")).containsExactlyInAnyOrderEntriesOf(mapOf("le" to 0.01, "k" to 0.002))
            val d = decide(correcting, "ke")
            assertThat(d.top).isEqualTo("le")
            assertThat(d.p1).isCloseTo(2.0 / 3.002, within(1e-4))
            assertThat(d.p2).isCloseTo(1.0 / 3.002, within(1e-4))
        }

        @Test
        fun `mélange de deux langues pondéré par pi`() {
            val fr = LanguageCandidates(false, true, 0.4, listOf(ScoredKey("bonjour", 0.3), ScoredKey("bonne", 0.1)))
            val en = LanguageCandidates(false, true, 0.2, listOf(ScoredKey("bonus", 0.2)))

            val d = CandidateMixer.combine(listOf(0.5 to fr, 0.5 to en))

            // total = 0,5 × 0,4 + 0,5 × 0,2 = 0,3 ; bonjour 0,15 ; bonus 0,1.
            assertThat(d.top).isEqualTo("bonjour")
            assertThat(d.p1).isCloseTo(0.5, within(1e-12))
            assertThat(d.p2).isCloseTo(1.0 / 3, within(1e-12))
        }

        @Test
        fun `un mot commun aux deux langues additionne ses scores`() {
            val fr = LanguageCandidates(false, true, 1.0, listOf(ScoredKey("table", 0.5), ScoredKey("tableau", 0.5)))
            val en = LanguageCandidates(false, true, 1.0, listOf(ScoredKey("table", 0.9), ScoredKey("tablet", 0.1)))

            val d = CandidateMixer.combine(listOf(0.5 to fr, 0.5 to en))

            assertThat(d.top).isEqualTo("table")
            assertThat(d.p1).isCloseTo(0.7, within(1e-12))
            assertThat(d.p2).isCloseTo(0.25, within(1e-12))
        }

        @Test
        fun `dominance - seuils de probabilité et de rapport`() {
            val d = Decision(false, "w", 0.6, 0.3)

            assertThat(d.dominates(Thresholds(0.6, 2.0))).isTrue()
            assertThat(d.dominates(Thresholds(0.61, 2.0))).isFalse()
            assertThat(d.dominates(Thresholds(0.6, 2.01))).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `texte tapé vide refusé`() {
            assertThatThrownBy { source.lookup("") }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("vide")
        }

        @Test
        fun `préfixe inconnu sans édition connue - aucun candidat`() {
            val c = source.lookup("zzz")

            assertThat(c.total).isZero()
            assertThat(c.top).isEmpty()
            assertThat(decide(source, "zzz")).isEqualTo(Decision(false, null, 0.0, 0.0))
            assertThat(Decision(false, null, 0.0, 0.0).dominates(Thresholds(0.0, 1.0))).isFalse()
        }

        @Test
        fun `une langue de poids nul est ignorée, même si le texte y est un mot`() {
            val word = LanguageCandidates(true, true, 1.0, listOf(ScoredKey("le", 1.0)))
            val other = LanguageCandidates(false, true, 0.5, listOf(ScoredKey("les", 0.5)))

            val d = CandidateMixer.combine(listOf(0.0 to word, 1.0 to other))

            assertThat(d).isEqualTo(Decision(false, "les", 1.0, 0.0))
        }

        @Test
        fun `un mot commun - pas de correction même avec la variante`() {
            val correcting = CandidateSource(lex, KeyboardLayout.AZERTY, correctKnownPrefixes = true)

            assertThat(correcting.lookup("la").top.map { it.key }).containsExactly("la")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `le cache rend le même objet, vidé il est recalculé`() {
            val first = source.lookup("bon")

            assertThat(source.lookup("bon")).isSameAs(first)
            source.clearCache()
            assertThat(source.lookup("bon")).isNotSameAs(first)
        }

        @Test
        fun `limite d'une forme par préfixe`() {
            val one = CandidateSource(lex, KeyboardLayout.AZERTY, topLimit = 1)

            assertThat(one.lookup("bon").top.map { it.key }).containsExactly("bonjour")
            assertThat(CandidateMixer.combine(listOf(1.0 to one.lookup("bon"))).p2).isZero()
        }

        @Test
        fun `deux formes d'une même clé additionnent leur fréquence`() {
            val accents = CandidateSource(lexicon("ou" to 30, "où" to 20, "oui" to 40), KeyboardLayout.AZERTY)

            val c = accents.lookup("o")

            assertThat(c.top.map { it.key to it.score * 90 }).satisfiesExactly(
                { assertThat(it.first).isEqualTo("ou"); assertThat(it.second).isCloseTo(50.0, within(0.1)) },
                { assertThat(it.first).isEqualTo("oui"); assertThat(it.second).isCloseTo(40.0, within(0.1)) },
            )
        }

        @Test
        fun `égalité de score - ordre alphabétique`() {
            val tie = CandidateSource(lexicon("ab" to 5, "ac" to 5), KeyboardLayout.AZERTY)

            assertThat(CandidateMixer.combine(listOf(1.0 to tie.lookup("a"))).top).isEqualTo("ab")
        }

        @Test
        fun `une seule lettre tapée inconnue - corrections à la première position`() {
            // « m » n'est pas une initiale : substitution par l (voisine) ou b (non voisine) ;
            // le retrait de l'unique lettre donnerait le préfixe vide, exclu.
            assertThat(source.edits("m")).containsExactlyInAnyOrderEntriesOf(mapOf("l" to 0.01, "b" to 0.0005))
        }

        @Test
        fun `candidats présents mais masse totale nulle - aucun candidat`() {
            val zero = LanguageCandidates(false, true, 0.0, listOf(ScoredKey("x", 0.0)))

            assertThat(CandidateMixer.combine(listOf(1.0 to zero))).isEqualTo(Decision(false, null, 0.0, 0.0))
        }

        @Test
        fun `mélange vide ou de masse nulle - aucun candidat`() {
            assertThat(CandidateMixer.combine(emptyList())).isEqualTo(Decision(false, null, 0.0, 0.0))
            val empty = LanguageCandidates(true, false, 0.0, emptyList())
            assertThat(CandidateMixer.combine(listOf(1.0 to empty))).isEqualTo(Decision(true, null, 0.0, 0.0))
        }
    }
}
