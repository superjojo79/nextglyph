package nextglyph.engine.adapt

import nextglyph.engine.adapt.AdaptFixtures.randomWeights
import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyRow
import nextglyph.engine.layout.KeyboardLayout
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.learn.SpatialModel
import nextglyph.engine.predict.PredictionSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TapSpatialModelTest {

    private val azerty = KeyboardLayouts.AZERTY
    private val rest = KeyboardGeometry.compute(azerty, 1000f, 100f, 0f)

    /** Modèle d'essai : décalages et facteurs fixes pour une lettre. */
    private class Fixed(
        private val letter: Char,
        private val dx: Float = 0f,
        private val dy: Float = 0f,
        private val sx: Float = 1f,
        private val sy: Float = 1f,
    ) : TapSpatialModel {
        override fun offsetX(letter: Char) = if (letter == this.letter) dx else 0f
        override fun offsetY(letter: Char) = if (letter == this.letter) dy else 0f
        override fun sigmaScaleX(letter: Char) = if (letter == this.letter) sx else 1f
        override fun sigmaScaleY(letter: Char) = if (letter == this.letter) sy else 1f
    }

    /** Modèle d'essai : décalages fixes pour des touches de fonction (les lettres restent neutres). */
    private class FixedKeys(private val shifts: Map<KeyKind, Pair<Float, Float>>) : TapSpatialModel {
        constructor(kind: KeyKind, dx: Float = 0f, dy: Float = 0f) : this(mapOf(kind to (dx to dy)))

        override fun offsetX(letter: Char) = 0f
        override fun offsetY(letter: Char) = 0f
        override fun sigmaScaleX(letter: Char) = 1f
        override fun sigmaScaleY(letter: Char) = 1f
        override fun keyOffsetX(kind: KeyKind) = shifts[kind]?.first ?: 0f
        override fun keyOffsetY(kind: KeyKind) = shifts[kind]?.second ?: 0f
    }

    private fun decodeIn(
        x: Float,
        y: Float,
        spatial: TapSpatialModel,
        displayed: KeyboardGeometry = rest,
        restGeometry: KeyboardGeometry = rest,
        config: TapDecoderConfig = TapDecoderConfig(),
    ): TapDecoding {
        val out = TapDecoding(displayed.layout)
        TapDecoder(displayed.layout, config, spatial).decode(x, y, displayed, restGeometry, null, out)
        return out
    }

    private val spaceIndex = azerty.rows[2].keys.indexOfFirst { it.kind == KeyKind.SPACE }
    private val space = rest.rows[2][spaceIndex]
    private val beforeSpace = rest.rows[2][spaceIndex - 1]
    private val row2Y = space.centerY

    private fun decode(x: Float, y: Float, spatial: TapSpatialModel, weights: KeyWeights? = null): TapDecoding {
        val out = TapDecoding(azerty)
        TapDecoder(azerty, TapDecoderConfig(), spatial).decode(x, y, rest, rest, weights, out)
        return out
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `l'espace appris trop à gauche agrandit l'espace aux dépens de la lettre qui le précède`() {
            // Frontière n|espace à space.left ; l'espace appris à -0,2 de sa largeur : frontière 0,1 × largeur plus à gauche.
            val shift = 0.1f * space.width
            val x = space.left - shift / 2

            assertThat(decodeIn(x, row2Y, TapSpatialModel.NONE).key?.kind).isEqualTo(KeyKind.LETTER)

            val learned = decodeIn(x, row2Y, FixedKeys(KeyKind.SPACE, dx = -0.2f))
            assertThat(learned.key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(learned.decision).isEqualTo(TapDecision.FUNCTION_KEY)
            assertThat(decodeIn(space.left - shift - 1f, row2Y, FixedKeys(KeyKind.SPACE, dx = -0.2f)).key).isEqualTo(beforeSpace.key)
        }

        @Test
        fun `l'espace appris trop à droite agrandit la lettre qui le précède`() {
            val shift = 0.1f * space.width
            val x = space.left + shift / 2

            assertThat(decodeIn(x, row2Y, TapSpatialModel.NONE).key?.kind).isEqualTo(KeyKind.SPACE)

            val learned = decodeIn(x, row2Y, FixedKeys(KeyKind.SPACE, dx = 0.2f))
            assertThat(learned.key).isEqualTo(beforeSpace.key)
            assertThat(learned.decision).isNotEqualTo(TapDecision.FUNCTION_KEY)
            assertThat(decodeIn(space.left + shift + 1f, row2Y, FixedKeys(KeyKind.SPACE, dx = 0.2f)).key?.kind).isEqualTo(KeyKind.SPACE)
        }

        @Test
        fun `la frontière est déplacée de la moyenne des décalages des deux touches de fonction`() {
            // Entrée (à droite de l'espace) appris à -0,3 : sa largeur 100 → -30 px ; espace à +0,125 × 160 = +20 px.
            val enter = rest.rows[2][spaceIndex + 1]
            val model = FixedKeys(mapOf(KeyKind.SPACE to (0.125f to 0f), KeyKind.ENTER to (-0.3f to 0f)))
            // Moyenne : (20 - 30) / 2 = -5 px ; bornée à 0,2 × min(160, 100) = 20 px : non bornée.
            val boundary = space.right

            assertThat(decodeIn(boundary - 6f, row2Y, model).key).isEqualTo(space.key)
            assertThat(decodeIn(boundary - 4f, row2Y, model).key).isEqualTo(enter.key)
            assertThat(decodeIn(boundary + 1f, row2Y, model).key).isEqualTo(enter.key)
        }

        @Test
        fun `la frontière entre symboles et la lettre suivante suit le décalage de symboles`() {
            val symbols = rest.rows[2][0]
            val model = FixedKeys(KeyKind.SYMBOLS, dx = 0.3f)
            // Décalage : 0,3 × 100 / 2 = 15 px (sous la borne de 20 px).

            assertThat(decodeIn(symbols.right + 10f, row2Y, model).key).isEqualTo(symbols.key)
            assertThat(decodeIn(symbols.right + 16f, row2Y, model).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `un décalage vertical appris vers la rangée du haut agrandit l'espace vers le haut`() {
            val x = space.centerX
            val y = space.top - 10f

            assertThat(decodeIn(x, y, TapSpatialModel.NONE).key?.kind).isEqualTo(KeyKind.LETTER)

            val learned = decodeIn(x, y, FixedKeys(KeyKind.SPACE, dy = -0.3f))
            // -0,3 × 100 / 2 = -15 px : frontière à y = 185.
            assertThat(learned.key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(x, space.top - 16f, FixedKeys(KeyKind.SPACE, dy = -0.3f)).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `un décalage vertical appris vers le bas agrandit la lettre du dessus`() {
            val x = space.centerX
            val y = space.top + 10f

            assertThat(decodeIn(x, y, TapSpatialModel.NONE).key?.kind).isEqualTo(KeyKind.SPACE)

            assertThat(decodeIn(x, y, FixedKeys(KeyKind.SPACE, dy = 0.3f)).key?.kind).isEqualTo(KeyKind.LETTER)
            assertThat(decodeIn(x, space.top + 16f, FixedKeys(KeyKind.SPACE, dy = 0.3f)).key?.kind).isEqualTo(KeyKind.SPACE)
        }

        @Test
        fun `la frontière verticale est celle de keyAt avec un espacement entre rangées`() {
            val gap = KeyboardGeometry.compute(azerty, 1000f, 100f, 20f)
            val s = gap.rows[2][spaceIndex]
            val model = FixedKeys(KeyKind.SPACE, dy = -0.3f)
            // Rangée 2 à partir de y = 240 (2 × 120) ; frontière déplacée de 15 px, soit y = 225.

            assertThat(decodeIn(s.centerX, 230f, model, gap, gap).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(s.centerX, 224f, model, gap, gap).key?.kind).isEqualTo(KeyKind.LETTER)
            assertThat(decodeIn(s.centerX, 224f, TapSpatialModel.NONE, gap, gap).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `les décalages se lisent sur la géométrie au repos et la frontière sur la géométrie affichée`() {
            // Espace élargi à l'affichage : n plus étroite, frontière n|espace à gauche de celle du repos.
            val widths = azerty.restWidths().toMutableList()
            val row = widths[2].toMutableList()
            row[spaceIndex - 1] = row[spaceIndex - 1] * 0.5f
            row[spaceIndex] = row[spaceIndex] + azerty.restWidths()[2][spaceIndex - 1] * 0.5f
            widths[2] = row
            val shown = KeyboardGeometry.compute(azerty, 1000f, 100f, 0f, widths)
            val shownSpace = shown.rows[2][spaceIndex]
            val model = FixedKeys(KeyKind.SPACE, dx = -0.2f)
            // Décalage selon la largeur au repos (160 px) : -0,2 × 160 / 2 = -16 px, sous la borne 0,2 × min(100, 160) = 20 px.

            assertThat(decodeIn(shownSpace.left - 15f, row2Y, model, shown, rest).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(shownSpace.left - 17f, row2Y, model, shown, rest).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `en QWERTY, le décalage de entrée déplace la frontière avec la dernière lettre de sa rangée`() {
            val qwerty = KeyboardLayouts.QWERTY
            val geometry = KeyboardGeometry.compute(qwerty, 1000f, 100f, 0f)
            val enter = geometry.rows[1].last()
            val letter = geometry.rows[1][geometry.rows[1].size - 2]
            val model = FixedKeys(KeyKind.ENTER, dx = -0.2f)
            // -0,2 × 115 / 2 = -11,5 px, sous la borne 0,2 × min(95, 115) = 19 px.

            assertThat(decodeIn(enter.left - 5f, enter.centerY, model, geometry, geometry).key).isEqualTo(enter.key)
            assertThat(decodeIn(enter.left - 5f, enter.centerY, TapSpatialModel.NONE, geometry, geometry).key).isEqualTo(letter.key)
        }

        @Test
        fun `un modèle vide donne exactement les mêmes décisions et scores que sans modèle`() {
            val random = Random(7)
            val empty = SpatialModel()
            val plain = TapDecoder(azerty)
            val personal = TapDecoder(azerty, TapDecoderConfig(), empty)
            val a = TapDecoding(azerty)
            val b = TapDecoding(azerty)

            repeat(2000) {
                val weights = if (random.nextInt(3) == 0) null else randomWeights(azerty, random)
                val x = random.nextFloat() * 1000f
                val y = random.nextFloat() * 300f
                val okA = plain.decode(x, y, rest, rest, weights, a)
                val okB = personal.decode(x, y, rest, rest, weights, b)

                assertThat(okB).isEqualTo(okA)
                assertThat(b.key).isEqualTo(a.key)
                assertThat(b.decision).isEqualTo(a.decision)
                for (r in 0 until azerty.rows.size) {
                    for (k in 0 until azerty.rows[r].keys.size) assertThat(b.score(r, k)).isEqualTo(a.score(r, k))
                }
            }
        }

        @Test
        fun `le décalage appris déplace la frontière entre deux touches`() {
            // Frappe à 5 px à gauche de la frontière a|z (x = 100) : sans modèle, c'est un a.
            assertThat(decode(95f, 50f, TapSpatialModel.NONE).key?.label).isEqualTo("a")

            // L'utilisateur frappe z 20 % de touche trop à gauche : cette frappe est probablement un z.
            val result = decode(95f, 50f, Fixed('z', dx = -0.2f))

            assertThat(result.key?.label).isEqualTo("z")
            assertThat(result.decision).isEqualTo(TapDecision.SCORE)
        }

        @Test
        fun `le facteur sigma change la vraisemblance`() {
            val base = decode(150f, 50f, TapSpatialModel.NONE).score(0, 1)

            val wider = decode(150f, 50f, Fixed('z', sx = 2f)).score(0, 1)
            val taller = decode(150f, 50f, Fixed('z', sy = 4f)).score(0, 1)

            // Au centre, la densité gaussienne est inversement proportionnelle à σ.
            assertThat(wider).isCloseTo(base / 2, Offset.offset(base * 1e-9))
            assertThat(taller).isCloseTo(base / 4, Offset.offset(base * 1e-9))
        }

        @Test
        fun `le noyau et la nature de la touche ne dépendent pas du modèle`() {
            val result = decode(150f, 50f, Fixed('z', dx = -0.3f))

            assertThat(result.decision).isEqualTo(TapDecision.CORE)
            assertThat(result.key?.label).isEqualTo("z")
        }

        @Test
        fun `le clavier adaptatif transmet le modèle au décodeur`() {
            val keyboard = AdaptiveKeyboard(azerty, AdaptationConfig(), Fixed('z', dx = -0.2f))

            assertThat(keyboard.decode(95f, 50f, rest, rest)?.key?.label).isEqualTo("z")
            assertThat(AdaptiveKeyboard(azerty).decode(95f, 50f, rest, rest)?.key?.label).isEqualTo("a")
        }

        @Test
        fun `un modèle appris par frappes validées déplace la décision`() {
            val model = SpatialModel()
            val z = rest.rows[0][1]
            repeat(200) {
                model.stage(z.centerX - 0.2f * z.width, z.centerY, z, PredictionSettings())
            }
            model.confirm()

            assertThat(model.offsetX('z')).isLessThan(-0.15f)
            assertThat(decode(95f, 50f, model).key?.label).isEqualTo("z")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `le déplacement de la frontière est borné par maxBoundaryShift`() {
            val model = FixedKeys(KeyKind.SPACE, dx = -0.5f) // brut : -0,25 × 160 = -40 px
            val limit = 0.2f * minOf(beforeSpace.width, space.width)

            assertThat(decodeIn(space.left - limit + 1f, row2Y, model).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(space.left - limit - 1f, row2Y, model).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `le déplacement vertical est borné par maxBoundaryShift`() {
            val model = FixedKeys(KeyKind.SPACE, dy = -0.5f) // brut : -25 px

            assertThat(decodeIn(space.centerX, space.top - 19f, model).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(space.centerX, space.top - 21f, model).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `maxBoundaryShift à zéro désactive les zones personnalisées`() {
            val off = TapDecoderConfig(maxBoundaryShift = 0f)
            val model = FixedKeys(KeyKind.SPACE, dx = -0.5f, dy = -0.5f)

            assertThat(decodeIn(space.left - 5f, row2Y, model, config = off).key).isEqualTo(beforeSpace.key)
            assertThat(decodeIn(space.centerX, space.top - 5f, model, config = off).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `les frontières entre deux lettres ne bougent pas avec les décalages des touches de fonction`() {
            val model = FixedKeys(
                mapOf(
                    KeyKind.SPACE to (-0.3f to -0.3f), KeyKind.ENTER to (0.3f to 0.3f),
                    KeyKind.BACKSPACE to (-0.3f to 0.3f), KeyKind.SYMBOLS to (0.3f to -0.3f),
                ),
            )
            val plain = TapDecoding(azerty)
            val personal = TapDecoding(azerty)

            for (r in 0..1) {
                for (rect in rest.rows[r]) {
                    for (dx in listOf(-3f, -0.5f, 0.5f, 3f)) {
                        val x = rect.right + dx
                        TapDecoder(azerty).decode(x, rect.centerY, rest, rest, null, plain)
                        TapDecoder(azerty, TapDecoderConfig(), model).decode(x, rect.centerY, rest, rest, null, personal)
                        // Rangée 1 : seules les frontières avec ↵ (QWERTY) ou le bord comptent ; en AZERTY, lettres seules.
                        assertThat(personal.key).isEqualTo(plain.key)
                        assertThat(personal.decision).isEqualTo(plain.decision)
                    }
                }
            }
        }

        @Test
        fun `un emplacement vide n'est jamais envahi ni ne cède de place`() {
            val row = KeyRow(List(8) { Key.letter('a' + it) } + Key(KeyKind.EMPTY, "", 1f) + Key(KeyKind.SPACE, "", 1f))
            val layout = KeyboardLayout(LayoutKind.AZERTY, listOf(row))
            val g = KeyboardGeometry.compute(layout, 1000f, 100f, 0f)
            val empty = g.rows[0][8]
            val spaceRect = g.rows[0][9]
            val model = FixedKeys(KeyKind.SPACE, dx = -0.5f)

            assertThat(decodeIn(empty.right - 1f, 50f, model, g, g).key?.kind).isEqualTo(KeyKind.EMPTY)
            assertThat(decodeIn(spaceRect.left + 1f, 50f, model, g, g).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(empty.left + 1f, 50f, model, g, g).key?.kind).isEqualTo(KeyKind.EMPTY)
        }

        @Test
        fun `les lettres inconnues du modèle gardent le comportement par défaut`() {
            val model = TapSpatialModel.NONE

            assertThat(model.offsetX('é')).isZero()
            assertThat(model.offsetY('é')).isZero()
            assertThat(model.sigmaScaleX('é')).isEqualTo(1f)
            assertThat(model.sigmaScaleY('é')).isEqualTo(1f)
        }

        @Test
        fun `un tap hors du clavier n'est pas décodé, avec ou sans modèle`() {
            val out = TapDecoding(azerty)

            val decoded = TapDecoder(azerty, TapDecoderConfig(), Fixed('z', dx = 0.2f)).decode(-5f, 50f, rest, rest, null, out)

            assertThat(decoded).isFalse()
            assertThat(out.key).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un point exactement sur la frontière déplacée vers la droite reste sur l'espace`() {
            val model = FixedKeys(KeyKind.SPACE, dx = 0.25f) // +0,25 × 160 / 2 = +20 px, borne à 0,2 × 90 = 18 px
            val boundary = space.left + 18f

            assertThat(decodeIn(boundary, row2Y, model).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(boundary - 0.01f, row2Y, model).key).isEqualTo(beforeSpace.key)
        }

        @Test
        fun `un point exactement sur la frontière verticale déplacée vers le haut est attribué à l'espace`() {
            val model = FixedKeys(KeyKind.SPACE, dy = -0.4f) // -20 px : frontière à y = 180
            val boundary = space.top - 20f

            assertThat(decodeIn(space.centerX, boundary, model).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(space.centerX, boundary - 0.01f, model).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `un point exactement sur la frontière verticale déplacée vers le bas reste sur l'espace`() {
            val model = FixedKeys(KeyKind.SPACE, dy = 0.4f) // +20 px : frontière à y = 220
            val boundary = space.top + 20f

            assertThat(decodeIn(space.centerX, boundary, model).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(space.centerX, boundary - 0.01f, model).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `la rangée voisine est lue au même x, y compris exactement sur une frontière de touches`() {
            val model = FixedKeys(KeyKind.SPACE, dy = -0.4f)

            // x = space.left : la touche de la rangée du bas à cet x est l'espace (pas la lettre à sa gauche).
            assertThat(decodeIn(space.left, space.top - 10f, model).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(space.left - 0.01f, space.top - 10f, model).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `en QWERTY, entrée appris vers le haut prend le bas de la dernière lettre de la rangée du haut`() {
            val qwerty = KeyboardLayouts.QWERTY
            val g = KeyboardGeometry.compute(qwerty, 1000f, 100f, 0f)
            val enter = g.rows[1].last()
            val model = FixedKeys(KeyKind.ENTER, dy = -0.4f) // -20 px : frontière à y = 80

            assertThat(decodeIn(enter.centerX, 90f, model, g, g).key).isEqualTo(enter.key)
            assertThat(decodeIn(enter.centerX, 90f, TapSpatialModel.NONE, g, g).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `une touche de fonction en bas à droite, appris vers le bas, ne déborde pas du clavier`() {
            val last = rest.rows[2].last()

            val result = decodeIn(last.centerX, 299f, FixedKeys(KeyKind.BACKSPACE, dy = 0.5f))

            assertThat(result.key).isEqualTo(last.key)
        }

        @Test
        fun `un modèle vide donne les mêmes décisions et scores près des touches de fonction et de toutes les frontières`() {
            val empty = SpatialModel()
            for (layout in listOf(KeyboardLayouts.AZERTY, KeyboardLayouts.QWERTY)) {
                for (gap in listOf(0f, 12f, 20f)) {
                    val geometry = KeyboardGeometry.compute(layout, 1000f, 100f, gap)
                    val first = TapDecoding(layout)
                    val second = TapDecoding(layout)
                    val plain = TapDecoder(layout)
                    val personal = TapDecoder(layout, TapDecoderConfig(), empty)
                    for (row in geometry.rows) {
                        for (rect in row) {
                            for (dx in listOf(-2f, -0.001f, 0f, 0.001f, 2f)) {
                                for (y in listOf(rect.top - 1f, rect.top, rect.top + 1f, rect.centerY, rect.bottom - 1f, rect.bottom, rect.bottom + gap)) {
                                    val x = rect.right + dx
                                    val okA = plain.decode(x, y, geometry, geometry, null, first)
                                    val okB = personal.decode(x, y, geometry, geometry, null, second)

                                    assertThat(okB).isEqualTo(okA)
                                    assertThat(second.key).isEqualTo(first.key)
                                    assertThat(second.decision).isEqualTo(first.decision)
                                }
                            }
                        }
                    }
                }
            }
        }

        @Test
        fun `un point exactement sur la frontière déplacée est attribué à la touche de droite`() {
            val model = FixedKeys(KeyKind.SPACE, dx = -0.2f)
            val boundary = space.left - 0.1f * space.width

            assertThat(decodeIn(boundary, row2Y, model).key?.kind).isEqualTo(KeyKind.SPACE)
            assertThat(decodeIn(boundary - 0.01f, row2Y, model).key?.kind).isEqualTo(KeyKind.LETTER)
        }

        @Test
        fun `un point en haut ou en bas du clavier n'a pas de voisin à personnaliser`() {
            val model = FixedKeys(KeyKind.BACKSPACE, dx = 0.5f, dy = 0.5f)
            val last = rest.rows[2].last()

            assertThat(decodeIn(1f, 1f, model).key).isEqualTo(rest.rows[0][0].key)
            assertThat(decodeIn(last.right, 299f, model).key).isEqualTo(last.key)
            assertThat(decodeIn(999.9f, 299.9f, model).key).isEqualTo(last.key)
        }

        @Test
        fun `un décalage nul et un facteur de un sont neutres`() {
            val neutral = decode(95f, 50f, Fixed('z', 0f, 0f, 1f, 1f))
            val none = decode(95f, 50f, TapSpatialModel.NONE)

            assertThat(neutral.key).isEqualTo(none.key)
            assertThat(neutral.score(0, 1)).isEqualTo(none.score(0, 1))
        }

        @Test
        fun `un décalage maximal reste décodable sur toute la rangée`() {
            for (x in listOf(1f, 50f, 99f, 500f, 999f)) {
                assertThat(decode(x, 50f, Fixed('z', dx = 0.25f, dy = -0.25f, sx = 1.6f, sy = 0.7f)).key).isNotNull()
            }
        }
    }
}
