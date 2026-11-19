package nextglyph.engine.adapt

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRow
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import org.assertj.core.api.Assertions.assertThat
import kotlin.math.abs
import kotlin.random.Random

/** Outils partagés des tests du package `adapt`. */
object AdaptFixtures {

    val LAYOUTS: List<KeyboardLayout> = listOf(KeyboardLayouts.AZERTY, KeyboardLayouts.QWERTY)

    /** Disposition d'essai : une rangée dont une lettre (`é`) est hors de l'alphabet du lexique. */
    val WITH_FOREIGN_LETTER: KeyboardLayout = KeyboardLayout(
        LayoutKind.AZERTY,
        listOf(
            KeyRow("abcdéfghi".map { Key.letter(it) } + Key(KeyKind.BACKSPACE, "⌫", 1f)),
        ),
    )

    /** Poids aléatoires : chaque lettre reçoit un poids, souvent nul ou maximal. */
    fun randomWeights(layout: KeyboardLayout, random: Random, intensity: Float = 1f): KeyWeights {
        val weights = KeyWeights(layout)
        for (c in 'a'..'z') {
            val r = when (random.nextInt(4)) {
                0 -> 0f
                1 -> 1f
                else -> random.nextFloat()
            }
            weights.setLetterWeight(c, r)
        }
        weights.setEndProbability(if (random.nextInt(3) == 0) 1f else random.nextFloat())
        weights.setIntensity(intensity)
        return weights
    }

    /** Centres en unités, calculés en `Double` à partir des largeurs `Float`. */
    fun centers(layout: KeyboardLayout, row: Int, widths: List<Float>): DoubleArray {
        var edge = layout.rows[row].leadingInset.toDouble()
        return DoubleArray(widths.size) { k ->
            val center = edge + widths[k] / 2.0
            edge += widths[k]
            center
        }
    }

    /** Vérifie toutes les garanties du solveur pour [out], avec le plafond [cap]. */
    fun assertInvariants(out: AdaptedLayout, config: AdaptationConfig, cap: Float) {
        val layout = out.layout
        val widths = out.widthsByRow()
        assertThat(widths).hasSize(layout.rows.size)
        layout.rows.forEachIndexed { r, row ->
            assertThat(widths[r]).hasSize(row.keys.size)
            val sum = row.leadingInset.toDouble() + widths[r].sumOf { it.toDouble() }
            assertThat(abs(sum - KeyboardLayout.ROW_UNITS)).isLessThanOrEqualTo(1e-5)
            val restCenters = centers(layout, r, row.restWidths)
            val shownCenters = centers(layout, r, widths[r])
            row.keys.forEachIndexed { k, key ->
                val w = widths[r][k]
                assertThat(w).isPositive()
                when (key.kind) {
                    KeyKind.LETTER -> assertThat(w.toDouble()).isGreaterThanOrEqualTo(config.minWidth * key.restWidth - 1e-6)
                    KeyKind.SPACE -> assertThat(w.toDouble()).isGreaterThanOrEqualTo(key.restWidth - 1e-6)
                    else -> assertThat(abs(w - key.restWidth).toDouble()).isLessThanOrEqualTo(1e-6)
                }
                // Plafond de déplacement : exact, sans tolérance.
                assertThat(abs(shownCenters[k] - restCenters[k])).isLessThanOrEqualTo(cap.toDouble() * key.restWidth)
                assertThat(out.opacity(r, k)).isBetween(0f, 1f)
                assertThat(out.labelScale(r, k)).isBetween(0f, 1f)
            }
        }
        nextglyph.engine.layout.KeyboardGeometry.compute(layout, 1080f, 100f, 10f, widths)
    }
}
