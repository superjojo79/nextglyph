package nextglyph.engine.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import nextglyph.engine.stats.KeyHeatmap;
import nextglyph.engine.stats.TypingStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fuzzing des états chiffrés en texte (carte de chaleur, compteurs de saisie) : la lecture est tolérante,
 * elle ne lève jamais d'exception et ce qu'elle accepte se réécrit à l'identique.
 */
class StoredStateFuzzTest {

    private static String validHeatmap() {
        return new KeyHeatmap().serialize();
    }

    private static String validCounters() {
        return new TypingStats.Counters().serialize();
    }

    @Nested
    class HappyPath {
        @Test
        @DisplayName("une carte de chaleur vide se relit à l'identique")
        void emptyHeatmapRoundTrips() {
            assertThat(KeyHeatmap.Companion.parse(validHeatmap()).serialize()).isEqualTo(validHeatmap());
        }

        @Test
        @DisplayName("des compteurs vides se relisent à l'identique")
        void emptyCountersRoundTrip() {
            assertThat(TypingStats.Counters.Companion.parse(validCounters()).serialize()).isEqualTo(validCounters());
        }
    }

    @Nested
    class ErrorCases {
        @FuzzTest(maxDuration = "30s")
        @DisplayName("une carte de chaleur arbitraire ne lève aucune exception et se réécrit de façon stable")
        void arbitraryHeatmapNeverThrows(FuzzedDataProvider data) {
            String serialized = KeyHeatmap.Companion.parse(FuzzInputs.text(data, validHeatmap())).serialize();

            assertThat(KeyHeatmap.Companion.parse(serialized).serialize()).isEqualTo(serialized);
        }

        @FuzzTest(maxDuration = "30s")
        @DisplayName("des compteurs arbitraires ne lèvent aucune exception et se réécrivent de façon stable")
        void arbitraryCountersNeverThrow(FuzzedDataProvider data) {
            String serialized = TypingStats.Counters.Companion.parse(FuzzInputs.text(data, validCounters())).serialize();

            assertThat(TypingStats.Counters.Companion.parse(serialized).serialize()).isEqualTo(serialized);
        }
    }

    @Nested
    class BoundaryValues {
        @Test
        @DisplayName("une valeur absente ou vide donne un état vide")
        void absentOrEmptyGivesEmptyState() {
            assertThat(KeyHeatmap.Companion.parse(null).serialize()).isEqualTo(validHeatmap());
            assertThat(KeyHeatmap.Companion.parse("").serialize()).isEqualTo(validHeatmap());
            assertThat(TypingStats.Counters.Companion.parse(null).serialize()).isEqualTo(validCounters());
            assertThat(TypingStats.Counters.Companion.parse("").serialize()).isEqualTo(validCounters());
        }

        @Test
        @DisplayName("un nombre hors de la plage d'un entier est ignoré sans exception")
        void hugeNumberIsIgnored() {
            String huge = "v2;" + "99999999999999999999;".repeat(15) + "0";

            assertThat(TypingStats.Counters.Companion.parse(huge).serialize()).isEqualTo(validCounters());
        }
    }
}
