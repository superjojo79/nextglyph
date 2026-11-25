package nextglyph.engine.pack

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PackKeysTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `fournit autant de clés que de clés déclarées`() {
            assertThat(PackKeys.trusted()).hasSameSizeAs(PackKeys.TRUSTED)
        }

        @Test
        fun `la clé du projet est déclarée`() {
            assertThat(PackKeys.TRUSTED).isNotEmpty()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `toutes les clés déclarées sont des clés EC valides`() {
            assertThat(PackKeys.trusted()).allSatisfy {
                assertThat(it.algorithm).isEqualTo("EC")
                assertThat((it as java.security.interfaces.ECPublicKey).params.order.bitLength()).isEqualTo(256)
            }
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `au plus deux clés pour la rotation`() {
            assertThat(PackKeys.TRUSTED.size).isLessThanOrEqualTo(2)
        }
    }
}
