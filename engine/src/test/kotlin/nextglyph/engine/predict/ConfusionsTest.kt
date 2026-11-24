package nextglyph.engine.predict

import nextglyph.engine.ngram.NgramFixtures
import nextglyph.engine.ngram.NgramModel
import nextglyph.engine.ngram.NgramModelBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Formes confondues et mots techniques (issue #55). */
class ConfusionsTest {

    private fun model(vararg counts: Triple<String, String, Int>): NgramModel {
        val builder = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL, confusionSets = ConfusionConfig.FRENCH_SETS.map { it.forms })
        builder.addSentence(listOf("il", "va", "elle"))
        for ((previous, form, count) in counts) builder.addConfusionContext(listOf(previous, form), count)
        return NgramModel.load(ByteBuffer.wrap(builder.build()))
    }

    private fun confusions(vararg counts: Triple<String, String, Int>) = Confusions(ConfusionConfig()).apply {
        models = mapOf("fr" to model(*counts))
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `les autres formes sont classées par part décroissante`() {
            val c = Confusions(ConfusionConfig(sets = mapOf("fr" to listOf(ConfusionSet(listOf("ou", "où", "oui")))))).apply {
                models = mapOf(
                    "fr" to NgramModel.load(
                        ByteBuffer.wrap(
                            NgramModelBuilder("fr", NgramFixtures.KEEP_ALL, confusionSets = listOf(listOf("ou", "où", "oui"))).apply {
                                addSentence(listOf("il", "ou"))
                                addConfusionContext(listOf("il", "oui"), 6)
                                addConfusionContext(listOf("il", "où"), 3)
                                addConfusionContext(listOf("il", "ou"), 1)
                            }.build(),
                        ),
                    ),
                )
            }

            val alternatives = c.alternatives("fr", "ou", "il")

            assertThat(alternatives.map { it.text }).containsExactly("oui", "où")
            assertThat(alternatives.map { it.share }).containsExactly(0.6, 0.3)
            assertThat(alternatives.map { it.total }).containsExactly(10, 10)
        }

        @Test
        fun `la forme proposée est celle de l'ensemble`() {
            val alternatives = confusions(Triple("va", "à", 10)).alternatives("fr", "A", "va")

            assertThat(alternatives).isEmpty()
            val lower = confusions(Triple("va", "à", 10)).alternatives("fr", "a", "va")
            assertThat(lower.single().text).isEqualTo("à")
            assertThat(lower.single().auto).isTrue()
        }

        @Test
        fun `une forme qui n'a jamais suivi le mot n'est pas proposée`() {
            val alternatives = confusions(Triple("va", "a", 10)).alternatives("fr", "a", "va")

            assertThat(alternatives).isEmpty()
        }

        @Test
        fun `un sigle ou un mot collé à un signe technique est technique`() {
            assertThat(Confusions.isTechnical("voir SNCF", "SNCF")).isTrue()
            assertThat(Confusions.isTechnical("voir jean@poste", "poste")).isTrue()
            assertThat(Confusions.isTechnical("voir /etc", "etc")).isTrue()
            assertThat(Confusions.isTechnical("voir mon_nom", "nom")).isTrue()
            assertThat(Confusions.isTechnical("voir a.poste", "poste")).isTrue()
            assertThat(Confusions.isTechnical("voir 1.poste", "poste")).isTrue()
            assertThat(Confusions.isTechnical("voir poste", "poste")).isFalse()
            assertThat(Confusions.isTechnical("fin. poste", "poste")).isFalse()
            assertThat(Confusions.isTechnical("l'poste", "poste")).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `sans modèle pour la langue rien n'est proposé`() {
            assertThat(Confusions(ConfusionConfig()).alternatives("fr", "a", "va")).isEmpty()
            assertThat(confusions(Triple("va", "à", 10)).alternatives("en", "a", "va")).isEmpty()
        }

        @Test
        fun `un accent tapé ou un mot hors des ensembles ne donne rien`() {
            val c = confusions(Triple("va", "à", 10))

            assertThat(c.alternatives("fr", "à", "va")).isEmpty()
            assertThat(c.alternatives("fr", "chat", "va")).isEmpty()
        }

        @Test
        fun `un mot précédent inconnu ou sans occurrence ne donne rien`() {
            val c = confusions(Triple("va", "à", 10))

            assertThat(c.alternatives("fr", "a", "zzz")).isEmpty()
            assertThat(c.alternatives("fr", "a", "elle")).isEmpty()
        }

        @Test
        fun `un acronyme est reconnu à partir de deux lettres majuscules seulement`() {
            assertThat(Confusions.isAcronym("SNCF")).isTrue()
            assertThat(Confusions.isAcronym("OK")).isTrue()
            assertThat(Confusions.isAcronym("A")).isFalse()
            assertThat(Confusions.isAcronym("Ok")).isFalse()
            assertThat(Confusions.isAcronym("")).isFalse()
            assertThat(Confusions.isAcronym("12")).isFalse()
        }

        @Test
        fun `un texte indisponible ou qui ne finit pas par le mot n'est pas technique`() {
            assertThat(Confusions.isTechnical(null, "poste")).isFalse()
            assertThat(Confusions.isTechnical("voir autre", "poste")).isFalse()
            assertThat(Confusions.isTechnical("poste", "poste")).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un seul compte donne une part de 1`() {
            val alternatives = confusions(Triple("va", "à", 1)).alternatives("fr", "a", "va")

            assertThat(alternatives.single().share).isEqualTo(1.0)
            assertThat(alternatives.single().total).isEqualTo(1)
        }

        @Test
        fun `un point juste avant le mot, au début du texte, ne le rend pas technique`() {
            assertThat(Confusions.isTechnical(".poste", "poste")).isFalse()
            assertThat(Confusions.isTechnical("..poste", "poste")).isFalse()
            assertThat(Confusions.isTechnical("a.poste", "poste")).isTrue()
            assertThat(Confusions.isTechnical("/poste", "poste")).isTrue()
        }
    }
}
