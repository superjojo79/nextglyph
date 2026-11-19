package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardLayouts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyWeightsTest {

    private val azerty = KeyboardLayouts.AZERTY

    @Nested
    inner class HappyPath {

        @Test
        fun `un nouveau tampon est neutre`() {
            val weights = KeyWeights(azerty)

            assertThat(weights.isNeutral).isTrue()
            assertThat(weights.source).isEqualTo(WeightSource.NEUTRAL)
            assertThat(weights.intensity).isEqualTo(0f)
            assertThat(weights.endProbability).isEqualTo(0f)
            assertThat(weights.isAtRest(0, 0)).isTrue()
            assertThat(weights.relativeWeight(0, 0)).isEqualTo(1f)
        }

        @Test
        fun `fixer un poids rend la lettre adaptée et les poids non neutres`() {
            val weights = KeyWeights(azerty)

            weights.setLetterWeight('z', 0.25f)

            assertThat(weights.source).isEqualTo(WeightSource.PREFIX)
            assertThat(weights.isNeutral).isFalse()
            assertThat(weights.intensity).isEqualTo(1f)
            assertThat(weights.relativeWeight(0, 1)).isEqualTo(0.25f)
            assertThat(weights.isAtRest(0, 1)).isFalse()
            assertThat(weights.isAtRest(0, 0)).isTrue()
        }

        @Test
        fun `un second poids ne réinitialise pas le premier`() {
            val weights = KeyWeights(azerty)

            weights.setLetterWeight('a', 0.5f)
            weights.setIntensity(0.3f)
            weights.setLetterWeight('z', 0.25f)

            assertThat(weights.relativeWeight(0, 0)).isEqualTo(0.5f)
            assertThat(weights.intensity).isEqualTo(0.3f)
        }

        @Test
        fun `copie et remise au neutre`() {
            val weights = KeyWeights(azerty)
            weights.setLetterWeight('e', 0f)
            weights.setEndProbability(0.7f)
            weights.setIntensity(0.4f)
            val copy = KeyWeights(azerty)

            copy.copyFrom(weights)
            weights.setNeutral()

            assertThat(copy.relativeWeight(0, 2)).isEqualTo(0f)
            assertThat(copy.isAtRest(0, 2)).isFalse()
            assertThat(copy.endProbability).isEqualTo(0.7f)
            assertThat(copy.intensity).isEqualTo(0.4f)
            assertThat(copy.source).isEqualTo(WeightSource.PREFIX)
            assertThat(weights.isNeutral).isTrue()
            assertThat(weights.isAtRest(0, 2)).isTrue()
            assertThat(weights.endProbability).isEqualTo(0f)
        }

        @Test
        fun `la représentation textuelle liste les lettres adaptées`() {
            val weights = KeyWeights(azerty)
            weights.setLetterWeight('e', 0.5f)

            assertThat(weights.toString()).contains("PREFIX", "e=0.5").doesNotContain("a=")
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(floats = [-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette un poids, une probabilité ou une intensité hors de 0 à 1`(value: Float) {
            val weights = KeyWeights(azerty)

            assertThatThrownBy { weights.setLetterWeight('a', value) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { weights.setEndProbability(value) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { weights.setIntensity(value) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(weights.isNeutral).isTrue()
        }

        @Test
        fun `rejette la copie de poids d'une autre disposition`() {
            assertThatThrownBy { KeyWeights(azerty).copyFrom(KeyWeights(KeyboardLayouts.QWERTY)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejette une touche inexistante`() {
            val weights = KeyWeights(azerty)

            assertThatThrownBy { weights.relativeWeight(3, 0) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
            assertThatThrownBy { weights.isAtRest(-1, 0) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
            assertThatThrownBy { weights.isAtRest(2, 10) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
        }

        @Test
        fun `une lettre absente de la disposition est ignorée`() {
            val weights = KeyWeights(azerty)

            weights.setLetterWeight('é', 0.5f)
            weights.setLetterWeight('?', 0.5f)

            assertThat(weights.toString()).doesNotContain("é=", "?=")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `l'intensité ne s'applique pas à des poids neutres`() {
            val weights = KeyWeights(azerty)

            weights.setIntensity(1f)

            assertThat(weights.intensity).isEqualTo(0f)
        }

        @Test
        fun `accepte les bornes 0 et 1`() {
            val weights = KeyWeights(azerty)

            weights.setLetterWeight('a', 0f)
            weights.setLetterWeight('p', 1f)
            weights.setEndProbability(1f)
            weights.setIntensity(0f)

            assertThat(weights.relativeWeight(0, 0)).isEqualTo(0f)
            assertThat(weights.relativeWeight(0, 9)).isEqualTo(1f)
            assertThat(weights.endProbability).isEqualTo(1f)
            assertThat(weights.intensity).isEqualTo(0f)
        }

        @Test
        fun `dernière touche de la dernière rangée`() {
            val weights = KeyWeights(azerty)
            weights.setLetterWeight('n', 0.5f)

            assertThat(weights.isAtRest(2, 9)).isTrue()
            assertThat(weights.relativeWeight(2, 6)).isEqualTo(0.5f)
        }

        @Test
        fun `une touche de fonction n'est jamais adaptée par une lettre homonyme`() {
            val weights = KeyWeights(azerty)

            for (c in 'a'..'z') weights.setLetterWeight(c, 0f)

            listOf(0, 7, 8, 9).forEach { assertThat(weights.isAtRest(2, it)).isTrue() }
        }
    }
}
