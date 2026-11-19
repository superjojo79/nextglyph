package nextglyph.engine.adapt

import nextglyph.engine.adapt.AdaptFixtures.LAYOUTS
import nextglyph.engine.adapt.AdaptFixtures.assertInvariants
import nextglyph.engine.adapt.AdaptFixtures.centers
import nextglyph.engine.adapt.AdaptFixtures.randomWeights
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class LayoutSolverTest {

    private val eps = Offset.offset(1e-4f)

    private fun solve(
        weights: KeyWeights,
        config: AdaptationConfig = AdaptationConfig(),
        layout: KeyboardLayout = weights.layout,
    ): AdaptedLayout {
        val out = AdaptedLayout(layout)
        LayoutSolver(layout, config).solve(weights, out)
        return out
    }

    /** Poids : [likely] à 1, toutes les autres lettres à 0. */
    private fun weights(layout: KeyboardLayout, likely: String, end: Float = 0f): KeyWeights {
        val weights = KeyWeights(layout)
        for (c in 'a'..'z') weights.setLetterWeight(c, if (c in likely) 1f else 0f)
        weights.setEndProbability(end)
        return weights
    }

    private val elastic = AdaptationConfig(mode = AdaptationMode.ELASTIC)

    @Nested
    inner class HappyPath {

        @Test
        fun `le mode par défaut est Calme renforcé`() {
            assertThat(LayoutSolver(KeyboardLayouts.AZERTY).config.mode).isEqualTo(AdaptationMode.CALM_REINFORCED)
        }

        @Test
        fun `une lettre probable s'élargit et une lettre improbable rétrécit`() {
            val out = solve(weights(KeyboardLayouts.AZERTY, "e"), elastic)

            // Rangée 1 : a z e r t y u i o p
            assertThat(out.width(0, 2)).isGreaterThan(1f)
            assertThat(out.width(0, 9)).isLessThan(1f)
        }

        @Test
        fun `opacité et libellé suivent les formules en mode élastique`() {
            val weights = KeyWeights(KeyboardLayouts.AZERTY)
            weights.setLetterWeight('a', 0.25f)
            weights.setLetterWeight('z', 0f)
            weights.setLetterWeight('e', 1f)

            val out = solve(weights, elastic)

            // r = 0,25 : s = 0,5 ; opacité 0,55 + 0,45 · 0,5 ; libellé 0,7 + 0,3 · 0,5.
            assertThat(out.opacity(0, 0)).isCloseTo(0.775f, eps)
            assertThat(out.labelScale(0, 0)).isCloseTo(0.85f, eps)
            assertThat(out.opacity(0, 1)).isCloseTo(0.30f, eps)
            assertThat(out.labelScale(0, 1)).isCloseTo(0.7f, eps)
            assertThat(out.opacity(0, 2)).isEqualTo(1f)
            assertThat(out.labelScale(0, 2)).isEqualTo(1f)
            // Lettre non renseignée et touches de fonction : au repos.
            assertThat(out.opacity(0, 3)).isEqualTo(1f)
            assertThat(out.opacity(2, 0)).isEqualTo(1f)
            assertThat(out.labelScale(2, 0)).isEqualTo(1f)
        }

        @Test
        fun `les intensités du mode et l'intensité des poids atténuent opacité et libellé`() {
            val weights = KeyWeights(KeyboardLayouts.AZERTY)
            weights.setLetterWeight('a', 0f)
            weights.setIntensity(0.5f)

            val calmReinforced = solve(weights)
            val calm = solve(weights, AdaptationConfig(mode = AdaptationMode.CALM))

            // 1 − 0,8 · 0,5 · 0,7 = 0,72 ; libellé 1 − 0,4 · 0,5 · 0,3 = 0,94.
            assertThat(calmReinforced.opacity(0, 0)).isCloseTo(0.72f, eps)
            assertThat(calmReinforced.labelScale(0, 0)).isCloseTo(0.94f, eps)
            // Calme : 1 − 0,5 · 0,5 · 0,7 = 0,825, libellé et largeurs au repos.
            assertThat(calm.opacity(0, 0)).isCloseTo(0.825f, eps)
            assertThat(calm.labelScale(0, 0)).isEqualTo(1f)
            assertThat(calm.widthsByRow()).isEqualTo(KeyboardLayouts.AZERTY.restWidths())
        }

        @Test
        fun `sans plafond actif, les largeurs suivent la formule normalisée`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, maxDisplacement = 1f)
            val weights = KeyWeights(KeyboardLayouts.AZERTY)
            // Rangée 1 : a et p probables, le reste nul ; formule 1,2 et 0,6.
            for (c in "azertyuiop") weights.setLetterWeight(c, if (c == 'a' || c == 'p') 1f else 0f)

            val out = solve(weights, config)

            val scale = 10.0 / (2 * 1.2 + 8 * 0.6)
            assertThat(out.width(0, 0).toDouble()).isCloseTo(1.2 * scale, Offset.offset(1e-4))
            assertThat(out.width(0, 9).toDouble()).isCloseTo(1.2 * scale, Offset.offset(1e-4))
            assertThat(out.width(0, 5).toDouble()).isCloseTo(0.6 * scale, Offset.offset(1e-4))
        }

        @Test
        fun `l'espace grandit avec P(fin) et les lettres de sa rangée compensent`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, maxDisplacement = 1f)
            val weights = KeyWeights(KeyboardLayouts.AZERTY)
            for (c in 'a'..'z') weights.setLetterWeight(c, 1f)
            weights.setEndProbability(1f)

            val out = solve(weights, config)

            // AZERTY rangée 3 : ?123 w x c v b n espace ↵ ⌫
            assertThat(out.width(2, 7)).isCloseTo(1.6f + 0.6f, eps)
            assertThat(out.width(2, 1)).isCloseTo(0.9f - 0.1f, eps)
            assertThat(out.width(2, 0)).isEqualTo(1f)
            assertThat(out.width(2, 8)).isEqualTo(1f)
            assertThat(out.width(2, 9)).isEqualTo(1f)
            assertThat(out.width(0, 0)).isCloseTo(1f, eps)
        }

        @Test
        fun `le plafond de déplacement limite l'élargissement en mode élastique`() {
            val out = solve(weights(KeyboardLayouts.AZERTY, "a"), elastic)

            // a est en bord de rangée : son centre ne peut avancer que de 0,5 u, donc 2 u au plus.
            assertThat(out.width(0, 0)).isLessThanOrEqualTo(2f)
            assertInvariants(out, elastic, 0.5f)
        }

        @Test
        fun `le plafond faible du mode Calme renforcé est respecté`() {
            val config = AdaptationConfig()
            val out = solve(weights(KeyboardLayouts.AZERTY, "ap"), config)

            assertInvariants(out, config, 0.15f)
            assertThat(out.width(0, 0)).isGreaterThan(1f)
            assertThat(out.width(0, 0)).isLessThanOrEqualTo(1.3f)
        }

        @Test
        fun `propriété, garanties du solveur sur 3000 distributions aléatoires`() {
            val random = Random(20261119)
            val modes = AdaptationMode.entries
            repeat(3000) { n ->
                val layout = LAYOUTS[n % 2]
                val mode = modes[n % modes.size]
                val config = AdaptationConfig(
                    mode = mode,
                    maxDisplacement = if (n % 7 == 0) random.nextFloat() else 0.5f,
                    spaceGain = random.nextFloat() * AdaptationConfig.MAX_SPACE_GAIN,
                    gain = random.nextFloat(),
                )
                val weights = randomWeights(layout, random, intensity = if (n % 5 == 0) random.nextFloat() else 1f)

                val out = solve(weights, config)

                assertInvariants(out, config, config.displacement(mode))
            }
        }

        @Test
        fun `propriété, une touche de fonction au milieu d'une rangée garde sa largeur`() {
            val layout = KeyboardLayout(
                nextglyph.engine.layout.LayoutKind.AZERTY,
                listOf(
                    nextglyph.engine.layout.KeyRow(
                        "abc".map { nextglyph.engine.layout.Key.letter(it) } +
                            nextglyph.engine.layout.Key(KeyKind.BACKSPACE, "⌫", 1f) +
                            "defghi".map { nextglyph.engine.layout.Key.letter(it) },
                    ),
                ),
            )
            val random = Random(5)
            repeat(500) {
                val out = AdaptedLayout(layout)

                LayoutSolver(layout, elastic).solve(randomWeights(layout, random), out)

                assertInvariants(out, elastic, 0.5f)
            }
            val pushed = solve(weights(layout, "abc"), elastic, layout)
            assertThat(pushed.width(0, 3)).isCloseTo(1f, Offset.offset(1e-6f))
            assertThat(pushed.width(0, 0)).isGreaterThan(1f)
        }

        @Test
        fun `propriété, la topologie est immuable (ordre et rangée)`() {
            val random = Random(42)
            repeat(500) { n ->
                val layout = LAYOUTS[n % 2]
                val out = solve(randomWeights(layout, random), elastic, layout)

                val geometry = nextglyph.engine.layout.KeyboardGeometry.compute(layout, 1000f, 100f, 0f, out.widthsByRow())
                geometry.rows.forEachIndexed { r, rects ->
                    assertThat(rects.map { it.key }).isEqualTo(layout.rows[r].keys)
                    rects.zipWithNext().forEach { (left, right) -> assertThat(left.right).isLessThanOrEqualTo(right.left + 1e-3f) }
                }
            }
        }

        @Test
        fun `propriété, déterminisme, mêmes entrées même sortie`() {
            val random = Random(7)
            repeat(300) { n ->
                val layout = LAYOUTS[n % 2]
                val weights = randomWeights(layout, random)
                val solver = LayoutSolver(layout, elastic)
                val first = AdaptedLayout(layout)
                val second = AdaptedLayout(layout)

                solver.solve(weights, first)
                solver.solve(randomWeights(layout, random), second)
                solver.solve(weights, second)

                assertThat(second.contentEquals(first)).isTrue()
            }
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `rejette des poids d'une autre disposition`() {
            val solver = LayoutSolver(KeyboardLayouts.AZERTY)

            assertThatThrownBy { solver.solve(KeyWeights(KeyboardLayouts.QWERTY), AdaptedLayout(KeyboardLayouts.AZERTY)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("poids")
        }

        @Test
        fun `rejette une sortie d'une autre disposition`() {
            val solver = LayoutSolver(KeyboardLayouts.AZERTY)

            assertThatThrownBy { solver.solve(KeyWeights(KeyboardLayouts.AZERTY), AdaptedLayout(KeyboardLayouts.QWERTY)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("sortie")
        }

        @Test
        fun `une espace trop large pour sa rangée est ramenée dans les contraintes`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, spaceGain = 2f, maxDisplacement = 1f, minWidth = 1f)
            val weights = weights(KeyboardLayouts.AZERTY, "", end = 1f)

            val out = solve(weights, config)

            // Lettres au minimum (largeur de repos) : l'espace ne peut pas grandir.
            assertInvariants(out, config, 1f)
            assertThat(out.width(2, 7)).isCloseTo(1.6f, eps)
        }
    }

    @Nested
    inner class BoundaryValues {

        @ParameterizedTest
        @EnumSource(AdaptationMode::class)
        fun `état neutre, exactement la disposition au repos dans tous les modes`(mode: AdaptationMode) {
            for (layout in LAYOUTS) {
                val out = AdaptedLayout(layout)
                LayoutSolver(layout, AdaptationConfig(mode = mode)).solve(randomWeights(layout, Random(1)), out)

                LayoutSolver(layout, AdaptationConfig(mode = mode)).solve(KeyWeights(layout), out)

                assertThat(out.isRest).isTrue()
                assertThat(out.widthsByRow()).isEqualTo(layout.restWidths())
            }
        }

        @Test
        fun `une sortie réutilisée en mode Calme revient aux largeurs de repos`() {
            val weights = weights(KeyboardLayouts.AZERTY, "e", end = 1f)
            val out = solve(weights, elastic)

            LayoutSolver(KeyboardLayouts.AZERTY, AdaptationConfig(mode = AdaptationMode.CALM)).solve(weights, out)

            assertThat(out.widthsByRow()).isEqualTo(KeyboardLayouts.AZERTY.restWidths())
        }

        @Test
        fun `mode Désactivé, repos quels que soient les poids`() {
            val out = solve(weights(KeyboardLayouts.AZERTY, "e", end = 1f), AdaptationConfig(mode = AdaptationMode.OFF))

            assertThat(out.isRest).isTrue()
        }

        @Test
        fun `une touche au repos garde l'opacité 1 même si la formule plafonne en dessous`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, opacityBase = 0.5f, opacitySlope = 0.4f, minLabelScale = 0.5f)
            val weights = KeyWeights(KeyboardLayouts.AZERTY)
            weights.setLetterWeight('e', 1f)

            val out = solve(weights, config)

            assertThat(out.opacity(0, 2)).isCloseTo(0.9f, eps)
            assertThat(out.opacity(0, 0)).isEqualTo(1f)
            assertThat(out.labelScale(0, 0)).isEqualTo(1f)
        }

        @Test
        fun `intensité nulle, repos`() {
            val weights = weights(KeyboardLayouts.AZERTY, "e", end = 1f)
            weights.setIntensity(0f)

            assertThat(solve(weights, elastic).isRest).isTrue()
        }

        @Test
        fun `poids uniformes, largeurs de repos à l'arrondi près`() {
            val weights = weights(KeyboardLayouts.QWERTY, "abcdefghijklmnopqrstuvwxyz")

            val out = solve(weights, elastic)

            KeyboardLayouts.QWERTY.rows.forEachIndexed { r, row ->
                row.keys.indices.forEach { k -> assertThat(out.width(r, k)).isCloseTo(row.keys[k].restWidth, eps) }
            }
        }

        @Test
        fun `tous les poids nuls, largeurs de repos, opacité minimale`() {
            val out = solve(weights(KeyboardLayouts.AZERTY, ""), elastic)

            assertThat(out.width(0, 4)).isCloseTo(1f, eps)
            assertThat(out.opacity(0, 4)).isCloseTo(0.30f, eps)
        }

        @Test
        fun `plafond nul en mode élastique, largeurs de repos`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, maxDisplacement = 0f)

            val out = solve(weights(KeyboardLayouts.AZERTY, "e", end = 1f), config)

            assertThat(out.widthsByRow()).isEqualTo(KeyboardLayouts.AZERTY.restWidths())
            assertThat(out.opacity(0, 0)).isLessThan(1f)
        }

        @Test
        fun `plafond atteint exactement à la marge numérique près`() {
            val out = solve(weights(KeyboardLayouts.AZERTY, "a"), elastic)

            val shown = centers(KeyboardLayouts.AZERTY, 0, out.widthsByRow()[0])
            val rest = centers(KeyboardLayouts.AZERTY, 0, KeyboardLayouts.AZERTY.rows[0].restWidths)
            val maxShift = shown.indices.maxOf { abs(shown[it] - rest[it]) }
            assertThat(maxShift).isLessThanOrEqualTo(0.5).isGreaterThan(0.5 - 1e-4)
        }

        @Test
        fun `largeur minimale atteinte pour une lettre de poids nul entourée de lettres probables`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, maxDisplacement = 1f)
            val weights = weights(KeyboardLayouts.AZERTY, "azertyuio")

            val out = solve(weights, config)

            assertThat(out.width(0, 9).toDouble()).isCloseTo(0.6, Offset.offset(1e-5))
            assertInvariants(out, config, 1f)
        }

        @Test
        fun `une lettre hors de l'alphabet reste au repos en largeur cible`() {
            val layout = AdaptFixtures.WITH_FOREIGN_LETTER
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, maxDisplacement = 1f)
            val weights = KeyWeights(layout)
            for (c in "abcdfghi") weights.setLetterWeight(c, if (c == 'a') 1f else 0.25f)

            val out = solve(weights, config, layout)

            assertThat(weights.isAtRest(0, 4)).isTrue()
            assertThat(out.width(0, 4)).isCloseTo(1f, eps)
            assertThat(out.opacity(0, 4)).isEqualTo(1f)
            assertThat(out.width(0, 9)).isEqualTo(1f)
            assertInvariants(out, config, 1f)
        }

        @Test
        fun `rangée sans lettre adaptée, l'espace reste au repos`() {
            val weights = KeyWeights(KeyboardLayouts.AZERTY)
            weights.setLetterWeight('a', 1f)
            weights.setEndProbability(1f)

            val out = solve(weights, AdaptationConfig(mode = AdaptationMode.ELASTIC, maxDisplacement = 1f))

            assertThat(out.width(2, 7)).isEqualTo(1.6f)
            assertThat(KeyboardLayouts.AZERTY.rows[2].keys[7].kind).isEqualTo(KeyKind.SPACE)
        }

        @Test
        fun `formule de largeur, la racine du poids pilote la cible`() {
            val config = AdaptationConfig(mode = AdaptationMode.ELASTIC, maxDisplacement = 1f)
            val weights = KeyWeights(KeyboardLayouts.AZERTY)
            for (c in "azertyuiop") weights.setLetterWeight(c, if (c == 'a') 0.25f else 1f)

            val out = solve(weights, config)

            val raw = 0.8 + 0.4 * sqrt(0.25)
            val scale = 10.0 / (raw + 9 * 1.2)
            assertThat(out.width(0, 0).toDouble()).isCloseTo(raw * scale, Offset.offset(1e-4))
        }
    }
}
