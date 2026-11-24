package nextglyph.engine.ngram

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Comptes de confusion du modèle de mot suivant (issue #55, §10.3). */
class NgramConfusionTest {

    private val accents = listOf("a", "à")
    private val articles = listOf("ces", "ses")

    private fun builder(
        sets: List<List<String>> = listOf(accents, articles),
        pruning: NgramPruning = NgramFixtures.KEEP_ALL,
    ): NgramModelBuilder = NgramModelBuilder("fr", pruning, confusionSets = sets).apply {
        // Le vocabulaire : chaque mot des contextes doit apparaître dans une phrase.
        addSentence(listOf("il", "a", "tout", "à", "ces", "ses", "elle"), 100)
    }

    private fun load(builder: NgramModelBuilder): NgramModel = NgramModel.load(ByteBuffer.wrap(builder.build()))

    @Nested
    inner class HappyPath {
        @Test
        fun `les comptes de chaque forme après un mot sont restitués dans l'ordre de l'ensemble`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), 5)
            b.addConfusionContext(listOf("il", "à"), 1)
            b.addConfusionContext(listOf("elle", "a"), 2)

            val model = load(b)

            assertThat(model.confusionCounts("il", accents)).containsExactly(5, 1)
            assertThat(model.confusionCounts("elle", accents)).containsExactly(2, 0)
        }

        @Test
        fun `la recherche du mot précédent ignore la casse et les accents`() {
            val b = builder()
            b.addConfusionContext(listOf("elle", "à"), 4)

            assertThat(load(b).confusionCounts("Élle", accents)).containsExactly(0, 4)
        }

        @Test
        fun `une forme est reconnue quelle que soit sa casse dans le corpus`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "À"), 3)

            assertThat(load(b).confusionCounts("il", accents)).containsExactly(0, 3)
        }

        @Test
        fun `chaque ensemble a ses propres comptes`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "ses"), 7)
            b.addConfusionContext(listOf("il", "a"), 2)

            val model = load(b)

            assertThat(model.confusionCounts("il", articles)).containsExactly(0, 7)
            assertThat(model.confusionCounts("il", accents)).containsExactly(2, 0)
        }

        @Test
        fun `un ensemble de trois formes est lu`() {
            val b = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL, confusionSets = listOf(listOf("ou", "où", "oui")))
            b.addSentence(listOf("il", "ou", "où", "oui"), 10)
            b.addConfusionContext(listOf("il", "où"), 3)
            b.addConfusionContext(listOf("il", "oui"), 4)

            assertThat(load(b).confusionCounts("il", listOf("ou", "où", "oui"))).containsExactly(0, 3, 4)
        }

        @Test
        fun `la construction est identique à chaque appel`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), 5)

            assertThat(b.build()).isEqualTo(b.build())
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un mot précédent inconnu du vocabulaire ne donne rien`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), 5)

            assertThat(load(b).confusionCounts("zzz", accents)).isNull()
        }

        @Test
        fun `un mot précédent jamais suivi d'une forme ne donne rien`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), 5)

            assertThat(load(b).confusionCounts("elle", accents)).isNull()
        }

        @Test
        fun `un ensemble absent du modèle ne donne rien`() {
            val b = builder(sets = listOf(accents))
            b.addConfusionContext(listOf("il", "a"), 5)

            assertThat(load(b).confusionCounts("il", articles)).isNull()
            assertThat(load(b).confusionCounts("il", listOf("à", "a"))).isNull()
        }

        @Test
        fun `le début de phrase n'est pas un mot précédent`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), 5)

            assertThat(load(b).confusionCounts(NgramModel.START, accents)).isNull()
        }

        @Test
        fun `un modèle sans confusion se charge et ne donne rien`() {
            val model = NgramFixtures.model()

            assertThat(model.confusionCounts("je", accents)).isNull()
        }

        @Test
        fun `un nombre de répétitions nul est refusé`() {
            assertThatThrownBy { builder().addConfusionContext(listOf("il", "a"), 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("répétitions")
        }

        @Test
        fun `des formes invalides sont refusées à la construction`() {
            assertThatThrownBy { builder(sets = listOf(listOf("a"))) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("2 à")
            assertThatThrownBy { builder(sets = listOf(listOf("a", "À"))) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minuscules")
            assertThatThrownBy { builder(sets = listOf(listOf("a", ""))) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("minuscules")
            assertThatThrownBy { builder(sets = listOf(listOf("a", "à"), listOf("à", "b"))) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("deux ensembles")
            assertThatThrownBy { builder(sets = List(256) { listOf("a$it", "b$it") }) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("255")
            assertThatThrownBy { builder(sets = listOf(listOf("a", "b".repeat(256)))) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("255 octets")
        }

        @Test
        fun `un seuil de confusion nul est refusé`() {
            assertThatThrownBy { NgramPruning(minConfusionCount = 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("confusions")
        }

        @Test
        fun `un ensemble de formes corrompu est signalé au chargement`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), 5)
            val bytes = b.build()
            val start = zoneStart(bytes)

            val oneForm = bytes.copyOf().also { it[start + 1] = 1 }
            val manySets = bytes.copyOf().also { it[start] = 9 }

            assertThatThrownBy { NgramModel.load(ByteBuffer.wrap(oneForm)) }
                .isInstanceOf(NgramFormatException::class.java).hasMessageContaining("1 formes confondues")
            assertThatThrownBy { NgramModel.load(ByteBuffer.wrap(manySets)) }
                .isInstanceOf(NgramFormatException::class.java)
        }

        @Test
        fun `un nombre d'entrées démesuré est signalé au chargement`() {
            val b = builder(sets = listOf(accents))
            b.addConfusionContext(listOf("il", "a"), 5)
            val bytes = b.build()
            // setCount, formCount, « a » (longueur + 1 octet), « à » (longueur + 2 octets), puis entryCount.
            val entryCount = zoneStart(bytes) + 1 + 1 + 2 + 3
            val broken = bytes.copyOf().also { it[entryCount + 3] = 99 }

            assertThatThrownBy { NgramModel.load(ByteBuffer.wrap(broken)) }
                .isInstanceOf(NgramFormatException::class.java).hasMessageContaining("entrées des confusions")
        }

        @Test
        fun `une zone des confusions plus longue que ses ensembles est signalée`() {
            val b = builder(sets = listOf(accents))
            b.addConfusionContext(listOf("il", "a"), 5)
            val bytes = b.build() + byteArrayOf(0)
            ByteBuffer.wrap(bytes).putInt(confusionLengthPosition(bytes), zoneLength(bytes) + 1)

            assertThatThrownBy { NgramModel.load(ByteBuffer.wrap(bytes)) }
                .isInstanceOf(NgramFormatException::class.java).hasMessageContaining("octets")
        }

        @Test
        fun `une taille de zone nulle est refusée`() {
            val bytes = NgramFixtures.bytes()
            ByteBuffer.wrap(bytes).putInt(confusionLengthPosition(bytes), 0)

            assertThatThrownBy { NgramModel.load(ByteBuffer.wrap(bytes)) }.isInstanceOf(NgramFormatException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un mot précédent vu exactement le seuil de fois est gardé, une fois de moins non`() {
            val pruning = NgramFixtures.KEEP_ALL.copy(minConfusionCount = 3)
            val b = builder(pruning = pruning)
            b.addConfusionContext(listOf("il", "a"), 2)
            b.addConfusionContext(listOf("elle", "a"), 2)
            b.addConfusionContext(listOf("elle", "à"), 1)

            val model = load(b)

            assertThat(model.confusionCounts("il", accents)).isNull()
            assertThat(model.confusionCounts("elle", accents)).containsExactly(2, 1)
        }

        @Test
        fun `des comptes au-delà de 65535 sont ramenés dans la plage en gardant leur rapport`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), 200_000)
            b.addConfusionContext(listOf("il", "à"), 100_000)

            val counts = load(b).confusionCounts("il", accents)!!

            assertThat(counts[0]).isLessThanOrEqualTo(NgramFormat.MAX_CONFUSION_COUNT).isGreaterThan(30_000)
            assertThat(counts[0].toDouble() / counts[1]).isBetween(1.99, 2.01)
        }

        @Test
        fun `exactement 65535 occurrences ne sont pas modifiées`() {
            val b = builder()
            b.addConfusionContext(listOf("il", "a"), NgramFormat.MAX_CONFUSION_COUNT)

            assertThat(load(b).confusionCounts("il", accents)).containsExactly(NgramFormat.MAX_CONFUSION_COUNT, 0)
        }

        @Test
        fun `une forme en tête de groupe n'a pas de mot précédent`() {
            val b = builder()
            b.addConfusionContext(listOf("a", "il"), 5)
            b.addConfusionContext(listOf("à"), 5)
            b.addConfusionContext(emptyList(), 5)

            assertThat(load(b).confusionCounts("il", accents)).isNull()
        }

        @Test
        fun `un mot précédent inconnu du corpus n'est pas compté`() {
            val b = builder()
            b.addConfusionContext(listOf("<inconnu>", "a"), 5)

            assertThat(load(b).confusionCounts("il", accents)).isNull()
        }

        @Test
        fun `un mot précédent hors du vocabulaire élagué n'est pas gardé`() {
            val b = NgramModelBuilder("fr", NgramFixtures.KEEP_ALL.copy(maxVocabulary = 1), confusionSets = listOf(accents))
            b.addSentence(listOf("il", "il", "il", "elle"))
            b.addConfusionContext(listOf("il", "a"), 5)
            b.addConfusionContext(listOf("elle", "a"), 5)

            val model = NgramModel.load(ByteBuffer.wrap(b.build()))

            assertThat(model.confusionCounts("il", accents)).containsExactly(5, 0)
            assertThat(model.confusionCounts("elle", accents)).isNull()
        }

        @Test
        fun `sans ensemble la zone ne contient que son compteur`() {
            val bytes = NgramFixtures.bytes()

            assertThat(zoneLength(bytes)).isEqualTo(1)
            assertThat(bytes.last()).isEqualTo(0.toByte())
        }

        @Test
        fun `un ensemble sans entrée gardée est écrit vide`() {
            val b = builder(sets = listOf(accents))

            assertThat(load(b).confusionCounts("il", accents)).isNull()
        }

        @Test
        fun `le nombre maximal de formes est accepté, un de plus refusé`() {
            val max = List(NgramFormat.MAX_CONFUSION_FORMS) { "f$it" }
            NgramModelBuilder("fr", NgramFixtures.KEEP_ALL, confusionSets = listOf(max))

            assertThatThrownBy { NgramModelBuilder("fr", NgramFixtures.KEEP_ALL, confusionSets = listOf(max + "fx")) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun confusionLengthPosition(bytes: ByteArray): Int = 7 + 2 + 5 * 4 // magic, version, longueur, étiquette « fr », 5 compteurs

    private fun zoneLength(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).getInt(confusionLengthPosition(bytes))

    private fun zoneStart(bytes: ByteArray): Int = bytes.size - zoneLength(bytes)
}
