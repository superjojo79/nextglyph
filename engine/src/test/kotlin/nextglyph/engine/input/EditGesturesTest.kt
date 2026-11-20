package nextglyph.engine.input

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EditGesturesTest {

    /** Départ en (100, 50), activation à 30 px, un pas tous les 20 px. */
    private fun drag() = DragSteps(100f, 50f, 30f, 20f)

    @Nested
    inner class HappyPath {
        @Test
        fun `glissement vers la droite - un pas à l'activation, puis un tous les 20 px`() {
            val d = drag()

            assertThat(d.move(120f, 50f)).isZero()
            assertThat(d.active).isFalse()
            assertThat(d.move(131f, 52f)).isEqualTo(1)
            assertThat(d.active).isTrue()
            assertThat(d.move(149f, 52f)).isZero()
            assertThat(d.move(171f, 52f)).isEqualTo(2)
        }

        @Test
        fun `glissement vers la gauche - pas négatifs`() {
            val d = drag()

            assertThat(d.move(69f, 50f)).isEqualTo(-1)
            assertThat(d.move(49f, 50f)).isEqualTo(-1)
        }

        @Test
        fun `mot précédent - espaces de fin et mot`() {
            assertThat(WordDeletion.length("Il fait beau ")).isEqualTo(5)
            assertThat(WordDeletion.length("Il fait beau")).isEqualTo(4)
            assertThat(WordDeletion.length("c'est-à-dire")).isEqualTo(12)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `seuils invalides refusés`() {
            assertThatThrownBy { DragSteps(0f, 0f, 0f, 20f) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Seuils")
            assertThatThrownBy { DragSteps(0f, 0f, 30f, -1f) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `glissement surtout vertical - jamais actif`() {
            val d = drag()

            assertThat(d.move(140f, 0f)).isZero()
            assertThat(d.active).isFalse()
        }

        @Test
        fun `texte vide ou absent - rien à effacer`() {
            assertThat(WordDeletion.length(null)).isZero()
            assertThat(WordDeletion.length("")).isZero()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `retour en arrière - les pas reviennent, sans dépasser le point de départ trop tôt`() {
            val d = drag()
            d.move(131f, 50f)
            d.move(151f, 50f)

            assertThat(d.move(135f, 50f)).isEqualTo(-1)
            assertThat(d.move(125f, 50f)).isZero()
            assertThat(d.move(110f, 50f)).isEqualTo(-1)
        }

        @Test
        fun `activation exactement au seuil - pas encore`() {
            val d = drag()

            assertThat(d.move(130f, 50f)).isZero()
            assertThat(d.move(70f, 50f)).isZero()
            assertThat(d.active).isFalse()
        }

        @Test
        fun `ponctuation, émoji ou seulement des espaces`() {
            assertThat(WordDeletion.length("Bonjour !")).isEqualTo(1)
            assertThat(WordDeletion.length("ok 😀")).isEqualTo(2)
            assertThat(WordDeletion.length("   ")).isEqualTo(3)
            assertThat(WordDeletion.length("a  ")).isEqualTo(3)
        }
    }
}
