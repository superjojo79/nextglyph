package nextglyph.engine.layout

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyboardMetricsTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `les valeurs du §5-1`() {
            assertThat(KeyboardMetrics.SUGGESTION_BAR_DP).isEqualTo(32f)
            assertThat(KeyboardMetrics.DEFAULT_ROW_HEIGHT_DP).isEqualTo(38f)
            assertThat(KeyboardMetrics.ROW_GAP_DP).isEqualTo(4f)
        }

        @Test
        fun `trois rangées par défaut tiennent sous le plafond de 160 dp`() {
            val total = KeyboardMetrics.totalHeightDp(3)

            assertThat(total).isEqualTo(154f)
            assertThat(total).isLessThanOrEqualTo(KeyboardMetrics.MAX_DEFAULT_TOTAL_DP)
        }

        @Test
        fun `la hauteur de rangée peut être réglée`() {
            assertThat(KeyboardMetrics.totalHeightDp(3, 46f)).isEqualTo(32f + 138f + 8f)
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun `rejette un nombre de rangées non positif`(rows: Int) {
            assertThatThrownBy { KeyboardMetrics.totalHeightDp(rows) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @ParameterizedTest
        @ValueSource(floats = [0f, -1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette une hauteur de rangée invalide`(height: Float) {
            assertThatThrownBy { KeyboardMetrics.totalHeightDp(3, height) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une seule rangée n'a pas d'espacement`() {
            assertThat(KeyboardMetrics.totalHeightDp(1)).isEqualTo(70f)
        }

        @Test
        fun `la plus petite hauteur réglable de 34 dp`() {
            assertThat(KeyboardMetrics.totalHeightDp(3, 34f)).isEqualTo(142f)
        }
    }
}
