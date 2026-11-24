package nextglyph.engine.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import nextglyph.engine.ngram.NgramFormatException;
import nextglyph.engine.ngram.NgramModel;
import nextglyph.engine.ngram.NgramModelBuilder;
import nextglyph.engine.ngram.NgramPruning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Fuzzing du modèle de mot suivant : un fichier corrompu ne lève que {@link NgramFormatException}. */
class NgramModelFuzzTest {

    private static final int MAX_LIMIT = 20;

    private static byte[] validFile() {
        NgramPruning keepAll = new NgramPruning(1, 1, 8, 4, 1000, 0.0, 1);
        // Une zone de confusions (format version 2) pour que le fuzzer en atteigne aussi le décodage.
        List<List<String>> confusions = List.of(List.of("a", "à"), List.of("ou", "où"));
        NgramModelBuilder builder = new NgramModelBuilder("fr", keepAll, word -> true, confusions);
        builder.addSentence(List.of("je", "suis", "content"), 5);
        builder.addSentence(List.of("je", "suis", "fatigué"), 3);
        builder.addSentence(List.of("tu", "es", "là"), 4);
        builder.addSentence(List.of("j'", "ai", "faim"), 3);
        builder.addSentence(List.of("il", "a", "faim"), 3);
        builder.addConfusionContext(List.of("il", "a", "faim"), 3);
        builder.addConfusionContext(List.of("je", "suis", "à", "la", "maison"), 2);
        builder.addConfusionContext(List.of("tu", "es", "où"), 2);
        return builder.build();
    }

    private static void exercise(byte[] bytes, FuzzedDataProvider data) {
        try {
            NgramModel model = NgramModel.Companion.load(ByteBuffer.wrap(bytes));
            for (int i = 0; i < 4; i++) {
                List<String> context = new ArrayList<>();
                int words = data.consumeInt(0, 3);
                for (int w = 0; w < words; w++) {
                    context.add(data.consumeString(10));
                }
                model.next(context, data.consumeInt(0, MAX_LIMIT));
                String previous = data.consumeString(10);
                String word = data.consumeString(10);
                model.contains(word);
                model.bigramProbability(previous, word);
                model.confusionCounts(previous, List.of(word, data.consumeString(10)));
            }
        } catch (NgramFormatException expected) {
            // Fichier corrompu : seule exception documentée.
        }
    }

    @Nested
    class HappyPath {
        @Test
        @DisplayName("un modèle valide propose le mot suivant le plus fréquent")
        void validModelPredicts() {
            NgramModel model = NgramModel.Companion.load(ByteBuffer.wrap(validFile()));

            assertThat(model.next(List.of("je", "suis"), 1)).extracting("text").containsExactly("content");
        }
    }

    @Nested
    class ErrorCases {
        @Test
        @DisplayName("un fichier sans magic est refusé par NgramFormatException")
        void garbageIsRejected() {
            ByteBuffer garbage = ByteBuffer.wrap(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});

            assertThatThrownBy(() -> NgramModel.Companion.load(garbage)).isInstanceOf(NgramFormatException.class);
        }

        @FuzzTest(maxDuration = "60s")
        @DisplayName("des octets arbitraires ne lèvent que NgramFormatException")
        void arbitraryBytesOnlyRaiseFormatException(FuzzedDataProvider data) {
            exercise(FuzzInputs.bytes(data, validFile()), data);
        }
    }

    @Nested
    class BoundaryValues {
        @Test
        @DisplayName("un tampon vide est refusé sans autre exception")
        void emptyBufferIsRejected() {
            assertThatThrownBy(() -> NgramModel.Companion.load(ByteBuffer.allocate(0))).isInstanceOf(NgramFormatException.class);
        }

        @Test
        @DisplayName("un modèle valide tronqué d'un octet est refusé")
        void truncatedByOneByteIsRejected() {
            byte[] file = validFile();
            ByteBuffer truncated = ByteBuffer.wrap(file, 0, file.length - 1);

            assertThatThrownBy(() -> NgramModel.Companion.load(truncated)).isInstanceOf(NgramFormatException.class);
        }
    }
}
