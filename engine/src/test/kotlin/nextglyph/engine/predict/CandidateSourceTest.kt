package nextglyph.engine.predict

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.adapt.TapSpatialModel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CandidateSourceTest {

    private val azerty = KeyAdjacency(KeyboardLayouts.AZERTY)

    private val qwerty = KeyboardLayouts.QWERTY

    /** Source dont les substitutions sont pondérées par la position des frappes (disposition QWERTY). */
    private fun spatialSource(vararg entries: Pair<String, Long>, config: PredictionConfig = PredictionConfig()) =
        CandidateSource(
            LexiconSet(LexiconFixtures.lexicon(entries.toList())),
            KeyAdjacency(qwerty),
            config,
            SpatialSubstitution(qwerty, config),
        )

    /** Frappes d'un mot de [length] caractères dont la première lettre [letter] est tapée en (dx, dy). */
    private fun taps(letter: Char, dx: Double, dy: Double, length: Int, model: TapSpatialModel = TapSpatialModel.NONE) =
        WordTaps(listOf<TypedTap?>(TypedTap(letter, dx, dy)) + List(length - 1) { null }, model)

    private fun source(vararg entries: Pair<String, Long>, config: PredictionConfig = PredictionConfig()) =
        CandidateSource(LexiconSet(LexiconFixtures.lexicon(entries.toList())), azerty, config)

    @Nested
    inner class HappyPath {
        @Test
        fun `complétions d'un préfixe connu - scores normalisés par la masse du lexique`() {
            val s = source("bonjour" to 60L, "bonsoir" to 30L, "merci" to 10L)

            val c = s.lookup("bons")

            assertThat(c.isWord).isFalse()
            assertThat(c.knownPrefix).isTrue()
            assertThat(c.top.first().key).isEqualTo("bonsoir")
            assertThat(c.top.first().score).isCloseTo(0.3, within(1e-3))
            // Corrections : « bonj » (s → j non voisines, 0,0005) et « bon » (lettre en trop, 0,002).
            assertThat(c.total).isCloseTo((30.0 + 0.0005 * 60 + 0.002 * 90) / 100, within(1e-3))
        }

        @Test
        fun `éditions - poids de chaque type`() {
            val s = source("le" to 10L, "la" to 10L, "peut-etre" to 10L, "abc" to 1L, "bac" to 1L)

            assertThat(s.edits("ke")).containsEntry("le", 0.01)
            assertThat(s.edits("ze")).containsEntry("le", 0.0005)
            assertThat(s.edits("lle")).containsEntry("le", 0.002)
            assertThat(s.edits("l")).doesNotContainKey("")
            assertThat(s.edits("peutetre")).containsEntry("peut-etre", 0.5)
            assertThat(s.edits("bca")).containsEntry("bac", 0.002)
            assertThat(s.edits("ac")).containsEntry("abc", 0.002)
        }

        @Test
        fun `correction même si le texte tapé est un préfixe connu qui n'est pas un mot`() {
            val s = source("le" to 1000L, "kestrel" to 1L)

            val c = s.lookup("ke")

            assertThat(c.knownPrefix).isTrue()
            assertThat(c.top.map { it.key }).startsWith("le", "kestrel")
        }

        @Test
        fun `élision reconstituée - mot complet seul, sinon complétions`() {
            val s = source("l'" to 100L, "école" to 50L, "écoles" to 30L, "ami" to 20L)

            val word = s.lookup("lami")
            val prefix = s.lookup("lec")

            // Initiales voyelles : 100 ; poids de l'élision 0,5 × 100 / 100 = 0,5.
            assertThat(word.top.first().key).isEqualTo("l'ami")
            assertThat(word.top.first().score).isCloseTo(0.5 * 20 / 200, within(1e-4))
            assertThat(word.top.first().restStart).isEqualTo(2)
            assertThat(word.top.map { it.key }).doesNotContain("l'amis")
            assertThat(prefix.top.map { it.key }).startsWith("l'ecole", "l'ecoles")
            assertThat(prefix.top[0].restStart).isEqualTo(2)
        }


        @Test
        fun `sans frappes - mêmes poids qu'avant`() {
            val s = spatialSource("rose" to 10L)

            assertThat(s.edits("eose")).containsEntry("rose", 0.01)
            assertThat(s.edits("eose", null)).isEqualTo(s.edits("eose"))
            assertThat(s.edits("eose", WordTaps(listOf(null, null, null, null)))).containsEntry("rose", 0.01)
        }

        @Test
        fun `frappe au centre - poids proche de l'ancien 0,01`() {
            val s = spatialSource("rose" to 10L)

            // « e » → « r » : voisine horizontale à 1 u, frappe centrée.
            assertThat(s.edits("eose", taps('e', 0.0, 0.0, 4)).getValue("rose")).isCloseTo(0.0101, within(2e-4))
        }

        @Test
        fun `frappe à la frontière - la voisine l'emporte sur une autre voisine`() {
            val s = spatialSource("rose" to 10L, "dose" to 10L)

            val weights = s.edits("eose", taps('e', 0.5, 0.0, 4))

            assertThat(weights.getValue("rose")).isCloseTo(0.6, within(1e-9))
            assertThat(weights.getValue("dose")).isLessThan(0.05)
            assertThat(s.lookup("eose", taps('e', 0.5, 0.0, 4)).top.first().key).isEqualTo("rose")
        }

        @Test
        fun `frappe au-delà de la frontière - poids plafonné à 1`() {
            val s = spatialSource("rose" to 10L)

            assertThat(s.edits("eose", taps('e', 0.7, 0.0, 4)).getValue("rose")).isEqualTo(1.0)
        }

        @Test
        fun `modèle appris - le centre décalé d'une touche change le classement`() {
            val s = spatialSource("rose" to 10L, "dose" to 10L)
            val learned = object : TapSpatialModel by TapSpatialModel.NONE {
                // Les frappes de « d » tombent 0,325 u plus à droite et 1 u plus haut : sur la frontière e/r.
                override fun offsetX(letter: Char): Float = if (letter == 'd') 0.325f / 0.95f else 0f
                override fun offsetY(letter: Char): Float = if (letter == 'd') -1f else 0f
            }

            val plain = s.lookup("eose", taps('e', 0.5, 0.0, 4))
            val personal = s.lookup("eose", taps('e', 0.5, 0.0, 4, learned))

            assertThat(plain.top.first().key).isEqualTo("rose")
            assertThat(personal.top.first().key).isEqualTo("dose")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rejette un texte vide`() {
            assertThatThrownBy { source("le" to 1L).lookup("") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("vide")
        }

        @Test
        fun `texte sans aucun candidat`() {
            val c = source("le" to 1L).lookup("zzzz")

            assertThat(c.knownPrefix).isFalse()
            assertThat(c.top).isEmpty()
            assertThat(c.total).isZero()
        }

        @Test
        fun `pas d'élision sans l'élision dans le lexique ni devant une consonne`() {
            val noElision = source("ami" to 10L)
            val consonant = source("l'" to 10L, "bateau" to 10L)

            assertThat(noElision.lookup("lami").top.map { it.key }).doesNotContain("l'ami")
            assertThat(consonant.lookup("lbateau").top.map { it.key }).doesNotContain("l'bateau")
        }


        @Test
        fun `frappes qui ne s'alignent pas sur le texte tapé - poids binaire`() {
            val s = spatialSource("rose" to 10L)
            val other = WordTaps(listOf(TypedTap('x', 0.5, 0.0), null, null, null))

            assertThat(s.edits("eose", other).getValue("rose")).isEqualTo(0.01)
        }

        @Test
        fun `sans substitution spatiale - les frappes sont ignorées`() {
            val s = source("rose" to 10L)

            assertThat(s.edits("eose", taps('e', 0.5, 0.0, 4)).getValue("rose")).isEqualTo(0.01)
        }

        @Test
        fun `caractère voulu hors disposition - poids binaire d'adjacence`() {
            val s = spatialSource("e-e" to 10L)
            val taps = WordTaps(listOf(null, TypedTap('s', 0.0, 0.0), null))

            // « s » → « - » : le trait d'union n'a pas de touche lettre, aucun score spatial.
            assertThat(s.edits("ese", taps).getValue("e-e")).isEqualTo(0.0005)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un mot n'a pas de correction`() {
            val c = source("le" to 10L, "la" to 10L).lookup("le")

            assertThat(c.isWord).isTrue()
            assertThat(c.top.map { it.key }).containsExactly("le")
            assertThat(c.total).isCloseTo(0.5, within(1e-3))
        }

        @Test
        fun `nombre de candidats borné par formsPerPrefix`() {
            val many = (1..10).map { "ma$it".replace(Regex("\\d")) { m -> ('a' + m.value.toInt()).toString() } to it.toLong() }
            val s = source(*many.toTypedArray(), config = PredictionConfig(formsPerPrefix = 2))

            assertThat(s.lookup("ma").top).hasSize(2)
        }

        @Test
        fun `élision seule tapée, ou élision sans rien après`() {
            val s = source("l'" to 10L, "ami" to 10L)

            assertThat(s.lookup("l").top.map { it.key }).doesNotContain("l'ami")
            assertThat(s.lookup("la").top.map { it.key }).contains("l'ami")
        }

        @Test
        fun `arrêt anticipé - même résultat que l'exploration complète`() {
            val entries = (0 until 26).map { ("b" + ('a' + it) + "c") to (it + 1L) * 10 } + ("ba" to 5L)
            val k1 = source(*entries.toTypedArray(), config = PredictionConfig(formsPerPrefix = 1))
            val k6 = source(*entries.toTypedArray())

            // « bzx » : la lettre en trop mène à « bz » (bzc, 260) ; les substitutions de x à d'autres.
            assertThat(k1.lookup("bzx").top.map { it.key }).containsExactly(k6.lookup("bzx").top.first().key)
            assertThat(k1.lookup("bzx").total).isEqualTo(k6.lookup("bzx").total)
        }

        @Test
        fun `poids d'élision plafonné à 1`() {
            val s = source("l'" to 1000L, "a" to 1L, config = PredictionConfig(correction = CorrectionWeights(separator = 1.0)))

            assertThat(s.lookup("la").top.first { it.key == "l'a" }.score).isCloseTo(1.0 / 1001, within(1e-9))
        }


        @Test
        fun `frappe loin de la voisine - plancher de la substitution`() {
            val s = spatialSource("rose" to 10L)

            // Frappe très à gauche de « e » : « r » est loin, le poids ne descend pas sous 0,0005.
            assertThat(s.edits("eose", taps('e', -0.45, 0.0, 4)).getValue("rose")).isEqualTo(0.0005)
        }

        @Test
        fun `frappe pile sur la frontière - rapport 1`() {
            val s = spatialSource("rose" to 10L)

            assertThat(s.edits("eose", taps('e', 0.5, 0.0, 4)).getValue("rose")).isCloseTo(0.6, within(1e-9))
            assertThat(s.edits("eose", taps('e', 0.4, 0.0, 4)).getValue("rose")).isLessThan(0.6)
            assertThat(s.edits("eose", taps('e', 0.6, 0.0, 4)).getValue("rose")).isGreaterThan(0.6)
        }

        @Test
        fun `frappe extrême - pas de dépassement numérique`() {
            val s = spatialSource("rose" to 10L)

            assertThat(s.edits("eose", taps('e', 5.0, 3.0, 4)).getValue("rose")).isBetween(0.0005, 1.0)
            assertThat(s.edits("eose", taps('e', -5.0, -3.0, 4)).getValue("rose")).isBetween(0.0005, 1.0)
        }
    }
}
