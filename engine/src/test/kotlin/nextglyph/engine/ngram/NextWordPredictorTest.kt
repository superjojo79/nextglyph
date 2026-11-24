package nextglyph.engine.ngram

import nextglyph.engine.predict.LanguageState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NextWordPredictorTest {

    private val french = NgramFixtures.model()

    private val english = NgramFixtures.model(
        listOf("I am here" to 6, "the cat sleeps" to 4),
        tag = "en",
    )

    private fun predictor(
        config: NextWordConfig = NextWordConfig(),
        models: Map<String, NgramModel> = linkedMapOf("fr" to french, "en" to english),
    ) = NextWordPredictor(models, config)

    private fun state(vararg languages: String, preferred: String = languages.first(), share: Double = 0.5) =
        LanguageState.initial(languages.toList(), preferred, preferredShare = share)

    private fun texts(
        predictor: NextWordPredictor,
        text: String?,
        language: LanguageState,
        capitalize: Boolean = false,
    ) = predictor.suggest(text, language, capitalize).map { it.text }

    @Nested
    inner class HappyPath {
        @Test
        fun `après un mot, le meilleur est au centre, puis le suivant, puis le groupe de deux mots`() {
            val suggestions = texts(predictor(), "Je ", state("fr"))

            assertThat(suggestions).containsExactly("suis", "veux", "suis content")
        }

        @Test
        fun `sans groupe assez probable, la troisième case est le troisième mot`() {
            val suggestions = texts(predictor(NextWordConfig(groupMinProbability = 0.45)), "", state("fr"))

            assertThat(suggestions).containsExactly("je", "tu", "j'")
        }

        @Test
        fun `le groupe est retenu quand il atteint le seuil`() {
            val suggestions = texts(predictor(NextWordConfig(groupMinProbability = 0.3)), "", state("fr"))

            assertThat(suggestions).containsExactly("je", "tu", "je suis")
        }

        @Test
        fun `un mot qui finit par une apostrophe est collé au suivant dans le groupe`() {
            val model = NgramFixtures.model(listOf("j' ai faim" to 10, "tu es là" to 1))

            val suggestions = texts(predictor(models = mapOf("fr" to model)), "", state("fr"))

            assertThat(suggestions).containsExactly("j'", "tu", "j'ai")
        }

        @Test
        fun `la majuscule de début de phrase est appliquée à chaque proposition`() {
            val suggestions = texts(predictor(), "Je ", state("fr"), capitalize = true)

            assertThat(suggestions).containsExactly("Suis", "Veux", "Suis content")
        }

        @Test
        fun `le I anglais reste en majuscule et les accents sont conservés`() {
            val french = NgramFixtures.model(listOf("él à" to 3, "été là" to 2))

            val fr = texts(predictor(models = mapOf("fr" to french)), "", state("fr"), capitalize = true)
            val en = texts(predictor(), "", state("en"), capitalize = false)

            assertThat(fr).startsWith("Él", "Été")
            assertThat(en).startsWith("I", "the")
        }

        @Test
        fun `les langues sont mélangées selon leur poids`() {
            val suggestions = texts(predictor(NextWordConfig(groupMinProbability = 0.9)), "", state("fr", "en"))

            // fr : je .5, tu .3, j' .15 ; en : I .6, the .4 ; poids 0,5 chacun.
            assertThat(suggestions).containsExactly("I", "je", "the")
        }

        @Test
        fun `la langue verrouillée domine`() {
            val locked = state("fr", "en").lock("en")

            assertThat(texts(predictor(), "", locked).first()).isEqualTo("I")
            assertThat(texts(predictor(), "", state("fr", "en").lock("fr")).first()).isEqualTo("je")
        }

        @Test
        fun `un poids de langue plus fort fait passer ses mots devant`() {
            val heavyFrench = state("fr", "en", share = 0.9)

            assertThat(texts(predictor(NextWordConfig(groupMinProbability = 0.9)), "", heavyFrench)).containsExactly("je", "tu", "j'")
        }

        @Test
        fun `un mot proposé par deux langues cumule les scores`() {
            val a = NgramFixtures.model(listOf("oui bien" to 1, "non rien" to 1), tag = "fr")
            val b = NgramFixtures.model(listOf("oui bien" to 1, "non rien" to 1), tag = "en")

            val suggestions = texts(predictor(models = linkedMapOf("fr" to a, "en" to b)), "Oui ", state("fr", "en"))

            assertThat(suggestions).containsExactly("bien")
        }

        @Test
        fun `après une élision, la langue française continue sans espace`() {
            val suggestions = texts(predictor(), "J'", state("fr"))

            assertThat(suggestions).startsWith("ai")
        }

        @Test
        fun `la disponibilité reflète les modèles`() {
            assertThat(predictor().isAvailable).isTrue()
            assertThat(predictor().languages).containsExactlyInAnyOrder("fr", "en")
            assertThat(predictor(models = emptyMap()).isAvailable).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `sans modèle, aucune proposition`() {
            assertThat(texts(predictor(models = emptyMap()), "Je ", state("fr"))).isEmpty()
        }

        @Test
        fun `un texte indisponible ou un mot en cours ne donne rien`() {
            assertThat(texts(predictor(), null, state("fr"))).isEmpty()
            assertThat(texts(predictor(), "Je su", state("fr"))).isEmpty()
        }

        @Test
        fun `un mot précédent inconnu ne donne rien`() {
            assertThat(texts(predictor(), "Zorglub ", state("fr"))).isEmpty()
        }

        @Test
        fun `une langue sans modèle ou sans poids n'apporte rien`() {
            assertThat(texts(predictor(models = mapOf("fr" to french)), "", state("fr", "en").lock("en"))).isEmpty()
            assertThat(texts(predictor(), "", state("fr"))).doesNotContain("I")
        }

        @Test
        fun `un chiffre avant le curseur ne donne rien`() {
            assertThat(texts(predictor(), "Il a 3 ", state("fr"))).isEmpty()
        }

        @Test
        fun `la configuration refuse les valeurs hors limites`() {
            assertThatThrownBy { NextWordConfig(groupMinProbability = -0.01) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("groupe")
            assertThatThrownBy { NextWordConfig(groupMinProbability = 1.01) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { NextWordConfig(candidates = 2) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("candidats")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `la configuration accepte ses bornes`() {
            assertThat(NextWordConfig(groupMinProbability = 0.0, candidates = 3).candidates).isEqualTo(3)
            assertThat(NextWordConfig(groupMinProbability = 1.0).groupMinProbability).isEqualTo(1.0)
        }

        @Test
        fun `un seul mot possible ne forme pas de groupe`() {
            val model = NgramFixtures.model(listOf("a b c" to 5))

            assertThat(texts(predictor(models = mapOf("fr" to model)), "", state("fr"))).containsExactly("a")
        }

        @Test
        fun `deux mots possibles et un groupe donnent trois cases`() {
            val model = NgramFixtures.model(listOf("a b c" to 5, "d e f" to 4))

            assertThat(texts(predictor(models = mapOf("fr" to model)), "", state("fr"))).containsExactly("a", "d", "a b")
        }

        @Test
        fun `un seuil de groupe nul accepte tout groupe`() {
            val config = NextWordConfig(groupMinProbability = 0.0)

            assertThat(texts(predictor(config), "", state("fr"))).hasSize(3)
        }

        @Test
        fun `un seuil de groupe juste en dessous de la probabilité du groupe l'accepte, juste au-dessus le refuse`() {
            val model = NgramFixtures.model(listOf("a b c" to 1, "d e f" to 1))
            val models = mapOf("fr" to model)

            val below = texts(predictor(NextWordConfig(groupMinProbability = 0.49), models), "", state("fr"))
            val above = texts(predictor(NextWordConfig(groupMinProbability = 0.52), models), "", state("fr"))

            assertThat(below).containsExactly("a", "d", "a b")
            assertThat(above).containsExactly("a", "d")
        }

        @Test
        fun `à score égal, l'ordre alphabétique départage`() {
            val model = NgramFixtures.model(listOf("b x" to 1, "a x" to 1))

            assertThat(texts(predictor(models = mapOf("fr" to model)), "", state("fr")).take(2)).containsExactly("a", "b")
        }

        @Test
        fun `la capitalisation d'un mot vide ou à une lettre`() {
            val model = NgramFixtures.model(listOf("a b" to 1))

            assertThat(texts(predictor(models = mapOf("fr" to model)), "", state("fr"), capitalize = true)).containsExactly("A")
        }
    }
}
