package nextglyph.ime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Locale

class DecimalSeparatorsTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `virgule en français, point en anglais`() {
            assertThat(DecimalSeparators.preferred(Locale.FRANCE)).isEqualTo(',')
            assertThat(DecimalSeparators.preferred(Locale.CANADA_FRENCH)).isEqualTo(',')
            assertThat(DecimalSeparators.preferred(Locale.US)).isEqualTo('.')
            assertThat(DecimalSeparators.preferred(Locale.UK)).isEqualTo('.')
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `locale absente - locale racine, donc le point`() {
            assertThat(DecimalSeparators.preferred(null)).isEqualTo('.')
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `séparateur ni virgule ni point - ramené à la virgule`() {
            // Arabe (Égypte) : séparateur décimal arabe U+066B.
            val arabic = Locale.forLanguageTag("ar-EG-u-nu-arab")

            assertThat(DecimalSeparators.preferred(arabic)).isEqualTo(',')
        }
    }
}
