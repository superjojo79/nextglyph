package nextglyph.tools.analysis

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRow
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.layout.LayoutKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

class GaussianTapModelTest {

    private val qwerty = GaussianTapModel(KeyboardLayouts.QWERTY, 0.2)
    private val azerty = GaussianTapModel(KeyboardLayouts.AZERTY, 0.2)

    private fun layout(vararg rows: KeyRow) = KeyboardLayout(LayoutKind.QWERTY, rows.toList())

    /** Rangée 0 : « a » [0 ; 2[, trou [2 ; 8[, « b » [8 ; 10[ ; rangée 1 sans lettre ; rangée 2 : « c » [0 ; 5[, « d » [5 ; 10[. */
    private val gaps = GaussianTapModel(
        layout(
            KeyRow(listOf(Key.letter('a', 2f), Key.character('1', 6f), Key.letter('b', 2f))),
            KeyRow(listOf(Key(KeyKind.SPACE, "", 10f))),
            KeyRow(listOf(Key.letter('c', 5f), Key.letter('d', 5f))),
        ),
        0.2,
    )

    /** Mêmes tirages que le modèle : Box-Muller, première valeur de `random` pour `u`, seconde pour l'angle. */
    private fun gaussian(random: Random): Double {
        val u = 1.0 - random.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * random.nextDouble())
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `le centre de repos d'une touche donne sa lettre`() {
            // QWERTY : rangée 0, « q » = [0 ; 1[, « w » = [1 ; 2[ ; rangée 1 : inset 0,3, « a » = [0,3 ; 1,25[.
            assertThat(qwerty.letterAt(0.5, 0.0)).isEqualTo('q')
            assertThat(qwerty.letterAt(1.5, 0.0)).isEqualTo('w')
            assertThat(qwerty.letterAt(0.775, 1.0)).isEqualTo('a')
            assertThat(azerty.letterAt(0.5, 0.0)).isEqualTo('a')
            assertThat(azerty.letterAt(9.5, 1.0)).isEqualTo('m')
        }

        @Test
        fun `les écarts de la frappe sont relatifs à la touche tapée`() {
            val random = Random(1)

            repeat(500) {
                val tap = qwerty.tap('g', random)!!

                assertThat(tap.dx).isBetween(-0.5, 0.5)
                assertThat(tap.dy).isBetween(-0.5, 0.5)
                assertThat(tap.letter).isIn(('a'..'z').toList())
            }
        }

        @Test
        fun `le taux de lettres fausses croît avec sigma et reste proche de la théorie`() {
            val low = wrongRate(GaussianTapModel(KeyboardLayouts.QWERTY, 0.1))
            val mid = wrongRate(qwerty)
            val high = wrongRate(GaussianTapModel(KeyboardLayouts.QWERTY, 0.3))

            assertThat(low).isLessThan(mid)
            assertThat(mid).isLessThan(high)
            // σ = 0,2 largeur : sortie horizontale ≈ 2·(1 − Φ(2,5)) ≈ 1,2 %, verticale ≈ 1,2 % : quelques %.
            assertThat(mid).isBetween(0.01, 0.12)
        }

        @Test
        fun `la frappe est reproductible avec la même graine`() {
            val a = qwerty.type("bonjour", Random(7))
            val b = qwerty.type("bonjour", Random(7))

            assertThat(a.typed).isEqualTo(b.typed)
            assertThat(a.taps).isEqualTo(b.taps)
        }

        @Test
        fun `la frappe est le centre de repos plus le tirage gaussien fois sigma et la largeur de la touche`() {
            val model = GaussianTapModel(layout(KeyRow(listOf(Key.letter('a', 5f), Key.letter('b', 5f)))), 0.01)
            val reference = Random(42)
            val gx = gaussian(reference)
            val gy = gaussian(reference)

            val tap = model.tap('b', Random(42))!!

            assertThat(tap.letter).isEqualTo('b')
            assertThat(tap.dx).isCloseTo(gx * 0.01, org.assertj.core.data.Offset.offset(1e-12))
            assertThat(tap.dy).isCloseTo(gy * 0.01, org.assertj.core.data.Offset.offset(1e-12))
        }

