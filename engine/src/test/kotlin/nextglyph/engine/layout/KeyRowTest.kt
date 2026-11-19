package nextglyph.engine.layout

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyRowTest {

    private val a = Key.letter('a')
    private val b = Key.letter('b', 0.5f)

    @Nested
    inner class HappyPath {
        @Test
        fun `expose les touches, leurs largeurs et la somme`() {
            val row = KeyRow(listOf(a, b))

            assertThat(row.keys).containsExactly(a, b)
            assertThat(row.restWidths).containsExactly(1f, 0.5f)
            assertThat(row.restUnits).isEqualTo(1.5f)
            assertThat(row.leadingInset).isEqualTo(0f)
        }

        @Test
        fun `le retrait compte dans la somme`() {
            assertThat(KeyRow(listOf(a, b), leadingInset = 0.3f).restUnits).isEqualTo(1.8f)
        }

        @Test
        fun `deux rangées de même contenu sont égales`() {
            assertThat(KeyRow(listOf(a), 0.3f))
                .isEqualTo(KeyRow(listOf(a), 0.3f))
                .hasSameHashCodeAs(KeyRow(listOf(a), 0.3f))
                .isNotEqualTo(KeyRow(listOf(a), 0.4f))
                .isNotEqualTo(KeyRow(listOf(b), 0.3f))
                .isNotEqualTo("autre chose")
        }

        @Test
        fun `la description liste les libellés`() {
            assertThat(KeyRow(listOf(a, Key(KeyKind.SPACE, "", 1f)), 0.5f).toString())
                .contains("inset=0.5", "a _")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette une rangée vide`() {
            assertThatThrownBy { KeyRow(emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("au moins une touche")
        }

        @ParameterizedTest
        @ValueSource(floats = [-0.1f, Float.NaN, Float.NEGATIVE_INFINITY])
        fun `rejette un retrait négatif ou non fini`(inset: Float) {
            assertThatThrownBy { KeyRow(listOf(a), inset) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("retrait")
        }

        @Test
        fun `ne partage pas la liste de l'appelant`() {
            val source = mutableListOf(a)
            val row = KeyRow(source)

            source.add(b)

            assertThat(row.keys).containsExactly(a)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `accepte une seule touche et un retrait nul`() {
            val row = KeyRow(listOf(a), 0f)

            assertThat(row.keys).hasSize(1)
            assertThat(row.restUnits).isEqualTo(1f)
        }
    }
}
