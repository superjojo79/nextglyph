package nextglyph.engine.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import nextglyph.engine.learn.LearnedData;
import nextglyph.engine.learn.LearnedDataFile;
import nextglyph.engine.learn.LearningConfig;
import nextglyph.engine.learn.LoadIssueKind;
import nextglyph.engine.learn.LoadResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fuzzing du fichier d'état appris : la lecture « ne lève jamais d'exception » (contrat de
 * {@link LearnedDataFile}), et ce qu'elle accepte peut être réécrit puis relu sans problème.
 */
class LearnedDataFileFuzzTest {

    private static final int SECTION_COUNT = 3;
    private static final int MAX_SECTION_BYTES = 256;
    private static final byte[] MAGIC = {'N', 'G', 'L', 'S'};

    private static byte[] validFile() {
        LearnedData data = new LearnedData();
        data.getPersonal().importWord("bonjour", "fr", 3);
        data.getPersonal().importWord("hello", "en", 2);
        data.getRejections().recordRejection("ca", "ça");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LearnedDataFile.INSTANCE.write(data, out);
        return out.toByteArray();
    }

    /** Un fichier d'en-tête correct dont les sections ont une longueur et une somme de contrôle justes. */
    private static byte[] structured(FuzzedDataProvider data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        out.write(0);
        out.write(LearnedDataFile.VERSION);
        out.write(SECTION_COUNT);
        for (int i = 0; i < SECTION_COUNT; i++) {
            byte[] payload = data.consumeBytes(data.consumeInt(0, MAX_SECTION_BYTES));
            out.write(data.consumeInt(1, 4));
            out.write(data.consumeInt(1, 3));
            out.writeBytes(new byte[] {0, 0, (byte) (payload.length >>> 8), (byte) payload.length});
            out.writeBytes(payload);
            out.writeBytes(new byte[4]);
        }
        byte[] file = out.toByteArray();
        FuzzInputs.fixChecksums(file);
        return file;
    }

    private static void checkTolerantRead(byte[] bytes) {
        LoadResult result = LearnedDataFile.INSTANCE.decode(bytes, new LearningConfig());
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        LearnedDataFile.INSTANCE.write(result.getData(), rewritten);
        LoadResult again = LearnedDataFile.INSTANCE.decode(rewritten.toByteArray(), new LearningConfig());

        assertThat(again.getIssues()).as("relecture de ce que la lecture a accepté").isEmpty();
    }

    @Nested
    class HappyPath {
        @Test
        @DisplayName("un fichier écrit se relit sans problème")
        void writtenFileReadsBackCleanly() {
            LoadResult result = LearnedDataFile.INSTANCE.decode(validFile(), new LearningConfig());

            assertThat(result.getIssues()).isEmpty();
            assertThat(result.getData().getPersonal().contains("bonjour", "fr")).isTrue();
        }
    }

    @Nested
    class ErrorCases {
        @FuzzTest(maxDuration = "60s")
        @DisplayName("des octets arbitraires ne lèvent jamais d'exception")
        void arbitraryBytesNeverThrow(FuzzedDataProvider data) {
            byte[] bytes = FuzzInputs.bytes(data, validFile());
            if (data.consumeBoolean()) {
                FuzzInputs.fixChecksums(bytes);
            }
            checkTolerantRead(bytes);
        }

        @FuzzTest(maxDuration = "60s")
        @DisplayName("des sections bien formées au contenu arbitraire ne lèvent jamais d'exception")
        void wellFormedSectionsNeverThrow(FuzzedDataProvider data) {
            checkTolerantRead(structured(data));
        }

        @FuzzTest(maxDuration = "30s")
        @DisplayName("la lecture d'un flux arbitraire ne lève jamais d'exception")
        void arbitraryStreamNeverThrows(FuzzedDataProvider data) {
            byte[] bytes = FuzzInputs.bytes(data, validFile());

            LearnedDataFile.INSTANCE.read(new ByteArrayInputStream(bytes), new LearningConfig());
        }
    }

    @Nested
    class BoundaryValues {
        @Test
        @DisplayName("un fichier vide signale une signature invalide")
        void emptyFileHasBadMagic() {
            LoadResult result = LearnedDataFile.INSTANCE.decode(new byte[0], new LearningConfig());

            assertThat(result.getIssues()).extracting("kind").containsExactly(LoadIssueKind.BAD_MAGIC);
        }

        @Test
        @DisplayName("un fichier valide tronqué d'un octet est signalé sans exception")
        void truncatedByOneByteIsReported() {
            byte[] file = validFile();
            byte[] truncated = Arrays.copyOf(file, file.length - 1);

            LoadResult result = LearnedDataFile.INSTANCE.decode(truncated, new LearningConfig());

            assertThat(result.getIssues()).isNotEmpty();
        }
    }
}
