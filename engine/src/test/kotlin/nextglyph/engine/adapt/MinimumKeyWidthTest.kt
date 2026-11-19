package nextglyph.engine.adapt

import nextglyph.engine.adapt.AdaptFixtures.LAYOUTS
import nextglyph.engine.adapt.AdaptFixtures.assertInvariants
import nextglyph.engine.adapt.AdaptFixtures.randomWeights
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.min
import kotlin.random.Random

/** Taille minimale des touches après adaptation (spec §13 : zones de tap d'au moins 32 dp). */
class MinimumKeyWidthTest {

    /** Poids extrêmes : le `e` (touche intérieure de la rangée 1) à 0, toutes les autres lettres à 1. */
    private fun weights(layout: KeyboardLayout): KeyWeights {
        val weights = KeyWeights(layout)
        for (c in 'a'..'z') weights.setLetterWeight(c, if (c == 'e') 0f else 1f)
        weights.setEndProbability(1f)
        return weights
    }

    private fun solver(layout: KeyboardLayout, widthDp: Float, config: AdaptationConfig) =
        LayoutSolver(layout, config).also { it.keyboardWidthDp = widthDp }

    /** Plus petite largeur d'une lettre de la rangée [row], en dp, sur [samples] dispositions. */
    private fun smallestLetterDp(layout: KeyboardLayout, widthDp: Float, config: AdaptationConfig, row: Int, samples: Int = 400): Float {
        val solver = solver(layout, widthDp, config)
        val out = AdaptedLayout(layout)
        val random = Random(17)
        var smallest = Float.MAX_VALUE
        repeat(samples) { n ->
            solver.solve(if (n == 0) weights(layout) else randomWeights(layout, random), out)
            for (k in layout.rows[row].keys.indices) {
                if (layout.rows[row].keys[k].kind == KeyKind.LETTER) smallest = min(smallest, out.width(row, k) * widthDp / 10f)
            }
        }
        return smallest
    }

    private fun restLetterDp(layout: KeyboardLayout, widthDp: Float, row: Int): Float =
        layout.rows[row].keys.first { it.kind == KeyKind.LETTER }.restWidth * widthDp / 10f

