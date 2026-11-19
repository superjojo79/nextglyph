package nextglyph.engine.adapt

import nextglyph.engine.layout.KeyboardGeometry
import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AdaptiveKeyboardTest {

    private val azerty = KeyboardLayouts.AZERTY
    private val config = AdaptationConfig(mode = AdaptationMode.ELASTIC)

    /** Après `a`, seul `e` est possible ; après `b`, `z` ; le mot `a` est aussi complet. */
    private val lexicon: Lexicon = LexiconFixtures.lexicon(listOf("ae" to 100L, "a" to 10L, "bz" to 50L, "ba" to 5L))

    private val rest = KeyboardGeometry.compute(azerty, 1000f, 100f, 0f)

    private fun keyboard(withLexicon: Boolean = true) = AdaptiveKeyboard(azerty, config).also {
        if (withLexicon) it.setLexicon(lexicon, 0L)
    }

    /** Applique la disposition en attente (hors saisie rapide) et termine l'animation. */
    private fun AdaptiveKeyboard.settle(at: Long) {
        advance(at, 1f)
        advance(at + 1000L, 1f)
    }

    private fun AdaptiveKeyboard.keyAt(x: Float, y: Float = 50f, displayed: KeyboardGeometry = rest) =
        decode(x, y, displayed, rest)?.key?.label

    /** Rangée 1 de l'AZERTY : a z e r t y u i o p ; `e` est la touche 2. */
    private fun AdaptiveKeyboard.opacityOfE() = frame.opacity(0, 2)

    @Nested
    inner class HappyPath {

        @Test
        fun `un clavier neuf est au repos`() {
            val keyboard = keyboard(withLexicon = false)

            assertThat(keyboard.frame.isRest).isTrue()
            assertThat(keyboard.lexicon).isNull()
            assertThat(keyboard.restOnly).isFalse()
            assertThat(keyboard.layout).isSameAs(azerty)
            assertThat(keyboard.pointersDown).isZero()
        }

        @Test
        fun `un préfixe adapte la disposition affichée après animation`() {
            val keyboard = keyboard()

            keyboard.onPrefix("a", 1000L)
            assertThat(keyboard.hasPendingUpdate).isTrue()
            assertThat(keyboard.frame.isRest).isTrue()

            keyboard.advance(1000L, 1f)
            assertThat(keyboard.isAnimating).isTrue()
            assertThat(keyboard.advance(1100L, 1f)).isFalse()

            assertThat(keyboard.isAnimating).isFalse()
            assertThat(keyboard.opacityOfE()).isEqualTo(1f)
            assertThat(keyboard.frame.opacity(0, 1)).isLessThan(1f)
        }

        @Test
        fun `le début de mot adapte aussi, de moitié`() {
            val keyboard = keyboard()

            keyboard.onPrefix("", 1000L)
            keyboard.settle(1000L)

            assertThat(keyboard.frame.isRest).isFalse()
        }

        @Test
        fun `le lexique pèse sur le décodage en périphérie d'une touche`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)

            // 195 : bord droit de z, hors du noyau ; e est la seule continuation de « a ».
            assertThat(keyboard.keyAt(195f)).isEqualTo("e")
            assertThat(keyboard.decode(195f, 50f, rest, rest)?.decision).isEqualTo(TapDecision.SCORE)
        }

        @Test
        fun `le centre d'une touche l'emporte sur le lexique`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)

            assertThat(keyboard.keyAt(150f)).isEqualTo("z")
        }

        @Test
        fun `un même préfixe n'est calculé qu'une fois`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            keyboard.onPrefix("a", 5000L)

            assertThat(keyboard.hasPendingUpdate).isFalse()
        }

        @Test
        fun `un nouveau préfixe est calculé`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            keyboard.onPrefix("b", 5000L)

            assertThat(keyboard.hasPendingUpdate).isTrue()
        }

        @Test
        fun `la disposition est gelée tant qu'un doigt est posé`() {
            val keyboard = keyboard()
            keyboard.pointerDown(900L)
            keyboard.onPrefix("a", 1000L)

            keyboard.settle(1000L)
            assertThat(keyboard.frame.isRest).isTrue()
            assertThat(keyboard.pointersDown).isEqualTo(1)

            keyboard.pointerUp(1100L)
            keyboard.settle(1100L)
            assertThat(keyboard.frame.isRest).isFalse()
            assertThat(keyboard.pointersDown).isZero()
        }

        @Test
        fun `ANIMATOR_DURATION_SCALE nul supprime l'animation`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)

            assertThat(keyboard.advance(1000L, 0f)).isFalse()

            assertThat(keyboard.isAnimating).isFalse()
            assertThat(keyboard.frame.isRest).isFalse()
        }

        @Test
        fun `la saisie rapide diffère la mise à jour`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.onPrefix("b", 1050L)

            assertThat(keyboard.nextUpdateTimeMs).isEqualTo(1050L + config.fastTypingMs)
        }

        @Test
        fun `le lexique retiré ramène le clavier au repos`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            keyboard.setLexicon(null, 5000L)
            keyboard.settle(5000L)

            assertThat(keyboard.lexicon).isNull()
            assertThat(keyboard.frame.isRest).isTrue()
        }

        @Test
        fun `un lexique chargé après le préfixe adapte le clavier`() {
            val keyboard = keyboard(withLexicon = false)
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)
            assertThat(keyboard.frame.isRest).isTrue()

            keyboard.setLexicon(lexicon, 5000L)
            keyboard.settle(5000L)

            assertThat(keyboard.lexicon).isSameAs(lexicon)
            assertThat(keyboard.frame.isRest).isFalse()
        }

        @Test
        fun `le mode repos seul ramène aussitôt la disposition au repos`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            keyboard.setRestOnly(true, 5000L)

            assertThat(keyboard.restOnly).isTrue()
            assertThat(keyboard.frame.isRest).isTrue()
            assertThat(keyboard.hasPendingUpdate).isFalse()
        }

        @Test
        fun `le mode repos seul n'adapte plus et décode sans le lexique`() {
            val keyboard = keyboard()
            keyboard.setRestOnly(true, 0L)

            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            assertThat(keyboard.frame.isRest).isTrue()
            assertThat(keyboard.keyAt(195f)).isEqualTo("z")
        }

        @Test
        fun `la sortie du mode repos seul adapte pour le préfixe courant`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.setRestOnly(true, 2000L)

            keyboard.setRestOnly(false, 3000L)
            keyboard.settle(3000L)

            assertThat(keyboard.frame.isRest).isFalse()
            assertThat(keyboard.keyAt(195f)).isEqualTo("e")
        }

        @Test
        fun `le décodage s'appuie sur la disposition affichée`() {
            val keyboard = keyboard(withLexicon = false)
            val widened = KeyboardGeometry.compute(
                azerty, 1000f, 100f, 0f,
                listOf(listOf(1.5f, 0.5f) + List(8) { 1f }) + azerty.restWidths().drop(1),
            )

            // a élargie occupe 0–150, z 150–200 : x = 100 est au repos à la frontière de a et z,
            // mais dans le noyau de a affichée ; x = 160 est dans z affichée, hors de son noyau.
            assertThat(keyboard.keyAt(100f, displayed = widened)).isEqualTo("a")
            assertThat(keyboard.keyAt(160f, displayed = widened)).isEqualTo("z")
        }

        @Test
        fun `la largeur du clavier en dp active le plancher de 32 dp`() {
            val keyboard = AdaptiveKeyboard(azerty).also { it.setLexicon(lexicon, 0L) }
            keyboard.keyboardWidthDp = 360f
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            assertThat(keyboard.keyboardWidthDp).isEqualTo(360f)
            assertThat(keyboard.frame.isRest).isFalse()
            for (k in 0 until 10) assertThat(keyboard.frame.width(0, k) * 36f).isGreaterThanOrEqualTo(32f - 0.01f)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un tap hors du clavier n'a pas de touche`() {
            val keyboard = keyboard()

            assertThat(keyboard.decode(-1f, 50f, rest, rest)).isNull()
            assertThat(keyboard.decode(50f, 5000f, rest, rest)).isNull()
        }

        @Test
        fun `un préfixe inconnu sans correction laisse le clavier au repos`() {
            val keyboard = keyboard()

            keyboard.onPrefix("qqqq", 1000L)
            keyboard.settle(1000L)

            assertThat(keyboard.frame.isRest).isTrue()
        }

        @Test
        fun `un préfixe absent laisse le clavier au repos`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            keyboard.onPrefix(null, 5000L)
            keyboard.settle(5000L)

            assertThat(keyboard.frame.isRest).isTrue()
            assertThat(keyboard.keyAt(195f)).isEqualTo("z")
        }

        @Test
        fun `sans lexique, le décodage est purement spatial`() {
            val keyboard = keyboard(withLexicon = false)
            keyboard.onPrefix("a", 1000L)

            assertThat(keyboard.keyAt(195f)).isEqualTo("z")
            assertThat(keyboard.decode(195f, 50f, rest, rest)?.decision).isEqualTo(TapDecision.SCORE)
        }

        @Test
        fun `une échelle de durée négative est refusée`() {
            val keyboard = keyboard()

            assertThatThrownBy { keyboard.advance(0L, -1f) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `un relâchement sans appui est ignoré`() {
            val keyboard = keyboard()

            keyboard.pointerUp(10L)
            keyboard.cancelPointers(20L)

            assertThat(keyboard.pointersDown).isZero()
        }

        @Test
        fun `une largeur de clavier négative est refusée`() {
            val keyboard = keyboard()

            assertThatThrownBy { keyboard.keyboardWidthDp = -1f }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(keyboard.keyboardWidthDp).isZero()
        }

        @Test
        fun `des géométries d'une autre disposition sont refusées`() {
            val keyboard = keyboard()
            val qwerty = KeyboardGeometry.compute(KeyboardLayouts.QWERTY, 1000f, 100f, 0f)

            assertThatThrownBy { keyboard.decode(10f, 10f, qwerty, qwerty) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `réinitialiser remet tout au repos immédiatement`() {
            val keyboard = keyboard()
            keyboard.pointerDown(900L)
            keyboard.onPrefix("a", 1000L)
            keyboard.pointerUp(950L)
            keyboard.settle(1000L)

            keyboard.reset()

            assertThat(keyboard.frame.isRest).isTrue()
            assertThat(keyboard.hasPendingUpdate).isFalse()
            assertThat(keyboard.isAnimating).isFalse()
            assertThat(keyboard.pointersDown).isZero()
            assertThat(keyboard.keyAt(195f)).isEqualTo("z")
        }

        @Test
        fun `après une réinitialisation, un préfixe absent ne change rien`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)
            keyboard.reset()

            keyboard.onPrefix(null, 5000L)

            assertThat(keyboard.hasPendingUpdate).isFalse()
        }

        @Test
        fun `après une réinitialisation, un préfixe vide adapte`() {
            val keyboard = keyboard()
            keyboard.reset()

            keyboard.onPrefix("", 5000L)

            assertThat(keyboard.hasPendingUpdate).isTrue()
        }

        @Test
        fun `un premier préfixe absent ne fait rien de plus qu'un clavier neuf`() {
            val keyboard = keyboard()

            keyboard.onPrefix(null, 1000L)

            assertThat(keyboard.hasPendingUpdate).isTrue()
            keyboard.settle(1000L)
            assertThat(keyboard.frame.isRest).isTrue()
        }

        @Test
        fun `activer deux fois le mode repos seul ne fait rien de plus`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)
            keyboard.setRestOnly(true, 2000L)

            keyboard.setRestOnly(true, 3000L)

            assertThat(keyboard.frame.isRest).isTrue()
            keyboard.setRestOnly(false, 4000L)
            assertThat(keyboard.hasPendingUpdate).isTrue()
        }

        @Test
        fun `désactiver le mode repos seul alors qu'il ne l'est pas ne recalcule pas`() {
            val keyboard = keyboard()
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            keyboard.setRestOnly(false, 5000L)

            assertThat(keyboard.hasPendingUpdate).isFalse()
        }

        @Test
        fun `redonner la même largeur de clavier ne recalcule pas, une autre si`() {
            val keyboard = keyboard()
            keyboard.keyboardWidthDp = 360f
            keyboard.onPrefix("a", 1000L)
            keyboard.settle(1000L)

            keyboard.keyboardWidthDp = 360f
            keyboard.onPrefix("a", 5000L)
            assertThat(keyboard.hasPendingUpdate).isFalse()

            keyboard.keyboardWidthDp = 411f
            keyboard.onPrefix("a", 6000L)
            assertThat(keyboard.hasPendingUpdate).isTrue()
        }

        @Test
        fun `l'annulation des pointeurs dégèle la disposition`() {
            val keyboard = keyboard()
            keyboard.pointerDown(900L)
            keyboard.pointerDown(910L)
            keyboard.onPrefix("a", 1000L)

            keyboard.cancelPointers(1100L)
            keyboard.settle(1100L)

            assertThat(keyboard.pointersDown).isZero()
            assertThat(keyboard.frame.isRest).isFalse()
        }

        @Test
        fun `le décodage est le même quand le préfixe est nul ou le lexique absent`() {
            val withoutPrefix = keyboard()
            val withoutLexicon = keyboard(withLexicon = false)
            withoutLexicon.onPrefix("a", 1000L)

            for (x in listOf(105f, 195f, 205f, 295f)) assertThat(withoutPrefix.keyAt(x)).isEqualTo(withoutLexicon.keyAt(x))
        }
    }
}