        @Test
        fun `un point hors de toute lettre donne la lettre la plus proche en tenant compte de la rangée`() {
            // Trou de la rangée 0 : « a » (centre 1) ou « b » (centre 9).
            assertThat(gaps.letterAt(3.0, 0.0)).isEqualTo('a')
            assertThat(gaps.letterAt(7.0, 0.0)).isEqualTo('b')
            // Rangée 1 sans lettre : la distance verticale décide quand l'écart horizontal seul dirait l'inverse.
            assertThat(gaps.letterAt(8.2, 0.6)).isEqualTo('b')
            assertThat(gaps.letterAt(8.6, 1.4)).isEqualTo('d')
            assertThat(gaps.letterAt(9.0, 0.6)).isEqualTo('b')
            assertThat(gaps.letterAt(0.5, 1.0)).isEqualTo('a')
            assertThat(gaps.letterAt(7.0, 1.4)).isEqualTo('d')
        }

        private fun wrongRate(model: GaussianTapModel): Double {
            val random = Random(3)
            val letters = "etaoinshrdlu"
            var wrong = 0
            val n = 20_000
            repeat(n) { if (model.tap(letters[it % letters.length], random)!!.letter != letters[it % letters.length]) wrong++ }
            return wrong.toDouble() / n
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette un écart-type négatif`() {
            assertThatThrownBy { GaussianTapModel(KeyboardLayouts.QWERTY, -0.1) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("écart-type")
        }

        @Test
        fun `rejette un écart-type infini ou non numérique`() {
            assertThatThrownBy { GaussianTapModel(KeyboardLayouts.QWERTY, Double.POSITIVE_INFINITY) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { GaussianTapModel(KeyboardLayouts.QWERTY, Double.NaN) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejette une disposition sans lettre a-z`() {
            assertThatThrownBy { GaussianTapModel(layout(KeyRow(listOf(Key.character('1', 10f)))), 0.2) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("aucune lettre")
        }

        @Test
        fun `seules les touches lettre a-z de la disposition sont tapées`() {
            val model = GaussianTapModel(
                layout(
                    KeyRow(listOf(Key.letter('`', 2.5f), Key.letter('a', 2.5f), Key.letter('z', 2.5f), Key.letter('{', 2.5f))),
                    KeyRow(listOf(Key.letter('é', 2.5f), Key.character('b', 2.5f), Key.letter('y', 5f))),
                ),
                0.0,
            )

            assertThat(model.tap('`', Random(1))).isNull()
            assertThat(model.tap('{', Random(1))).isNull()
            assertThat(model.tap('é', Random(1))).isNull()
            assertThat(model.tap('b', Random(1))).isNull()
            assertThat(model.tap('a', Random(1))!!.letter).isEqualTo('a')
            assertThat(model.tap('z', Random(1))!!.letter).isEqualTo('z')
            assertThat(model.tap('y', Random(1))!!.letter).isEqualTo('y')
        }

        @Test
        fun `les caractères hors a-z sont tapés tels quels sans position`() {
            val word = qwerty.type("don't-é", Random(2))

            assertThat(word.typed).hasSize(7)
            assertThat(word.typed[3]).isEqualTo('\'')
            assertThat(word.typed[5]).isEqualTo('-')
            assertThat(word.typed[6]).isEqualTo('é')
            assertThat(word.taps[3]).isNull()
            assertThat(word.taps[5]).isNull()
            assertThat(word.taps[6]).isNull()
            assertThat(word.taps[0]).isNotNull()
            assertThat(qwerty.tap('é', Random(2))).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `sigma nul tape exactement la lettre voulue au centre de la touche`() {
            val model = GaussianTapModel(KeyboardLayouts.AZERTY, 0.0)

            val word = model.type("azertyuiopqsdfghjklmwxcvbn", Random(5))

            assertThat(word.typed).isEqualTo("azertyuiopqsdfghjklmwxcvbn")
            assertThat(word.taps.filterNotNull()).allSatisfy {
                assertThat(it.dx).isZero()
                assertThat(it.dy).isZero()
            }
        }

        @Test
        fun `les rangées sans lettre sont ignorées et le point est borné aux rangées de lettres`() {
            assertThat(gaps.letterAt(0.5, -5.0)).isEqualTo('a')
            assertThat(gaps.letterAt(9.5, 9.0)).isEqualTo('d')
            assertThat(gaps.letterAt(0.5, 2.0)).isEqualTo('c')
            assertThat(gaps.letterAt(4.999, 2.0)).isEqualTo('c')
            assertThat(gaps.letterAt(5.0, 2.0)).isEqualTo('d')
            assertThat(gaps.letterAt(100.0, 2.0)).isEqualTo('d')
        }

        @Test
        fun `sigma est conservé et la frappe d'une lettre en double vise sa première touche`() {
            val model = GaussianTapModel(
                layout(KeyRow(listOf(Key.letter('a', 5f), Key.letter('b', 5f))), KeyRow(listOf(Key.letter('b', 5f), Key.letter('a', 5f)))),
                0.0,
            )

            val tap = model.tap('a', Random(1))!!

            assertThat(model.sigma).isEqualTo(0.0)
            assertThat(GaussianTapModel(KeyboardLayouts.QWERTY, 0.37).sigma).isEqualTo(0.37)
            assertThat(tap.letter).isEqualTo('a')
            assertThat(tap.dx).isZero()
            assertThat(tap.dy).isZero()
            assertThat(model.letterAt(7.5, 1.0)).isEqualTo('a')
            assertThat(model.letterAt(7.5, 0.0)).isEqualTo('b')
        }

        @Test
        fun `un mot vide donne une frappe vide`() {
            val word = qwerty.type("", Random(1))

            assertThat(word.typed).isEmpty()
            assertThat(word.taps).isEmpty()
        }

        @Test
        fun `les arêtes des touches appartiennent à la touche de droite`() {
            assertThat(qwerty.letterAt(1.0, 0.0)).isEqualTo('w')
            assertThat(qwerty.letterAt(0.9999, 0.0)).isEqualTo('q')
            assertThat(qwerty.letterAt(0.3, 1.0)).isEqualTo('a')
        }

        @Test
        fun `y est arrondi à la rangée la plus proche et borné aux rangées de lettres`() {
            assertThat(qwerty.letterAt(0.5, 0.49)).isEqualTo('q')
            assertThat(qwerty.letterAt(0.5, 0.5)).isEqualTo(qwerty.letterAt(0.5, 1.0))
            assertThat(qwerty.letterAt(1.5, -3.0)).isEqualTo('w')
            assertThat(qwerty.letterAt(2.5, 40.0)).isEqualTo(qwerty.letterAt(2.5, 2.0))
        }

        @Test
        fun `x est borné à la première et à la dernière lettre de la rangée`() {
            assertThat(qwerty.letterAt(-5.0, 0.0)).isEqualTo('q')
            assertThat(qwerty.letterAt(50.0, 0.0)).isEqualTo('p')
            // Rangée 1 QWERTY : retrait de 0,3 avant « a », touche entrée après « l ».
            assertThat(qwerty.letterAt(0.0, 1.0)).isEqualTo('a')
            assertThat(qwerty.letterAt(50.0, 1.0)).isEqualTo('l')
            // Rangée 3 : « ?123 » avant la première lettre, espace après la dernière.
            assertThat(qwerty.letterAt(0.2, 2.0)).isEqualTo('z')
            assertThat(qwerty.letterAt(50.0, 2.0)).isEqualTo('m')
            assertThat(azerty.letterAt(50.0, 2.0)).isEqualTo('n')
        }

        @Test
        fun `l'écart-type empirique des écarts suit sigma quand les sorties de touche sont rares`() {
            val model = GaussianTapModel(KeyboardLayouts.QWERTY, 0.05)
            val random = Random(11)
            val dx = List(10_000) { model.tap('g', random)!!.dx }
            val mean = dx.average()
            val sd = sqrt(dx.sumOf { (it - mean) * (it - mean) } / dx.size)

            assertThat(sd).isBetween(0.045, 0.055)
            assertThat(mean).isBetween(-0.005, 0.005)
        }
    }
}