    @Nested
    inner class HappyPath {

        @ParameterizedTest(name = "{0} dp")
        @ValueSource(floats = [360f, 411f])
        fun `en Calme renforcé, les lettres des rangées 1 et 2 gardent 32 dp`(widthDp: Float) {
            for (layout in LAYOUTS) for (row in 0..1) {
                val smallest = smallestLetterDp(layout, widthDp, AdaptationConfig(), row)

                assertThat(smallest).`as`("${layout.kind} rangée $row à $widthDp dp").isGreaterThanOrEqualTo(32f - 0.01f)
            }
        }

        @ParameterizedTest(name = "{0} dp")
        @ValueSource(floats = [360f, 411f])
        fun `en Calme renforcé, la rangée 3 garde 32 dp`(widthDp: Float) {
            for (layout in LAYOUTS) {
                val smallest = smallestLetterDp(layout, widthDp, AdaptationConfig(), 2)

                assertThat(smallest).`as`("${layout.kind} rangée 3 à $widthDp dp").isGreaterThanOrEqualTo(32f - 0.01f)
            }
        }

        @ParameterizedTest(name = "{0} dp")
        @ValueSource(floats = [360f, 411f])
        fun `au repos, toutes les lettres font au moins 32 dp`(widthDp: Float) {
            for (layout in LAYOUTS) for (row in 0..2) {
                assertThat(restLetterDp(layout, widthDp, row)).`as`("${layout.kind} rangée $row à $widthDp dp").isGreaterThanOrEqualTo(32f)
            }
        }

        @Test
        fun `les garanties du solveur tiennent avec le plancher en dp`() {
            val random = Random(5)
            for (layout in LAYOUTS) for (mode in AdaptationMode.entries) {
                val config = AdaptationConfig(mode = mode)
                val solver = solver(layout, 360f, config)
                val out = AdaptedLayout(layout)
                repeat(500) {
                    solver.solve(randomWeights(layout, random), out)
                    assertInvariants(out, config, config.displacement())
                }
            }
        }

        @Test
        fun `la valeur par défaut est de 32 dp`() {
            assertThat(AdaptationConfig().minKeyWidthDp).isEqualTo(32f)
        }

        @Test
        fun `la largeur du clavier se relit`() {
            val solver = solver(LAYOUTS[0], 411f, AdaptationConfig())

            assertThat(solver.keyboardWidthDp).isEqualTo(411f)
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(floats = [-1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette une largeur de clavier invalide`(value: Float) {
            val solver = LayoutSolver(LAYOUTS[0])

            assertThatThrownBy { solver.keyboardWidthDp = value }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("largeur")
        }

        @ParameterizedTest
        @ValueSource(floats = [-1f, Float.NaN, Float.POSITIVE_INFINITY])
        fun `rejette une largeur minimale en dp invalide`(value: Float) {
            assertThatThrownBy { AdaptationConfig(minKeyWidthDp = value) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("minKeyWidthDp")
        }

        @Test
        fun `le mode Élastique n'applique pas le plancher en dp`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC)
            val smallest = smallestLetterDp(LAYOUTS[0], 360f, config, 0)

            // 0,6 u de repos, soit 21,6 dp à 360 dp : choix explicite de l'utilisateur (§13).
            assertThat(smallest).isLessThan(30f)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `sans largeur connue, aucun plancher en dp`() {
            for (layout in LAYOUTS) {
                val solver = LayoutSolver(layout)
                val out = AdaptedLayout(layout)
                solver.solve(weights(layout), out)

                assertThat(out.width(0, 2)).isLessThan(0.7f)
            }
        }

        @Test
        fun `à 320 dp, une lettre de 1 u vaut exactement 32 dp et ne rétrécit pas`() {
            val layout = LAYOUTS[0]
            val out = AdaptedLayout(layout)
            solver(layout, 320f, AdaptationConfig()).solve(weights(layout), out)

            for (k in 0 until 10) assertThat(out.width(0, k)).isGreaterThanOrEqualTo(1f - 1e-4f)
        }

        @Test
        fun `sur un clavier plus étroit que 32 dp par lettre au repos, aucune lettre ne rétrécit`() {
            for (layout in LAYOUTS) {
                val out = AdaptedLayout(layout)
                solver(layout, 100f, AdaptationConfig()).solve(weights(layout), out)

                layout.rows.forEachIndexed { r, row ->
                    row.keys.forEachIndexed { k, key ->
                        if (key.kind == KeyKind.LETTER) assertThat(out.width(r, k)).isGreaterThanOrEqualTo(key.restWidth - 1e-4f)
                    }
                }
            }
        }

        @Test
        fun `un plancher de 0 dp laisse la largeur minimale relative`() {
            val layout = LAYOUTS[0]
            val config = AdaptationConfig(minKeyWidthDp = 0f)
            val smallest = smallestLetterDp(layout, 360f, config, 0)

            assertThat(smallest).isLessThan(24f)
        }

        @Test
        fun `la largeur minimale relative reste la contrainte quand elle dépasse le plancher`() {
            val layout = LAYOUTS[0]
            val config = AdaptationConfig(minWidth = 0.95f)
            val smallest = smallestLetterDp(layout, 360f, config, 0)

            assertThat(smallest).isGreaterThanOrEqualTo(0.95f * 36f - 0.01f)
        }

        @Test
        fun `revenir à une largeur inconnue supprime le plancher`() {
            val layout = LAYOUTS[0]
            val solver = solver(layout, 360f, AdaptationConfig())
            val out = AdaptedLayout(layout)
            solver.keyboardWidthDp = 0f

            solver.solve(weights(layout), out)

            assertThat(out.width(0, 2)).isLessThan(0.7f)
        }
    }
}
