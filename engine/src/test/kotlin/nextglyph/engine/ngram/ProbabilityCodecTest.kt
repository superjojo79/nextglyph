package nextglyph.engine.ngram

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProbabilityCodecTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `la probabilité un est le code zéro`() {
            assertThat(ProbabilityCodec.encode(1.0)).isZero()
            assertThat(ProbabilityCodec.decode(0)).isEqualTo(1.0)
        }

        @Test
        fun `le décodage retrouve la probabilité à trois pour cent près`() {
            for (p in listOf(0.9, 0.5, 0.25, 0.12, 0.05, 0.001, 1e-5)) {
                val decoded = ProbabilityCodec.decode(ProbabilityCodec.encode(p))

                assertThat(decoded / p).isBetween(0.968, 1.033)
            }
        }

        @Test
        fun `le codage est décroissant avec la probabilité`() {
            assertThat(ProbabilityCodec.encode(0.5)).isLessThan(ProbabilityCodec.encode(0.4))
            assertThat(ProbabilityCodec.decode(10)).isGreaterThan(ProbabilityCodec.decode(11))
        }

        @Test
        fun `un neper vaut seize codes`() {
            assertThat(ProbabilityCodec.encode(StrictMath.exp(-1.0))).isEqualTo(16)
            assertThat(ProbabilityCodec.decode(16)).isCloseTo(StrictMath.exp(-1.0), within(1e-12))
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une probabilité nulle, négative ou non numérique prend le code maximal`() {
            assertThat(ProbabilityCodec.encode(0.0)).isEqualTo(ProbabilityCodec.MAX_CODE)
            assertThat(ProbabilityCodec.encode(-0.5)).isEqualTo(ProbabilityCodec.MAX_CODE)
            assertThat(ProbabilityCodec.encode(Double.NaN)).isEqualTo(ProbabilityCodec.MAX_CODE)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une probabilité plus petite que le minimum représentable est écrêtée`() {
            assertThat(ProbabilityCodec.encode(1e-300)).isEqualTo(ProbabilityCodec.MAX_CODE)
            assertThat(ProbabilityCodec.encode(Double.MIN_VALUE)).isEqualTo(ProbabilityCodec.MAX_CODE)
        }

        @Test
        fun `une probabilité supérieure à un est écrêtée à zéro`() {
            assertThat(ProbabilityCodec.encode(2.0)).isZero()
        }

        @Test
        fun `le dernier code décode une probabilité strictement positive`() {
            assertThat(ProbabilityCodec.decode(ProbabilityCodec.MAX_CODE)).isPositive().isLessThan(1e-6)
            assertThat(ProbabilityCodec.encode(ProbabilityCodec.decode(ProbabilityCodec.MAX_CODE))).isEqualTo(ProbabilityCodec.MAX_CODE)
        }
    }
}
