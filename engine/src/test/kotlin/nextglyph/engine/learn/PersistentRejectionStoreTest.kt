package nextglyph.engine.learn

import nextglyph.engine.predict.PredictFixtures
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.SpaceCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PersistentRejectionStoreTest {

    private val store = PersistentRejectionStore()

    @Nested
    inner class HappyPath {

        @Test
        fun `compte les refus d'une paire sans tenir compte de la casse`() {
            store.recordRejection("Ecole", "École")
            store.recordRejection("ecole", "école")

            assertThat(store.rejections("ECOLE", "ÉCOLE")).isEqualTo(2)
            assertThat(store.rejections("ecole", "autre")).isZero()
            assertThat(store.size).isEqualTo(1)
        }

        @Test
        fun `deux refus de la même correction la désactivent dans le prédicteur`() {
            val predictor = PredictFixtures.frenchPredictor(store)
            val state = predictor.initialLanguageState("fr")
            val settings = PredictionSettings()
            assertThat(predictor.predict("Il dit bonj", state, settings).case).isEqualTo(SpaceCase.DOMINANT)

            store.recordRejection("bonj", "bonjour")
            assertThat(predictor.predict("Il dit bonj", state, settings).case).isEqualTo(SpaceCase.DOMINANT)
            store.recordRejection("bonj", "bonjour")

            assertThat(predictor.predict("Il dit bonj", state, settings).case).isEqualTo(SpaceCase.LITERAL)
        }

        @Test
        fun `la révision change à chaque modification`() {
            val start = store.revision

            store.recordRejection("a", "b")
            val recorded = store.revision
            store.clear()

            assertThat(recorded).isGreaterThan(start)
            assertThat(store.revision).isGreaterThan(recorded)
        }

        @Test
        fun `effacer oublie tous les refus`() {
            store.recordRejection("a", "b")

            store.clear()

            assertThat(store.size).isZero()
            assertThat(store.rejections("a", "b")).isZero()
        }

        @Test
        fun `le contenu s'enregistre du moins récent au plus récent et se restaure`() {
            store.recordRejection("un", "1")
            store.recordRejection("deux", "2")
            store.recordRejection("un", "1")
            val copy = PersistentRejectionStore()

            copy.restore(store.snapshot())

            assertThat(store.snapshot()).containsExactly(Triple("deux", "2", 1), Triple("un", "1", 2))
            assertThat(copy.snapshot()).isEqualTo(store.snapshot())
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `une paire jamais refusée vaut zéro`() {
            assertThat(store.rejections("", "")).isZero()
        }

        @Test
        fun `une restauration invalide lève une exception sans rien modifier`() {
            store.recordRejection("a", "b")
            val cases = listOf(
                listOf(Triple("", "b", 1)),
                listOf(Triple("a", "", 1)),
                listOf(Triple("a".repeat(131), "b", 1)),
                listOf(Triple("a", "b".repeat(131), 1)),
                listOf(Triple("a", "b", 0)),
                listOf(Triple("a", "b", 1001)),
                listOf(Triple("a", "b", 1), Triple("A", "B", 1)),
            )

            for (bad in cases) {
                assertThatThrownBy { store.restore(bad) }.isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThat(store.snapshot()).containsExactly(Triple("a", "b", 1))
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `le plafond de paires oublie la moins récemment refusée`() {
            val small = PersistentRejectionStore(LearningConfig(maxRejections = 2))
            small.recordRejection("a", "1")
            small.recordRejection("b", "2")
            small.recordRejection("a", "1") // a devient la plus récente.

            small.recordRejection("c", "3")

            assertThat(small.size).isEqualTo(2)
            assertThat(small.rejections("b", "2")).isZero()
            assertThat(small.rejections("a", "1")).isEqualTo(2)
            assertThat(small.rejections("c", "3")).isEqualTo(1)
        }

        @Test
        fun `exactement maxRejections paires n'évince rien`() {
            val small = PersistentRejectionStore(LearningConfig(maxRejections = 2))

            small.recordRejection("a", "1")
            small.recordRejection("b", "2")

            assertThat(small.size).isEqualTo(2)
        }

        @Test
        fun `le compteur est plafonné`() {
            val small = PersistentRejectionStore(LearningConfig(maxRejectionCount = 3))

            repeat(5) { small.recordRejection("a", "1") }

            assertThat(small.rejections("a", "1")).isEqualTo(3)
        }

        @Test
        fun `la restauration accepte les textes de 130 caractères et un compteur au plafond`() {
            store.restore(listOf(Triple("a".repeat(130), "b".repeat(130), 1000)))

            assertThat(store.rejections("a".repeat(130), "b".repeat(130))).isEqualTo(1000)
        }

        @Test
        fun `la restauration accepte exactement maxRejections paires`() {
            val small = PersistentRejectionStore(LearningConfig(maxRejections = 1))

            small.restore(listOf(Triple("a", "b", 1)))

            assertThatThrownBy { small.restore(listOf(Triple("a", "b", 1), Triple("c", "d", 1))) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
