package nextglyph.engine.adapt

import nextglyph.engine.adapt.AdaptFixtures.LAYOUTS
import nextglyph.engine.adapt.AdaptFixtures.randomWeights
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.exp
import kotlin.random.Random

class TapDecoderTest {

    private val azerty = KeyboardLayouts.AZERTY
    private val rest = KeyboardGeometry.compute(azerty, 1000f, 100f, 0f)

    private fun decode(
        x: Float,
        y: Float,
        weights: KeyWeights? = null,
        config: TapDecoderConfig = TapDecoderConfig(),
        displayed: KeyboardGeometry = rest,
        restGeometry: KeyboardGeometry = rest,
    ): TapDecoding {
        val out = TapDecoding(displayed.layout)
        val decoded = TapDecoder(displayed.layout, config).decode(x, y, displayed, restGeometry, weights, out)
        assertThat(decoded).isEqualTo(out.key != null)
        return out
    }

    private fun weights(vararg pairs: Pair<Char, Float>, layout: KeyboardLayout = azerty): KeyWeights =
        KeyWeights(layout).apply { pairs.forEach { (c, r) -> setLetterWeight(c, r) } }

    /** Rangée 1 avec a = 1,5 u et z = 0,5 u. */
    private val widened = KeyboardGeometry.compute(
        azerty, 1000f, 100f, 0f,
        listOf(listOf(1.5f, 0.5f) + List(8) { 1f }) + azerty.restWidths().drop(1),
    )

    @Nested
    inner class HappyPath {

        @Test
        fun `un tap au centre d'une touche la produit`() {
            val result = decode(150f, 50f)

            assertThat(result.key?.label).isEqualTo("z")
            assertThat(result.rowIndex).isEqualTo(0)
            assertThat(result.keyIndex).isEqualTo(1)
            assertThat(result.decision).isEqualTo(TapDecision.CORE)
        }

        @Test
        fun `près d'une frontière, le modèle de langue départage`() {
            val result = decode(95f, 50f, weights('a' to 0.05f, 'z' to 1f))

            assertThat(result.key?.label).isEqualTo("z")
            assertThat(result.decision).isEqualTo(TapDecision.SCORE)
        }

        @Test
        fun `sans poids, le décodage est spatial`() {
            val result = decode(95f, 50f)

            assertThat(result.key?.label).isEqualTo("a")
            assertThat(result.decision).isEqualTo(TapDecision.SCORE)
        }

        @Test
        fun `lambda nul, décodage purement spatial malgré les poids`() {
            val result = decode(95f, 50f, weights('a' to 0f, 'z' to 1f), TapDecoderConfig.SPATIAL)

            assertThat(result.key?.label).isEqualTo("a")
        }

        @Test
        fun `les scores suivent la formule du mélange de gaussiennes`() {
            val config = TapDecoderConfig(alpha = 0.25f)

            val result = decode(120f, 60f, weights('a' to 0.5f, 'z' to 1f), config, displayed = widened)

            // σx = 0,35 × 100 px, σy = 0,35 × 100 px ; 10 px sous le centre de la rangée.
            val s = 35.0
            fun n(dx: Double) = exp(-(dx * dx + 100.0) / (2 * s * s)) / (s * s)
            val a = (0.25 * n(120.0 - 75) + 0.75 * n(120.0 - 50)) * Math.pow(0.5, 0.6)
            val z = 0.25 * n(120.0 - 175) + 0.75 * n(120.0 - 150)
            assertThat(result.score(0, 0)).isCloseTo(a, Offset.offset(a * 1e-6))
            assertThat(result.score(0, 1)).isCloseTo(z, Offset.offset(z * 1e-6))
            assertThat(result.score(2, 7)).isEqualTo(0.0)
        }

        @Test
        fun `alpha choisit entre centre affiché et centre de repos`() {
            val shown = decode(120f, 50f, config = TapDecoderConfig(alpha = 1f), displayed = widened)
            val remembered = decode(120f, 50f, config = TapDecoderConfig(alpha = 0f), displayed = widened)

            assertThat(shown.key?.label).isEqualTo("a")
            assertThat(remembered.key?.label).isEqualTo("z")
        }

        @Test
        fun `un tap franchement au centre d'une touche atténuée produit cette lettre`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC)
            val weights = KeyWeights(azerty)
            for (c in 'a'..'z') weights.setLetterWeight(c, if (c == 'z') 0f else 1f)
            val adapted = AdaptedLayout(azerty)
            LayoutSolver(azerty, config).solve(weights, adapted)
            val displayed = KeyboardGeometry.compute(azerty, 1000f, 100f, 0f, adapted.widthsByRow())
            val z = displayed.rows[0][1]

