package nextglyph.engine.stats

import nextglyph.engine.predict.Prudence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TypingStatsTest {

    private var now = 0L
    private fun stats(counters: TypingStats.Counters = TypingStats.Counters()) = TypingStats({ now }, counters)

    @Nested
    inner class HappyPath {
        @Test
        fun `vitesse - 10 caractères en 6 secondes, 20 mots par minute`() {
            val s = stats()
            repeat(11) {
                s.onKey()
                now += 600
            }

            assertThat(s.counters.chars).isEqualTo(11)
            assertThat(s.counters.activeMs).isEqualTo(6000)
            assertThat(s.counters.wordsPerMinute!!).isCloseTo(22.0, Offset.offset(1e-9))
        }

        @Test
        fun `taux de correction, d'acceptation et d'annulation`() {
            val s = stats()
            repeat(8) { s.onKey() }
            s.onBackspace()
            s.onBackspace()
            s.onWord(TypingStats.AutoKind.CORRECTION)
            s.onWord(TypingStats.AutoKind.CORRECTION)
            s.onUndo()
            s.onWord(null)
            s.onPick()

            val c = s.counters
            assertThat(c.correctionRate!!).isCloseTo(2.0 / 11, Offset.offset(1e-9))
            assertThat(c.acceptanceRate!!).isCloseTo(0.5, Offset.offset(1e-9))
            assertThat(c.undoRate!!).isCloseTo(0.5, Offset.offset(1e-9))
            assertThat(s.changed).isTrue()
        }

        @Test
        fun `enregistrement puis relecture identiques`() {
            val c = TypingStats.Counters(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

            assertThat(c.serialize()).isEqualTo("v2;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16")
            assertThat(TypingStats.Counters.parse(c.serialize())).isEqualTo(c)
        }

        @Test
        fun `restitutions et corrections comptées par catégorie, annulations imputées à la dernière validation`() {
            val s = stats()
            s.onWord(TypingStats.AutoKind.RESTORATION)
            s.onUndo()
            s.onWord(TypingStats.AutoKind.CORRECTION, Prudence.CAUTIOUS)
            s.onWord(TypingStats.AutoKind.CORRECTION, Prudence.NORMAL)
            s.onWord(TypingStats.AutoKind.CORRECTION, Prudence.NORMAL)
            s.onUndo()
            s.onWord(TypingStats.AutoKind.CORRECTION, Prudence.BOLD)
            s.onUndo()

            val c = s.counters
            assertThat(c).isEqualTo(
                TypingStats.Counters(
                    keys = 3, words = 5, autocommits = 5, undone = 3, restored = 1, restoredUndone = 1,
                    cautiousCorrections = 1, normalCorrections = 2, normalUndone = 1, boldCorrections = 1, boldUndone = 1,
                ),
            )
            assertThat(c.correctionsTotal).isEqualTo(4)
            assertThat(c.correctionsUndoneTotal).isEqualTo(2)
            assertThat(c.correctionUndoRate!!).isCloseTo(0.5, Offset.offset(1e-9))
            assertThat(c.correctionUndoRate(Prudence.CAUTIOUS)!!).isCloseTo(0.0, Offset.offset(1e-9))
            assertThat(c.correctionUndoRate(Prudence.NORMAL)!!).isCloseTo(0.5, Offset.offset(1e-9))
            assertThat(c.correctionUndoRate(Prudence.BOLD)!!).isCloseTo(1.0, Offset.offset(1e-9))
            assertThat(c.restoredUndoRate!!).isCloseTo(1.0, Offset.offset(1e-9))
            assertThat(c.undoRate!!).isCloseTo(0.6, Offset.offset(1e-9))
        }

        @Test
        fun `les accesseurs par prudence lisent chacun leur champ`() {
            val c = TypingStats.Counters(
                cautiousCorrections = 1, cautiousUndone = 2, normalCorrections = 4, normalUndone = 8, boldCorrections = 16, boldUndone = 32,
            )

            assertThat(Prudence.entries.map { c.corrections(it) }).containsExactly(1L, 4L, 16L)
            assertThat(Prudence.entries.map { c.correctionsUndone(it) }).containsExactly(2L, 8L, 32L)
            assertThat(c.correctionsTotal).isEqualTo(21)
            assertThat(c.correctionsUndoneTotal).isEqualTo(42)
        }

        @Test
        fun `une annulation compte comme frappe mais pas comme effacement`() {
            val s = stats()
            s.onWord(TypingStats.AutoKind.CORRECTION)

            s.onUndo()

            assertThat(s.counters.keys).isEqualTo(1)
            assertThat(s.counters.chars).isZero()
            assertThat(s.counters.backspaces).isZero()
            assertThat(s.counters.correctionRate).isEqualTo(0.0)
        }

        @Test
        fun `lecture d'une forme v1 - anciens compteurs gardés, nouveaux à zéro`() {
            val c = TypingStats.Counters.parse("v1;1;2;3;4;5;6;7;8")

            assertThat(c).isEqualTo(TypingStats.Counters(1, 2, 3, 4, 5, 6, 7, 8))
            assertThat(c.serialize()).isEqualTo("v2;1;2;3;4;5;6;7;8;0;0;0;0;0;0;0;0")
        }

        @Test
        fun `les compteurs de catégorie sont lisibles un à un`() {
            val c = TypingStats.Counters(
                restored = 1, restoredUndone = 2, cautiousCorrections = 3, cautiousUndone = 4,
                normalCorrections = 5, normalUndone = 6, boldCorrections = 7, boldUndone = 8,
            )

            assertThat(listOf(c.restored, c.restoredUndone, c.cautiousCorrections, c.cautiousUndone))
                .containsExactly(1L, 2L, 3L, 4L)
            assertThat(listOf(c.normalCorrections, c.normalUndone, c.boldCorrections, c.boldUndone))
                .containsExactly(5L, 6L, 7L, 8L)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `forme enregistrée absente, abîmée ou d'une autre version - compteurs nuls`() {
            for (stored in listOf(null, "", "v3;1;2;3;4;5;6;7;8", "v2;1;2;3;4;5;6;7;8", "v1;1;2", "v1;1;2;3;4;5;6;7;x", "v1;1;2;3;4;5;6;7;-1")) {
                assertThat(TypingStats.Counters.parse(stored)).isEqualTo(TypingStats.Counters())
            }
        }

        @Test
        fun `compteur négatif refusé`() {
            assertThatThrownBy { TypingStats.Counters(chars = -1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("négatif")
        }

        @Test
        fun `sans données - pas de taux`() {
            val c = TypingStats.Counters()

            assertThat(c.wordsPerMinute).isNull()
            assertThat(c.correctionRate).isNull()
            assertThat(c.acceptanceRate).isNull()
            assertThat(c.undoRate).isNull()
        }

        @Test
        fun `une annulation dont la validation n'est plus connue compte seulement le total`() {
            val s = stats()
            s.onWord(TypingStats.AutoKind.CORRECTION)
            s.onKey()
            s.onUndo()
            s.onWord(TypingStats.AutoKind.CORRECTION)
            s.onPick()
            s.onUndo()
            s.onWord(TypingStats.AutoKind.CORRECTION)
            s.onWord(null)
            s.onUndo()

            val c = s.counters
            assertThat(c.undone).isEqualTo(3)
            assertThat(c.correctionsUndoneTotal).isZero()
            assertThat(c.restoredUndone).isZero()
        }

        @Test
        fun `après une remise à zéro, l'annulation n'est plus imputée`() {
            val s = stats()
            s.onWord(TypingStats.AutoKind.RESTORATION)
            s.reset()

            s.onUndo()

            assertThat(s.counters.restoredUndone).isZero()
            assertThat(s.counters.undone).isEqualTo(1)
        }

        @Test
        fun `formes v2 abîmées - compteurs nuls`() {
            val good = "v2;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16"
            assertThat(TypingStats.Counters.parse(good).boldUndone).isEqualTo(16)
            val bad = listOf(
                "v2;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15",
                "v2;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16;17",
                "v2;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;x",
                "v2;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;-1",
                "v2;1;2;3;4;5;6;7;8",
                "v1;1;2;3;4;5;6;7;8;9",
                "V2;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16",
            )
            for (stored in bad) assertThat(TypingStats.Counters.parse(stored)).isEqualTo(TypingStats.Counters())
        }

        @Test
        fun `sans correction ni restitution - pas de taux détaillé`() {
            val c = TypingStats.Counters()

            assertThat(c.correctionUndoRate).isNull()
            assertThat(c.restoredUndoRate).isNull()
            for (p in Prudence.entries) assertThat(c.correctionUndoRate(p)).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `pause de plus de 5 s non comptée, pause de 5 s comptée`() {
            val s = stats()
            s.onKey()
            now += 5000
            s.onKey()
            now += 5001
            s.onKey()

            assertThat(s.counters.activeMs).isEqualTo(5000)
        }

        @Test
        fun `remise à zéro et enregistrement marqué`() {
            val s = stats(TypingStats.Counters(chars = 3, keys = 3))
            assertThat(s.changed).isFalse()

            s.onKey()
            s.markSaved()
            assertThat(s.changed).isFalse()
            now += 100
            s.reset()

            assertThat(s.counters).isEqualTo(TypingStats.Counters())
            assertThat(s.changed).isTrue()
            s.onKey()
            assertThat(s.counters.activeMs).isZero()
        }

        @Test
        fun `plus d'annulations que de validations - acceptation jamais négative`() {
            val c = TypingStats.Counters(words = 2, autocommits = 1, undone = 3)

            assertThat(c.acceptanceRate).isEqualTo(0.0)
            assertThat(TypingStats.PAUSE_MS).isEqualTo(5000)
        }

        @Test
        fun `chaque nouveau compteur négatif est refusé`() {
            val builders = listOf<() -> Any>(
                { TypingStats.Counters(restored = -1) },
                { TypingStats.Counters(restoredUndone = -1) },
                { TypingStats.Counters(cautiousCorrections = -1) },
                { TypingStats.Counters(cautiousUndone = -1) },
                { TypingStats.Counters(normalCorrections = -1) },
                { TypingStats.Counters(normalUndone = -1) },
                { TypingStats.Counters(boldCorrections = -1) },
                { TypingStats.Counters(boldUndone = -1) },
            )

            for (build in builders) assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `une seule annulation parmi une seule correction - taux de cent pour cent`() {
            val c = TypingStats.Counters(normalCorrections = 1, normalUndone = 1, restored = 1)

            assertThat(c.correctionUndoRate).isEqualTo(1.0)
            assertThat(c.restoredUndoRate).isEqualTo(0.0)
        }

        @Test
        fun `un mot sans validation automatique ne compte aucune catégorie`() {
            val s = stats()

            s.onWord(null, Prudence.BOLD)
            s.onUndo()

            assertThat(s.counters).isEqualTo(TypingStats.Counters(keys = 1, words = 1, undone = 1))
        }
    }
}
