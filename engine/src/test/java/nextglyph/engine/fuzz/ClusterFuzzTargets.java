package nextglyph.engine.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * Points d'entrée ClusterFuzzLite (`.clusterfuzzlite/`) : chaque classe imbriquée expose le
 * {@code fuzzerTestOneInput} attendu par le pilote Jazzer autonome et délègue au {@code @FuzzTest}
 * correspondant, sans dupliquer sa logique. Une cible ajoutée ici doit l'être aussi dans
 * `.clusterfuzzlite/build.sh` et `scripts/fuzz.sh`.
 *
 * <p>Le nom ne finit pas par {@code Test} : ce n'est pas une classe de test JUnit.
 */
public final class ClusterFuzzTargets {

    private ClusterFuzzTargets() {
    }

    /** {@link LexiconFuzzTest}. */
    public static final class Lexicon {
        private Lexicon() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new LexiconFuzzTest().new ErrorCases().arbitraryBytesOnlyRaiseFormatException(data);
        }
    }

    /** {@link NgramModelFuzzTest}. */
    public static final class NgramModel {
        private NgramModel() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new NgramModelFuzzTest().new ErrorCases().arbitraryBytesOnlyRaiseFormatException(data);
        }
    }

    /** {@link LearnedDataFileFuzzTest}, octets arbitraires. */
    public static final class LearnedDataBytes {
        private LearnedDataBytes() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new LearnedDataFileFuzzTest().new ErrorCases().arbitraryBytesNeverThrow(data);
        }
    }

    /** {@link LearnedDataFileFuzzTest}, sections bien formées. */
    public static final class LearnedDataSections {
        private LearnedDataSections() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new LearnedDataFileFuzzTest().new ErrorCases().wellFormedSectionsNeverThrow(data);
        }
    }

    /** {@link LearnedDataFileFuzzTest}, lecture en flux. */
    public static final class LearnedDataStream {
        private LearnedDataStream() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new LearnedDataFileFuzzTest().new ErrorCases().arbitraryStreamNeverThrows(data);
        }
    }

    /** {@link ByteCodecFuzzTest}, lecteur d'octets. */
    public static final class ByteReader {
        private ByteReader() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new ByteCodecFuzzTest().new ErrorCases().readerOnlyRaisesIllegalArgument(data);
        }
    }

    /** {@link ByteCodecFuzzTest}, lecture bornée d'un flux. */
    public static final class ReadAtMost {
        private ReadAtMost() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) throws java.io.IOException {
            new ByteCodecFuzzTest().new ErrorCases().readAtMostReturnsBoundedPrefix(data);
        }
    }

    /** {@link StoredStateFuzzTest}, carte de chaleur. */
    public static final class Heatmap {
        private Heatmap() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new StoredStateFuzzTest().new ErrorCases().arbitraryHeatmapNeverThrows(data);
        }
    }

    /** {@link StoredStateFuzzTest}, compteurs de saisie. */
    public static final class Counters {
        private Counters() {
        }

        public static void fuzzerTestOneInput(FuzzedDataProvider data) {
            new StoredStateFuzzTest().new ErrorCases().arbitraryCountersNeverThrow(data);
        }
    }
}