            val result = decode(z.centerX, z.centerY, weights, displayed = displayed)

            assertThat(adapted.opacity(0, 1)).isCloseTo(0.3f, Offset.offset(1e-6f))
            assertThat(result.key?.label).isEqualTo("z")
            assertThat(result.decision).isEqualTo(TapDecision.CORE)
        }

        @ParameterizedTest
        @EnumSource(AdaptationMode::class)
        fun `propriété, le centre affiché de chaque lettre produit cette lettre`(mode: AdaptationMode) {
            val random = Random(mode.ordinal + 1L)
            repeat(200) { n ->
                val layout = LAYOUTS[n % 2]
                val weights = randomWeights(layout, random)
                val adapted = AdaptedLayout(layout)
                LayoutSolver(layout, AdaptationConfig(mode = mode)).solve(weights, adapted)
                val displayed = KeyboardGeometry.compute(layout, 1080f, 120f, 12f, adapted.widthsByRow())
                val restGeometry = KeyboardGeometry.compute(layout, 1080f, 120f, 12f)
                val decoder = TapDecoder(layout)
                val out = TapDecoding(layout)

                for (rect in displayed.rows.flatten()) {
                    decoder.decode(rect.centerX, rect.centerY, displayed, restGeometry, weights, out)

                    assertThat(out.key).isEqualTo(rect.key)
                }
            }
        }

        @Test
        fun `un tap sur une touche de fonction la produit, même entourée de lettres probables`() {
            val strong = weights('n' to 1f, 'w' to 1f, 'm' to 1f)
            val row3 = rest.rows[2]

            for (index in listOf(0, 7, 8, 9)) {
                val rect = row3[index]
                val nearLeft = decode(rect.left + 1f, rect.centerY, strong)

                assertThat(nearLeft.key).isEqualTo(rect.key)
                assertThat(nearLeft.decision).isEqualTo(TapDecision.FUNCTION_KEY)
                assertThat(nearLeft.score(2, 6)).isEqualTo(0.0)
            }
        }

        @Test
        fun `un tap sur une lettre près de l'espace reste une lettre`() {
            val n = rest.rows[2][6]

            val result = decode(n.right - 1f, n.centerY, weights('n' to 0f, 'b' to 0f))

            assertThat(result.key?.kind).isEqualTo(KeyKind.LETTER)
            assertThat(result.key?.label).isEqualTo("n")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un tap hors du clavier ne produit rien`() {
            val out = TapDecoding(azerty)
            out.let { TapDecoder(azerty).decode(150f, 50f, rest, rest, null, it) }

            val decoded = TapDecoder(azerty).decode(-1f, 50f, rest, rest, null, out)

            assertThat(decoded).isFalse()
            assertThat(out.key).isNull()
            assertThat(out.rowIndex).isEqualTo(-1)
            assertThat(out.keyIndex).isEqualTo(-1)
            assertThat(out.score(0, 1)).isEqualTo(0.0)
        }

        @Test
        fun `rejette une géométrie d'une autre disposition`() {
            val qwerty = KeyboardGeometry.compute(KeyboardLayouts.QWERTY, 1000f, 100f, 0f)

            assertThatThrownBy { TapDecoder(azerty).decode(1f, 1f, qwerty, rest, null, TapDecoding(azerty)) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { TapDecoder(azerty).decode(1f, 1f, rest, qwerty, null, TapDecoding(azerty)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejette des géométries de dimensions différentes`() {
            for (other in listOf(
                KeyboardGeometry.compute(azerty, 999f, 100f, 0f),
                KeyboardGeometry.compute(azerty, 1000f, 99f, 0f),
                KeyboardGeometry.compute(azerty, 1000f, 100f, 1f),
            )) {
                assertThatThrownBy { TapDecoder(azerty).decode(1f, 1f, other, rest, null, TapDecoding(azerty)) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("dimensions")
            }
        }

        @Test
        fun `rejette des poids ou un résultat d'une autre disposition`() {
            val decoder = TapDecoder(azerty)

            assertThatThrownBy { decoder.decode(1f, 1f, rest, rest, KeyWeights(KeyboardLayouts.QWERTY), TapDecoding(azerty)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("poids")
            assertThatThrownBy { decoder.decode(1f, 1f, rest, rest, null, TapDecoding(KeyboardLayouts.QWERTY)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("résultat")
        }

        @Test
        fun `rejette une touche inexistante pour un score`() {
            val result = decode(150f, 50f)

            assertThatThrownBy { result.score(3, 0) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
            assertThatThrownBy { result.score(0, 10) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
            assertThatThrownBy { result.score(0, -1) }.isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("inexistante")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `à score égal, la touche sous le doigt l'emporte`() {
            val result = decode(100f, 50f)

            assertThat(result.score(0, 0)).isEqualTo(result.score(0, 1))
            assertThat(result.key?.label).isEqualTo("z")
        }

        @Test
        fun `bord du noyau, inclus`() {
            // Noyau de z : 150 ± 25 px.
            assertThat(decode(125f, 50f, weights('a' to 1f, 'z' to 0f)).decision).isEqualTo(TapDecision.CORE)
            assertThat(decode(124f, 50f, weights('a' to 1f, 'z' to 0f)).decision).isEqualTo(TapDecision.SCORE)
            assertThat(decode(150f, 25f, weights('a' to 1f, 'z' to 0f)).decision).isEqualTo(TapDecision.CORE)
            assertThat(decode(150f, 24f, weights('a' to 1f, 'z' to 0f)).decision).isEqualTo(TapDecision.SCORE)
        }

        @Test
        fun `noyau nul, le score décide même au centre`() {
            val result = decode(150f, 50f, weights('a' to 1f, 'e' to 1f, 'z' to 0f), TapDecoderConfig(coreFraction = 0f))

            assertThat(result.decision).isEqualTo(TapDecision.SCORE)
            assertThat(result.key?.label).isEqualTo("z")
        }

        @Test
        fun `noyau total, toute la touche est garantie`() {
            val result = decode(101f, 1f, weights('a' to 1f, 'z' to 0f), TapDecoderConfig(coreFraction = 1f))

            assertThat(result.key?.label).isEqualTo("z")
            assertThat(result.decision).isEqualTo(TapDecision.CORE)
        }

        @Test
        fun `un poids nul vaut le plancher minPrior`() {
            val zero = decode(95f, 50f, weights('a' to 0f, 'z' to 1f))
            val floor = decode(95f, 50f, weights('a' to 0.05f, 'z' to 1f))
            val above = decode(95f, 50f, weights('a' to 0.06f, 'z' to 1f))

            assertThat(zero.score(0, 0)).isEqualTo(floor.score(0, 0))
            assertThat(above.score(0, 0)).isGreaterThan(floor.score(0, 0))
        }

        @Test
        fun `une lettre au repos a un a priori neutre`() {
            val layout = AdaptFixtures.WITH_FOREIGN_LETTER
            val geometry = KeyboardGeometry.compute(layout, 1000f, 100f, 0f)
            val w = weights('d' to 0.05f, 'f' to 0.05f, layout = layout)

            val result = decode(410f, 50f, w, displayed = geometry, restGeometry = geometry)
            val neutral = decode(410f, 50f, null, displayed = geometry, restGeometry = geometry)

            assertThat(result.score(0, 4)).isEqualTo(neutral.score(0, 4))
            assertThat(result.score(0, 3)).isLessThan(neutral.score(0, 3))
        }

        @Test
        fun `coins extrêmes du clavier`() {
            assertThat(decode(0f, 0f).key?.label).isEqualTo("a")
            assertThat(decode(1000f, 300f).key?.kind).isEqualTo(KeyKind.BACKSPACE)
            assertThat(decode(1000.5f, 300f).key).isNull()
        }
    }
}
