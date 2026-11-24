package nextglyph.engine.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.ByteBuffer;
import nextglyph.engine.lexicon.Lexicon;
import nextglyph.engine.lexicon.LexiconBuilder;
import nextglyph.engine.lexicon.InvalidFormPolicy;
import nextglyph.engine.lexicon.LexiconFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Fuzzing du décodeur de lexique `.nglx` : un fichier corrompu ne lève que {@link LexiconFormatException}. */
class LexiconFuzzTest {

    private static final int MAX_LIMIT = 50;

    private static byte[] validFile() {
        return new LexiconBuilder("fr", InvalidFormPolicy.REJECT)
            .add("bonjour", 100)
            .add("bonsoir", 40)
            .add("été", 30)
            .add("ete", 5)
            .add("maison", 20)
            .toByteArray();
    }

    /** Charge puis parcourt tout le lexique avec des requêtes dérivées du fuzzer. */
    private static void exercise(byte[] bytes, FuzzedDataProvider data) {
        try {
            Lexicon lexicon = Lexicon.Companion.load(ByteBuffer.wrap(bytes));
            for (int i = 0; i < 4; i++) {
                String prefix = data.consumeString(12);
                lexicon.distribution(prefix);
                lexicon.prefixMass(prefix);
                lexicon.completions(prefix, data.consumeInt(0, MAX_LIMIT));
                lexicon.displayForms(prefix);
            }
            lexicon.toByteArray();
        } catch (LexiconFormatException expected) {
            // Fichier corrompu : seule exception documentée.
        }
    }

    @Nested
    class HappyPath {
        @Test
        @DisplayName("un lexique valide se charge et répond aux requêtes")
        void validLexiconLoads() {
            Lexicon lexicon = Lexicon.Companion.load(ByteBuffer.wrap(validFile()));

            assertThat(lexicon.completions("bon", 5)).extracting("form").contains("bonjour", "bonsoir");
        }
    }

    @Nested
    class ErrorCases {
        @Test
        @DisplayName("un fichier sans magic est refusé par LexiconFormatException")
        void garbageIsRejected() {
            ByteBuffer garbage = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});

            assertThatThrownBy(() -> Lexicon.Companion.load(garbage)).isInstanceOf(LexiconFormatException.class);
        }

        @FuzzTest(maxDuration = "60s")
        @DisplayName("des octets arbitraires ne lèvent que LexiconFormatException")
        void arbitraryBytesOnlyRaiseFormatException(FuzzedDataProvider data) {
            exercise(FuzzInputs.bytes(data, validFile()), data);
        }
    }

    @Nested
    class BoundaryValues {
        @Test
        @DisplayName("un tampon vide est refusé sans autre exception")
        void emptyBufferIsRejected() {
            assertThatThrownBy(() -> Lexicon.Companion.load(ByteBuffer.allocate(0))).isInstanceOf(LexiconFormatException.class);
        }

        @Test
        @DisplayName("un lexique valide tronqué d'un octet est refusé")
        void truncatedByOneByteIsRejected() {
            byte[] file = validFile();
            ByteBuffer truncated = ByteBuffer.wrap(file, 0, file.length - 1);

            assertThatThrownBy(() -> Lexicon.Companion.load(truncated)).isInstanceOf(LexiconFormatException.class);
        }
    }
}
