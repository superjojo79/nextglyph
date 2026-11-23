package nextglyph.engine.learn

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.PredictionSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SpatialModelTest {

    private val settings = PredictionSettings()
    private val model = SpatialModel()
    private val rest = KeyboardGeometry.compute(KeyboardLayouts.AZERTY, 1000f, 100f, 0f)

    private fun SpatialModel.learn(letter: Char, dx: Double, dy: Double, times: Int) {
        // Par lots : la file d'attente est bornée (SpatialConfig.maxStaged).
        var left = times
        while (left > 0) {
            val batch = minOf(left, 50)
            repeat(batch) { assertThat(stage(letter, dx, dy, settings)).isTrue() }
            confirm()
            left -= batch
        }
    }

    private fun SpatialModel.learnKey(kind: KeyKind, dx: Double, dy: Double, times: Int) {
        repeat(times) {
            assertThat(stageKey(kind, dx, dy, settings)).isTrue()
            assertThat(confirmKey()).isTrue()
        }
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `les touches apprises sont espace, entrée, retour arrière et symboles, dans cet ordre`() {
            assertThat(SpatialModel.LEARNED_KEYS).containsExactly(KeyKind.SPACE, KeyKind.ENTER, KeyKind.BACKSPACE, KeyKind.SYMBOLS)
        }

        @Test
        fun `une frappe de touche en attente n'est pas apprise avant confirmation`() {
            assertThat(model.stageKey(KeyKind.SPACE, 0.2, -0.1, settings)).isTrue()

            assertThat(model.pendingKey).isEqualTo(KeyKind.SPACE)
            assertThat(model.sampleCount(KeyKind.SPACE)).isZero()
            assertThat(model.isEmpty).isTrue()
            assertThat(model.totalSamples).isZero()
        }

        @Test
        fun `la confirmation intègre la frappe de touche et change la révision`() {
            model.stageKey(KeyKind.ENTER, 0.2, -0.1, settings)
            val before = model.revision

            assertThat(model.confirmKey()).isTrue()

            assertThat(model.pendingKey).isNull()
            assertThat(model.sampleCount(KeyKind.ENTER)).isEqualTo(1)
            assertThat(model.revision).isEqualTo(before + 1)
            assertThat(model.isEmpty).isFalse()
        }

        @Test
        fun `l'écart d'une touche de fonction est ramené vers zéro selon la confiance`() {
            model.learnKey(KeyKind.SPACE, -0.2, 0.1, 20)

            // n / (n + priorWeight) = 20 / 40 = 0,5.
            assertThat(model.keyOffsetX(KeyKind.SPACE)).isCloseTo(-0.1f, Offset.offset(1e-6f))
            assertThat(model.keyOffsetY(KeyKind.SPACE)).isCloseTo(0.05f, Offset.offset(1e-6f))
            assertThat(model.keyOffsetX(KeyKind.ENTER)).isZero()
        }

        @Test
        fun `chaque touche apprise a son propre compteur et le total les additionne avec les lettres`() {
            model.learn('e', 0.1, 0.0, 3)
            model.learnKey(KeyKind.SPACE, 0.1, 0.0, 2)
            model.learnKey(KeyKind.ENTER, 0.1, 0.0, 4)
            model.learnKey(KeyKind.BACKSPACE, 0.1, 0.0, 5)
            model.learnKey(KeyKind.SYMBOLS, 0.1, 0.0, 6)

            assertThat(model.sampleCount(KeyKind.SPACE)).isEqualTo(2)
            assertThat(model.sampleCount(KeyKind.ENTER)).isEqualTo(4)
            assertThat(model.sampleCount(KeyKind.BACKSPACE)).isEqualTo(5)
            assertThat(model.sampleCount(KeyKind.SYMBOLS)).isEqualTo(6)
            assertThat(model.sampleCount('e')).isEqualTo(3)
            assertThat(model.totalSamples).isEqualTo(20)
        }

        @Test
        fun `la confirmation des lettres ne touche pas la frappe de touche en attente`() {
            model.stage('e', 0.1, 0.0, settings)
            model.stageKey(KeyKind.SPACE, 0.1, 0.0, settings)

            model.confirm()
            assertThat(model.pendingKey).isEqualTo(KeyKind.SPACE)
            model.discardStaged()
            model.discardLast()

            assertThat(model.pendingKey).isEqualTo(KeyKind.SPACE)
            assertThat(model.sampleCount('e')).isEqualTo(1)
        }

        @Test
        fun `un enregistrement de 30 entrées est restauré avec les touches`() {
            model.learn('e', 0.1, 0.0, 5)
            model.learnKey(KeyKind.SYMBOLS, 0.2, -0.2, 7)
            val copy = SpatialModel()

            copy.restore(model.snapshot())

            assertThat(model.snapshot()).hasSize(30)
            assertThat(copy.sampleCount(KeyKind.SYMBOLS)).isEqualTo(7)
            assertThat(copy.keyOffsetX(KeyKind.SYMBOLS)).isEqualTo(model.keyOffsetX(KeyKind.SYMBOLS))
            assertThat(copy.keyOffsetY(KeyKind.SYMBOLS)).isEqualTo(model.keyOffsetY(KeyKind.SYMBOLS))
            assertThat(copy.totalSamples).isEqualTo(12)
        }

        @Test
        fun `un enregistrement de 26 lettres laisse les touches vides`() {
            model.learnKey(KeyKind.SPACE, 0.2, 0.0, 10)
            val letters = SpatialModel().apply { learn('e', 0.1, 0.0, 5) }.snapshot().take(26)

            model.restore(letters)

            assertThat(model.sampleCount('e')).isEqualTo(5)
            assertThat(model.sampleCount(KeyKind.SPACE)).isZero()
            assertThat(model.keyOffsetX(KeyKind.SPACE)).isZero()
        }

        @Test
        fun `l'effacement oublie aussi les touches et la frappe de touche en attente`() {
            model.learnKey(KeyKind.SPACE, 0.2, 0.0, 10)
            model.stageKey(KeyKind.ENTER, 0.1, 0.0, settings)

            model.reset()

            assertThat(model.isEmpty).isTrue()
            assertThat(model.totalSamples).isZero()
            assertThat(model.pendingKey).isNull()
            assertThat(model.keyOffsetX(KeyKind.SPACE)).isZero()
        }

        @Test
        fun `après l'effacement, un nouvel apprentissage repart de zéro`() {
            model.learn('e', 0.2, 0.2, 20)
            model.reset()

            model.learn('e', -0.1, -0.1, 20)

            assertThat(model.offsetX('e')).isCloseTo(-0.05f, Offset.offset(1e-6f))
            assertThat(model.offsetY('e')).isCloseTo(-0.05f, Offset.offset(1e-6f))
            assertThat(model.sampleCount('e')).isEqualTo(20)
            // Variance nulle des frappes identiques : facteur au plancher, à moitié de confiance.
            assertThat(model.sigmaScaleX('e')).isCloseTo(0.85f, Offset.offset(1e-6f))
            assertThat(model.sigmaScaleY('e')).isCloseTo(0.85f, Offset.offset(1e-6f))
        }

        @Test
        fun `un modèle vide expose les valeurs par défaut pour toutes les lettres`() {
            for (letter in 'a'..'z') {
                assertThat(model.offsetX(letter)).isZero()
                assertThat(model.offsetY(letter)).isZero()
                assertThat(model.sigmaScaleX(letter)).isEqualTo(1f)
                assertThat(model.sigmaScaleY(letter)).isEqualTo(1f)
            }
            assertThat(model.isEmpty).isTrue()
        }

        @Test
        fun `le décalage appris est ramené vers zéro tant que l'échantillon est petit`() {
            model.learn('e', 0.1, -0.08, 20)

            // n = 20, poids des valeurs par défaut 20 : l'estimation compte pour moitié.
            assertThat(model.offsetX('e')).isCloseTo(0.05f, Offset.offset(1e-6f))
            assertThat(model.offsetY('e')).isCloseTo(-0.04f, Offset.offset(1e-6f))
            assertThat(model.sampleCount('e')).isEqualTo(20)
            assertThat(model.offsetX('z')).isZero()
            assertThat(model.isEmpty).isFalse()
        }

        @Test
        fun `avec beaucoup de frappes, l'estimation domine`() {
            model.learn('e', 0.1, 0.0, 200)

            assertThat(model.offsetX('e')).isCloseTo((0.1 * 200 / 220).toFloat(), Offset.offset(1e-6f))
        }

        @Test
        fun `le facteur sigma suit la dispersion mesurée`() {
            repeat(10) {
                model.stage('e', 0.2, 0.2, settings)
                model.stage('e', -0.2, -0.2, settings)
            }
            model.confirm()

            // Écart type 0,2 pour une dispersion attendue de 0,23 ; confiance 1/2.
            val expected = (1 + (0.2 / 0.23 - 1) * 0.5).toFloat()
            assertThat(model.sigmaScaleX('e')).isCloseTo(expected, Offset.offset(1e-5f))
            assertThat(model.sigmaScaleY('e')).isCloseTo(expected, Offset.offset(1e-5f))
        }

        @Test
        fun `une dispersion forte élargit sigma, une faible le resserre`() {
            val wide = SpatialModel(SpatialConfig(priorWeight = 0.0, maxSampleOffset = 0.5))
            val narrow = SpatialModel(SpatialConfig(priorWeight = 0.0))
            repeat(10) {
                wide.stage('a', 0.45, 0.0, settings)
                wide.stage('a', -0.45, 0.0, settings)
                narrow.stage('a', 0.01, 0.0, settings)
                narrow.stage('a', -0.01, 0.0, settings)
            }
            wide.confirm()
            narrow.confirm()

            assertThat(wide.sigmaScaleX('a')).isEqualTo(1.6f)
            assertThat(narrow.sigmaScaleX('a')).isEqualTo(0.7f)
            assertThat(wide.sigmaScaleY('a')).isEqualTo(0.7f)
        }

        @Test
        fun `les frappes en attente ne comptent qu'une fois validées`() {
            model.stage('e', 0.1, 0.0, settings)
            model.stage('e', 0.1, 0.0, settings)

            assertThat(model.stagedCount).isEqualTo(2)
            assertThat(model.sampleCount('e')).isZero()
            assertThat(model.revision).isZero()

            assertThat(model.confirm()).isEqualTo(2)

            assertThat(model.stagedCount).isZero()
            assertThat(model.sampleCount('e')).isEqualTo(2)
            assertThat(model.revision).isEqualTo(1L)
        }

        @Test
        fun `une frappe annulée n'est pas apprise`() {
            model.stage('e', 0.4, 0.0, settings)
            model.stage('r', 0.4, 0.0, settings)

            assertThat(model.discardLast()).isTrue()
            model.confirm()

            assertThat(model.sampleCount('e')).isEqualTo(1)
            assertThat(model.sampleCount('r')).isZero()
        }

        @Test
        fun `un mot annulé écarte toutes ses frappes`() {
            model.stage('e', 0.4, 0.0, settings)
            model.stage('r', 0.4, 0.0, settings)

            model.discardStaged()

            assertThat(model.confirm()).isZero()
            assertThat(model.isEmpty).isTrue()
        }

        @Test
        fun `confirmer sans frappe en attente ne change pas la révision`() {
            assertThat(model.confirm()).isZero()
            assertThat(model.revision).isZero()
        }

        @Test
        fun `les lettres sont indépendantes`() {
            model.learn('a', 0.2, 0.0, 40)
            model.learn('z', -0.2, 0.0, 40)

            assertThat(model.offsetX('a')).isGreaterThan(0.1f)
            assertThat(model.offsetX('z')).isLessThan(-0.1f)
            assertThat(model.offsetX('e')).isZero()
        }

        @Test
        fun `une frappe peut être mise en attente depuis sa position en pixels`() {
            val e = rest.rows[0][2] // lettre e en AZERTY.

            val staged = model.stage(e.centerX + 10f, e.centerY - 5f, e, settings)
            model.confirm()

            assertThat(e.key.label).isEqualTo("e")
            assertThat(staged).isTrue()
            assertThat(model.offsetX('e')).isCloseTo((10f / e.width) / 21f, Offset.offset(1e-6f))
            assertThat(model.offsetY('e')).isCloseTo((-5f / e.height) / 21f, Offset.offset(1e-6f))
        }

        @Test
        fun `effacer remet le modèle à zéro`() {
            model.learn('e', 0.1, 0.1, 30)
            model.stage('r', 0.1, 0.1, settings)
            val before = model.revision

            model.reset()

            assertThat(model.isEmpty).isTrue()
            assertThat(model.stagedCount).isZero()
            assertThat(model.offsetX('e')).isZero()
            assertThat(model.sigmaScaleX('e')).isEqualTo(1f)
            assertThat(model.revision).isGreaterThan(before)
        }

        @Test
        fun `l'estimation oublie les frappes anciennes au-delà de la fenêtre`() {
            val small = SpatialModel(SpatialConfig(window = 10, priorWeight = 0.0))
            repeat(10) { small.stage('e', 0.2, 0.0, settings) }
            small.confirm()
            repeat(10) { small.stage('e', -0.2, 0.0, settings) }
            small.confirm()

            // Poids 1/10 par frappe : 0,2 · 0,9^10 − 0,2 · (1 − 0,9^10).
            val expected = 0.2 * Math.pow(0.9, 10.0) - 0.2 * (1 - Math.pow(0.9, 10.0))
            assertThat(small.offsetX('e').toDouble()).isCloseTo(expected, Offset.offset(1e-6))
            assertThat(small.sampleCount('e')).isEqualTo(10)
        }

        @Test
        fun `l'état s'enregistre et se restaure`() {
            model.learn('e', 0.1, -0.1, 25)
            val copy = SpatialModel()

            copy.restore(model.snapshot())

            for (letter in 'a'..'z') {
                assertThat(copy.offsetX(letter)).isEqualTo(model.offsetX(letter))
                assertThat(copy.sigmaScaleY(letter)).isEqualTo(model.sigmaScaleY(letter))
                assertThat(copy.sampleCount(letter)).isEqualTo(model.sampleCount(letter))
            }
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `une frappe de touche est refusée là où l'apprentissage est interdit`() {
            for (profile in listOf(FieldProfile.PASSWORD, FieldProfile.NUMERIC)) {
                val refused = model.stageKey(KeyKind.SPACE, 0.1, 0.0, PredictionSettings(profile = profile))

                assertThat(refused).describedAs(profile.name).isFalse()
            }
            assertThat(model.stageKey(KeyKind.SPACE, 0.1, 0.0, PredictionSettings(incognito = true))).isFalse()
            assertThat(model.pendingKey).isNull()
        }

        @Test
        fun `une touche qui n'est pas apprise est refusée`() {
            for (kind in KeyKind.entries.filter { it !in SpatialModel.LEARNED_KEYS }) {
                assertThat(model.stageKey(kind, 0.0, 0.0, settings)).describedAs(kind.name).isFalse()
                assertThat(model.sampleCount(kind)).isZero()
                assertThat(model.keyOffsetX(kind)).isZero()
                assertThat(model.keyOffsetY(kind)).isZero()
            }
            assertThat(model.pendingKey).isNull()
        }

        @Test
        fun `une frappe de touche non finie ou hors du rectangle est refusée`() {
            assertThat(model.stageKey(KeyKind.SPACE, Double.NaN, 0.0, settings)).isFalse()
            assertThat(model.stageKey(KeyKind.SPACE, 0.0, Double.POSITIVE_INFINITY, settings)).isFalse()
            assertThat(model.stageKey(KeyKind.SPACE, 0.51, 0.0, settings)).isFalse()
            assertThat(model.stageKey(KeyKind.SPACE, 0.0, -0.51, settings)).isFalse()
            assertThat(model.pendingKey).isNull()
        }

        @Test
        fun `un refus ne remplace pas la frappe de touche en attente`() {
            model.stageKey(KeyKind.SPACE, 0.1, 0.0, settings)

            model.stageKey(KeyKind.ENTER, 0.9, 0.0, settings)

            assertThat(model.pendingKey).isEqualTo(KeyKind.SPACE)
        }

        @Test
        fun `confirmer ou écarter sans frappe de touche en attente renvoie faux`() {
            assertThat(model.confirmKey()).isFalse()
            assertThat(model.discardKey()).isFalse()
            assertThat(model.revision).isZero()
        }

        @Test
        fun `écarter la frappe de touche en attente ne l'apprend pas`() {
            model.stageKey(KeyKind.SPACE, 0.1, 0.0, settings)
            val before = model.revision

            assertThat(model.discardKey()).isTrue()

            assertThat(model.pendingKey).isNull()
            assertThat(model.confirmKey()).isFalse()
            assertThat(model.totalSamples).isZero()
            assertThat(model.revision).isEqualTo(before)
        }

        @Test
        fun `une restauration écarte la frappe de touche en attente`() {
            model.stageKey(KeyKind.SPACE, 0.1, 0.0, settings)

            model.restore(SpatialModel().snapshot())

            assertThat(model.pendingKey).isNull()
        }

        @Test
        fun `un enregistrement de taille invalide est refusé`() {
            val ok = model.snapshot()

            for (size in listOf(0, 25, 27, 29, 31)) {
                val bad = List(size) { ok[0] }
                assertThatThrownBy { model.restore(bad) }.describedAs("taille $size").isInstanceOf(IllegalArgumentException::class.java)
            }
        }

        @Test
        fun `un enregistrement invalide sur une touche de fonction est refusé sans rien modifier`() {
            model.learnKey(KeyKind.SPACE, 0.2, 0.0, 10)
            val bad = model.snapshot().mapIndexed { i, s -> if (i == 29) LetterStats(1, 0.6, 0.0, 0.0, 0.0) else s }

            assertThatThrownBy { model.restore(bad) }.isInstanceOf(IllegalArgumentException::class.java)

            assertThat(model.sampleCount(KeyKind.SPACE)).isEqualTo(10)
        }

        @Test
        fun `chaque valeur non finie est ignorée, quel que soit l'axe`() {
            assertThat(model.stage('e', 0.0, Double.NaN, settings)).isFalse()
            assertThat(model.stage('e', Double.POSITIVE_INFINITY, 0.0, settings)).isFalse()
            assertThat(model.stage('e', Double.NEGATIVE_INFINITY, 0.0, settings)).isFalse()
            assertThat(model.stage('e', 0.0, Double.NEGATIVE_INFINITY, settings)).isFalse()
        }

        @Test
        fun `les caractères voisins de a et z ne sont pas des lettres`() {
            assertThat(model.stage('`', 0.0, 0.0, settings)).isFalse()
            assertThat(model.stage('{', 0.0, 0.0, settings)).isFalse()
            assertThat(model.stage('@', 0.0, 0.0, settings)).isFalse()
            assertThat(model.sampleCount('{')).isZero()
            assertThat(model.offsetX('`')).isZero()
        }

        @Test
        fun `la touche espace, sans libellé de lettre, est ignorée`() {
            val space = rest.rows[2].first { it.key.kind == nextglyph.engine.layout.KeyKind.SPACE }

            assertThat(model.stage(space.centerX, space.centerY, space, settings)).isFalse()
        }

        @ParameterizedTest
        @ValueSource(strings = ["TEXT_INCOGNITO", "PASSWORD", "NUMERIC"])
        fun `rien n'est appris là où c'est interdit`(kind: String) {
            val forbidden = when (kind) {
                "TEXT_INCOGNITO" -> PredictionSettings(incognito = true)
                else -> PredictionSettings(profile = FieldProfile.valueOf(kind))
            }

            assertThat(model.stage('e', 0.1, 0.1, forbidden)).isFalse()
            assertThat(model.stage(100f, 50f, rest.rows[0][0], forbidden)).isFalse()

            assertThat(model.stagedCount).isZero()
        }

        @Test
        fun `une valeur non finie ou une lettre hors a-z est ignorée`() {
            assertThat(model.stage('e', Double.NaN, 0.0, settings)).isFalse()
            assertThat(model.stage('e', 0.0, Double.POSITIVE_INFINITY, settings)).isFalse()
            assertThat(model.stage('é', 0.0, 0.0, settings)).isFalse()
            assertThat(model.stage('E', 0.0, 0.0, settings)).isFalse()
            assertThat(model.stage('1', 0.0, 0.0, settings)).isFalse()
            assertThat(model.stagedCount).isZero()
            assertThat(model.offsetX('É')).isZero()
            assertThat(model.sigmaScaleX('é')).isEqualTo(1f)
            assertThat(model.sampleCount('é')).isZero()
        }

        @Test
        fun `une frappe hors du rectangle de la touche est ignorée`() {
            assertThat(model.stage('e', 0.51, 0.0, settings)).isFalse()
            assertThat(model.stage('e', -0.51, 0.0, settings)).isFalse()
            assertThat(model.stage('e', 0.0, 0.51, settings)).isFalse()
            assertThat(model.stage('e', 0.0, -0.51, settings)).isFalse()
        }

        @Test
        fun `une touche qui n'est pas une lettre est ignorée`() {
            val backspace = rest.rows[2].last()

            assertThat(model.stage(backspace.centerX, backspace.centerY, backspace, settings)).isFalse()
        }

        @Test
        fun `annuler sans frappe en attente renvoie faux`() {
            assertThat(model.discardLast()).isFalse()
        }

        @Test
        fun `une restauration invalide lève une exception sans rien modifier`() {
            model.learn('e', 0.1, 0.0, 10)
            val ok = model.snapshot()
            val cases = mapOf(
                "taille" to ok.drop(1),
                "frappes négatives" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(-1, 0.0, 0.0, 0.0, 0.0) else s },
                "frappes au-delà de la fenêtre" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(301, 0.0, 0.0, 0.0, 0.0) else s },
                "décalage x" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, 0.51, 0.0, 0.0, 0.0) else s },
                "décalage y" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, 0.0, -0.51, 0.0, 0.0) else s },
                "décalage NaN" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, Double.NaN, 0.0, 0.0, 0.0) else s },
                "variance x" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, 0.0, 0.0, 0.26, 0.0) else s },
                "variance y négative" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, 0.0, 0.0, 0.0, -0.1) else s },
                "variance NaN" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, 0.0, 0.0, Double.NaN, 0.0) else s },
                "variance y NaN" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, 0.0, 0.0, 0.0, Double.NaN) else s },
                "décalage y NaN" to ok.mapIndexed { i, s -> if (i == 0) LetterStats(1, 0.0, Double.NaN, 0.0, 0.0) else s },
            )

            for ((name, bad) in cases) {
                assertThatThrownBy { model.restore(bad) }.describedAs(name).isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThat(model.sampleCount('e')).isEqualTo(10)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `une frappe de touche à exactement maxSampleOffset est retenue`() {
            assertThat(model.stageKey(KeyKind.BACKSPACE, 0.5, -0.5, settings)).isTrue()
        }

        @Test
        fun `l'écart d'une touche de fonction est borné par maxOffset`() {
            val big = SpatialModel(SpatialConfig(priorWeight = 0.0))
            big.learnKey(KeyKind.SPACE, 0.5, -0.5, 10)

            assertThat(big.keyOffsetX(KeyKind.SPACE)).isEqualTo(0.25f)
            assertThat(big.keyOffsetY(KeyKind.SPACE)).isEqualTo(-0.25f)
        }

        @Test
        fun `le compteur d'une touche ne dépasse pas la fenêtre`() {
            val small = SpatialModel(SpatialConfig(window = 5))

            small.learnKey(KeyKind.ENTER, 0.1, 0.0, 9)

            assertThat(small.sampleCount(KeyKind.ENTER)).isEqualTo(5)
            assertThat(small.totalSamples).isEqualTo(5)
        }

        @Test
        fun `un modèle vide a un total nul et des touches centrées`() {
            assertThat(model.totalSamples).isZero()
            for (kind in SpatialModel.LEARNED_KEYS) {
                assertThat(model.keyOffsetX(kind)).isZero()
                assertThat(model.keyOffsetY(kind)).isZero()
                assertThat(model.sampleCount(kind)).isZero()
            }
        }

        @Test
        fun `sans poids des valeurs par défaut, un modèle vide reste neutre`() {
            val sharp = SpatialModel(SpatialConfig(priorWeight = 0.0))

            assertThat(sharp.offsetX('a')).isZero()
            assertThat(sharp.sigmaScaleX('a')).isEqualTo(1f)
            assertThat(sharp.sigmaScaleY('a')).isEqualTo(1f)
        }

        @Test
        fun `le décalage exposé ne dépasse jamais maxOffset`() {
            val sharp = SpatialModel(SpatialConfig(priorWeight = 0.0))
            repeat(50) { sharp.stage('e', 0.45, -0.45, settings) }
            sharp.confirm()

            assertThat(sharp.offsetX('e')).isEqualTo(0.25f)
            assertThat(sharp.offsetY('e')).isEqualTo(-0.25f)
        }

        @Test
        fun `une frappe à exactement maxSampleOffset est retenue`() {
            assertThat(model.stage('e', 0.5, -0.5, settings)).isTrue()
        }

        @Test
        fun `avec un poids nul des valeurs par défaut, une seule frappe suffit`() {
            val sharp = SpatialModel(SpatialConfig(priorWeight = 0.0))
            sharp.stage('e', 0.1, 0.0, settings)
            sharp.confirm()

            assertThat(sharp.offsetX('e')).isCloseTo(0.1f, Offset.offset(1e-6f))
        }

        @Test
        fun `une seule frappe n'a presque aucun effet par défaut`() {
            model.learn('e', 0.2, 0.0, 1)

            assertThat(model.offsetX('e')).isCloseTo((0.2 / 21).toFloat(), Offset.offset(1e-6f))
            // Variance nulle d'une frappe : facteur au plancher, mais à 1/21 de confiance.
            assertThat(model.sigmaScaleX('e')).isCloseTo((1 + (0.7 - 1) / 21).toFloat(), Offset.offset(1e-6f))
        }

        @Test
        fun `la file d'attente est bornée, la plus ancienne est abandonnée`() {
            val small = SpatialModel(SpatialConfig(maxStaged = 3))
            small.stage('a', 0.1, 0.0, settings)
            for (letter in "bcd") small.stage(letter, 0.1, 0.0, settings)

            assertThat(small.stagedCount).isEqualTo(3)
            small.confirm()
            assertThat(small.sampleCount('a')).isZero()
            assertThat(small.sampleCount('d')).isEqualTo(1)
        }

        @Test
        fun `le nombre de frappes est plafonné à la fenêtre`() {
            val small = SpatialModel(SpatialConfig(window = 5))
            small.learn('e', 0.1, 0.0, 12)

            assertThat(small.sampleCount('e')).isEqualTo(5)
        }

        @Test
        fun `la restauration accepte les valeurs limites`() {
            val limit = List(26) { LetterStats(300, 0.5, -0.5, 0.25, 0.0) }

            model.restore(limit)

            assertThat(model.sampleCount('a')).isEqualTo(300)
            assertThat(model.offsetX('a')).isEqualTo((0.25 * 300 / 320).toFloat())
        }

        @Test
        fun `la restauration vide les frappes en attente`() {
            model.stage('e', 0.1, 0.0, settings)

            model.restore(SpatialModel().snapshot())

            assertThat(model.stagedCount).isZero()
        }
    }
}
