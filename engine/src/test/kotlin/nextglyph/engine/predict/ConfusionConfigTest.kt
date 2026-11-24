package nextglyph.engine.predict

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Réglages du contexte de la correction (issue #55) : ensembles de formes confondues et seuils. */
class ConfusionConfigTest {

    @Nested
    inner class HappyPath {
        @Test
        fun `les confusions françaises par défaut`() {
            val sets = ConfusionConfig().sets.getValue("fr").map { it.forms }

            assertThat(sets).containsExactly(
                listOf("a", "à"), listOf("ou", "où"), listOf("la", "là"), listOf("du", "dû"), listOf("ces", "ses"), listOf("et", "est"),
            )
            assertThat(ConfusionConfig().sets).doesNotContainKey("en")
        }

        @Test
        fun `le rang d'une forme ignore la casse`() {
            val set = ConfusionSet(listOf("ou", "où"))

            assertThat(set.indexOf("OU")).isEqualTo(0)
            assertThat(set.indexOf("Où")).isEqualTo(1)
            assertThat(set.indexOf("a")).isEqualTo(-1)
        }

        @Test
        fun `les valeurs par défaut de la configuration`() {
            val config = ConfusionConfig()

            assertThat(config.offerShare).isEqualTo(0.5)
            assertThat(config.minOfferCount).isEqualTo(5)
            assertThat(config.protectTechnical).isTrue()
            assertThat(PredictionConfig().confusion).isEqualTo(config)
        }

        @Test
        fun `la prudence normale exige une part de 0,98 sur 30 occurrences`() {
            assertThat(Prudence.NORMAL.confusionShare).isEqualTo(0.98)
            assertThat(Prudence.NORMAL.confusionCount).isEqualTo(30)
            assertThat(Prudence.CAUTIOUS.confusionShare).isEqualTo(0.99)
            assertThat(Prudence.BOLD.confusionShare).isEqualTo(0.97)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un ensemble a au moins deux formes distinctes en minuscules`() {
            assertThatThrownBy { ConfusionSet(listOf("a")) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("2 à")
            assertThatThrownBy { ConfusionSet(listOf("a", "a")) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("distinctes")
            assertThatThrownBy { ConfusionSet(listOf("a", "À")) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minuscules")
            assertThatThrownBy { ConfusionSet(listOf("a", "")) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minuscules")
        }

        @Test
        fun `les seuils invalides sont refusés`() {
            assertThatThrownBy { ConfusionConfig(offerShare = 0.0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("offerShare")
            assertThatThrownBy { ConfusionConfig(offerShare = 1.1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("offerShare")
            assertThatThrownBy { ConfusionConfig(minOfferCount = 0) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minOfferCount")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `huit formes sont acceptées, neuf refusées`() {
            ConfusionSet(List(ConfusionSet.MAX_FORMS) { "f$it" })

            assertThatThrownBy { ConfusionSet(List(ConfusionSet.MAX_FORMS + 1) { "f$it" }) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `une part de 1 et une seule occurrence sont admises`() {
            val config = ConfusionConfig(offerShare = 1.0, minOfferCount = 1)

            assertThat(config.offerShare).isEqualTo(1.0)
            assertThat(config.minOfferCount).isEqualTo(1)
        }
    }
}
