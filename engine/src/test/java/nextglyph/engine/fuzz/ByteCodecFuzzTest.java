package nextglyph.engine.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import nextglyph.engine.learn.ByteCodec;
import nextglyph.engine.learn.ByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fuzzing des primitives de lecture binaire : {@link ByteReader} ne lève que {@link IllegalArgumentException}
 * et {@link ByteCodec#readAtMost} ne dépasse jamais sa borne.
 */
class ByteCodecFuzzTest {

    private static final int MAX_OPERATIONS = 64;
    private static final int MAX_BOUND = 1 << 16;

    @Nested
    class HappyPath {
        @Test
        @DisplayName("la lecture bornée rend au plus le maximum demandé")
        void readAtMostStopsAtTheBound() throws IOException {
            byte[] result = ByteCodec.INSTANCE.readAtMost(new ByteArrayInputStream(new byte[100]), 10);

            assertThat(result).hasSize(10);
        }
    }

    @Nested
    class ErrorCases {
        @Test
        @DisplayName("une borne négative est refusée par IllegalArgumentException")
        void negativeBoundIsRejected() {
            assertThatThrownBy(() -> ByteCodec.INSTANCE.readAtMost(new ByteArrayInputStream(new byte[1]), -1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @FuzzTest(maxDuration = "60s")
        @DisplayName("ByteReader ne lève que IllegalArgumentException sur des octets arbitraires")
        void readerOnlyRaisesIllegalArgument(FuzzedDataProvider data) {
            byte[] bytes = data.consumeBytes(data.consumeInt(0, 64));
            ByteReader reader = new ByteReader(bytes, 0, bytes.length);
            try {
                for (int i = 0; i < MAX_OPERATIONS && data.remainingBytes() > 0; i++) {
                    switch (data.consumeInt(0, 7)) {
                        case 0 -> reader.u8();
                        case 1 -> reader.u16();
                        case 2 -> reader.u32();
                        case 3 -> reader.varInt(data.consumeInt(0, Integer.MAX_VALUE));
                        case 4 -> reader.string(data.consumeInt(0, 64));
                        case 5 -> reader.u16();
                        case 6 -> reader.bytes(data.consumeInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
                        default -> reader.requireEnd();
                    }
                    assertThat(reader.getRemaining()).isBetween(0, bytes.length);
                }
            } catch (IllegalArgumentException expected) {
                // Données corrompues : seule exception documentée.
            }
        }

        @FuzzTest(maxDuration = "30s")
        @DisplayName("readAtMost rend un préfixe de l'entrée de taille au plus égale à la borne")
        void readAtMostReturnsBoundedPrefix(FuzzedDataProvider data) throws IOException {
            int max = data.consumeInt(0, MAX_BOUND);
            byte[] input = data.consumeRemainingAsBytes();

            byte[] result = ByteCodec.INSTANCE.readAtMost(new ByteArrayInputStream(input), max);

            assertThat(result).isEqualTo(Arrays.copyOf(input, Math.min(input.length, max)));
        }
    }

    @Nested
    class BoundaryValues {
        @Test
        @DisplayName("une borne nulle ne lit rien")
        void zeroBoundReadsNothing() throws IOException {
            assertThat(ByteCodec.INSTANCE.readAtMost(new ByteArrayInputStream(new byte[5]), 0)).isEmpty();
        }

        @Test
        @DisplayName("un flux plus court que la borne est rendu en entier")
        void shortStreamIsReturnedWhole() throws IOException {
            assertThat(ByteCodec.INSTANCE.readAtMost(new ByteArrayInputStream(new byte[3]), 10)).hasSize(3);
        }
    }
}
