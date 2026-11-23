package nextglyph.engine.ngram

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class NgramModelTest {

    private fun texts(words: List<NextWord>) = words.map { it.text }

    private fun load(bytes: ByteArray) = NgramModel.load(ByteBuffer.wrap(bytes))

    @Nested
    inner class HappyPath {
        @Test
        fun `après un mot, les suivants sont classés par probabilité décroissante`() {
            val model = NgramFixtures.model()

            val words = model.next(listOf("je"), 3)

            assertThat(texts(words)).containsExactly("suis", "veux")
            assertThat(words[0].probability).isCloseTo(0.8, within(0.04))
            assertThat(words[1].probability).isCloseTo(0.2, within(0.02))
        }

        @Test
        fun `un contexte vide désigne le début de phrase`() {
            val model = NgramFixtures.model()

            val words = model.next(emptyList(), 3)

            assertThat(texts(words)).containsExactly("je", "tu", "j'")
            assertThat(words[0].probability).isCloseTo(0.5, within(0.03))
        }

        @Test
        fun `le marqueur de début de phrase seul équivaut à un contexte vide`() {
            val model = NgramFixtures.model()

            assertThat(model.next(listOf(NgramModel.START), 3)).isEqualTo(model.next(emptyList(), 3))
        }

        @Test
        fun `le trigramme prime sur le bigramme et le bigramme complète avec le repli`() {
            val model = NgramFixtures.model()

            val words = model.next(listOf("tu", "suis"), 3)

            assertThat(texts(words)).containsExactly("ça", "content", "fatigué")
            assertThat(words[0].probability).isCloseTo(1.0, within(0.04))
            assertThat(words[1].probability).isCloseTo(NgramModel.BACKOFF * 0.5, within(0.01))
            assertThat(words[2].probability).isCloseTo(NgramModel.BACKOFF * 0.3, within(0.01))
        }

        @Test
        fun `le trigramme du premier mot de la phrase s'obtient avec le début de phrase`() {
            val model = NgramFixtures.model(
                listOf("je suis content" to 3, "il suis ça" to 1, "il est là" to 5, "elle est là" to 1),
            )

            val words = model.next(listOf(NgramModel.START, "je"), 1)

            assertThat(texts(words)).containsExactly("suis")
            assertThat(model.next(listOf("est"), 1)[0].text).isEqualTo("là")
        }

        @Test
        fun `seuls les deux derniers mots du contexte comptent`() {
            val model = NgramFixtures.model()

            assertThat(model.next(listOf("n'importe", "quoi", "tu", "suis"), 1)).isEqualTo(model.next(listOf("tu", "suis"), 1))
        }

        @Test
        fun `la forme d'affichage est rendue, la recherche ignore casse et accents`() {
            val model = NgramFixtures.model(listOf("I am here" to 2, "i am there" to 1, "je suis là" to 1, "il est parti" to 1))

            assertThat(texts(model.next(listOf("I"), 2))).containsExactly("am")
            assertThat(texts(model.next(listOf("i"), 2))).containsExactly("am")
            assertThat(texts(model.next(listOf("JE"), 1))).containsExactly("suis")
            assertThat(texts(model.next(listOf("est"), 1))).containsExactly("parti")
            assertThat(texts(model.next(listOf("il"), 1))).containsExactly("est")
        }

        @Test
        fun `les mots accentués et les élisions gardent leur forme`() {
            val model = NgramFixtures.model(listOf("l' école est là" to 3, "l' ecole est là" to 1), "fr")

            assertThat(texts(model.next(emptyList(), 1))).containsExactly("l'")
            assertThat(texts(model.next(listOf("l'"), 1))).containsExactly("école")
            assertThat(model.contains("ecole")).isTrue()
        }

        @Test
        fun `les compteurs et l'étiquette sont lus dans le fichier`() {
            val bytes = NgramFixtures.bytes()
            val model = load(bytes)

            assertThat(model.languageTag).isEqualTo("fr")
            assertThat(model.sizeInBytes).isEqualTo(bytes.size)
            assertThat(model.vocabularySize).isEqualTo(16)
            assertThat(model.bigramCount).isPositive()
            assertThat(model.trigramContextCount).isPositive()
            assertThat(model.trigramCount).isGreaterThanOrEqualTo(model.trigramContextCount)
        }

        @Test
        fun `le chargement lit à partir de la position du tampon`() {
            val bytes = NgramFixtures.bytes()
            val shifted = ByteBuffer.allocate(bytes.size + 5)
            shifted.position(5)
            shifted.put(bytes)
            shifted.position(5)

            val model = NgramModel.load(shifted)

            assertThat(texts(model.next(listOf("je"), 1))).containsExactly("suis")
            assertThat(shifted.position()).isEqualTo(5)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un mot précédent inconnu ne donne aucune proposition`() {
            val model = NgramFixtures.model()

            assertThat(model.next(listOf("zzz"), 3)).isEmpty()
            assertThat(model.next(listOf("je", "zzz"), 3)).isEmpty()
            assertThat(model.next(listOf("3D"), 3)).isEmpty()
            assertThat(model.next(listOf(""), 3)).isEmpty()
            assertThat(model.next(listOf("deux mots"), 3)).isEmpty()
            assertThat(model.next(listOf("straße"), 3)).isEmpty()
        }

        @Test
        fun `un avant-dernier mot inconnu se replie sur le bigramme`() {
            val model = NgramFixtures.model()

            assertThat(model.next(listOf("zzz", "suis"), 3)).isEqualTo(model.next(listOf("suis"), 3))
        }

        @Test
        fun `une limite négative est refusée`() {
            val model = NgramFixtures.model()

            assertThatThrownBy { model.next(listOf("je"), -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("limite")
        }

        @Test
        fun `un fichier sans le magic est refusé`() {
            val bytes = NgramFixtures.bytes()
            bytes[0] = 'X'.code.toByte()

            assertThatThrownBy { load(bytes) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("magic invalide")
        }

        @Test
        fun `une version inconnue est refusée`() {
            val bytes = NgramFixtures.bytes()
            bytes[5] = 2

            assertThatThrownBy { load(bytes) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("version 2")
        }

        @Test
        fun `une étiquette de langue invalide est refusée`() {
            val bytes = NgramFixtures.bytes()
            bytes[7] = '1'.code.toByte()

            assertThatThrownBy { load(bytes) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("étiquette de langue invalide")
        }

        @Test
        fun `une longueur d'étiquette nulle ou trop grande est refusée`() {
            for (length in listOf(0, NgramFormat.MAX_TAG_LENGTH + 1)) {
                val bytes = NgramFixtures.bytes()
                bytes[6] = length.toByte()

                assertThatThrownBy { load(bytes) }
                    .isInstanceOf(NgramFormatException::class.java)
                    .hasMessageContaining("longueur d'étiquette")
            }
        }

        @Test
        fun `un fichier tronqué est refusé`() {
            val bytes = NgramFixtures.bytes()

            assertThatThrownBy { load(bytes.copyOf(bytes.size - 1)) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("taille incohérente")
            assertThatThrownBy { load(bytes.copyOf(10)) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("tronqué")
            assertThatThrownBy { load(ByteArray(0)) }
                .isInstanceOf(NgramFormatException::class.java)
        }

        @Test
        fun `un fichier suivi d'octets en trop est refusé`() {
            val bytes = NgramFixtures.bytes()

            assertThatThrownBy { load(bytes.copyOf(bytes.size + 1)) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("taille incohérente")
        }

        @Test
        fun `des compteurs négatifs ou un vocabulaire trop grand sont refusés`() {
            val tag = 2
            val vocabulary = 7 + tag
            val blob = vocabulary + 4
            for ((position, message) in listOf(vocabulary to "vocabulaire invalide", blob to "compteurs négatifs")) {
                val bytes = NgramFixtures.bytes()
                bytes[position] = 0x80.toByte()

                assertThatThrownBy { load(bytes) }
                    .isInstanceOf(NgramFormatException::class.java)
                    .hasMessageContaining(message)
            }
        }

        @Test
        fun `un identifiant de suivant hors du vocabulaire est signalé à la lecture`() {
            val model = NgramFixtures.model()
            val bytes = NgramFixtures.bytes()
            val recordsStart = bigramRecordsStart(model)
            bytes[recordsStart] = 0xFF.toByte()
            bytes[recordsStart + 1] = 0xFF.toByte()

            assertThatThrownBy { load(bytes).next(emptyList(), 3) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("hors du vocabulaire")
        }

        @Test
        fun `l'identifiant zéro dans un suivant est signalé à la lecture`() {
            val model = NgramFixtures.model()
            val bytes = NgramFixtures.bytes()
            val recordsStart = bigramRecordsStart(model)
            bytes[recordsStart] = 0
            bytes[recordsStart + 1] = 0

            assertThatThrownBy { load(bytes).next(emptyList(), 3) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("hors du vocabulaire")
        }

        @Test
        fun `un offset de suivants incohérent est signalé à la lecture`() {
            val model = NgramFixtures.model()
            val bytes = NgramFixtures.bytes()
            val offsets = bigramOffsetsStart(model)
            // Offset de fin des suivants du début de phrase : on l'envoie très loin.
            bytes[offsets + 4] = 0x00
            bytes[offsets + 5] = 0x7F

            assertThatThrownBy { load(bytes).next(emptyList(), 3) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("suivants")
        }

        @Test
        fun `un offset de mot hors de la zone est signalé à la recherche`() {
            val model = NgramFixtures.model()
            val bytes = NgramFixtures.bytes()
            val offsets = wordOffsetsStart(model)
            bytes[offsets + 4 * 3] = 0x7F

            assertThatThrownBy { load(bytes).next(listOf("ai"), 3) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("hors de la zone")
        }

        @Test
        fun `une longueur de clé démesurée est signalée à la recherche`() {
            val model = NgramFixtures.model()
            val bytes = NgramFixtures.bytes()
            val blob = wordOffsetsStart(model) + 4 * (model.vocabularySize + 1)
            // Le premier mot est « ai » : on annonce une clé qui déborde de la zone.
            bytes[blob] = 0xFF.toByte()

            assertThatThrownBy { load(bytes).next(listOf("ai"), 1) }
                .isInstanceOf(NgramFormatException::class.java)
                .hasMessageContaining("tronqué")
        }

        @Test
        fun `une forme d'affichage qui déborde est signalée à la lecture`() {
            val model = NgramFixtures.model(listOf("é a" to 3, "a é" to 1))
            val bytes = NgramFixtures.bytes(listOf("é a" to 3, "a é" to 1))
            // Zone des mots : « a » (1, a, 0) puis « e » + affichage « é » (1, e, 2, é) ; on gonfle la longueur d'affichage.
            val blob = wordOffsetsStart(model) + 4 * (model.vocabularySize + 1)
            val displayLengthAt = blob + 3 + 2
            assertThat(bytes[displayLengthAt].toInt()).isEqualTo(2)
            bytes[displayLengthAt] = 0xF0.toByte()

            assertThatThrownBy { load(bytes).next(listOf("a"), 1) }
                .isInstanceOf(NgramFormatException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une limite nulle ne renvoie rien et une limite trop grande renvoie tout`() {
            val model = NgramFixtures.model()

            assertThat(model.next(listOf("je"), 0)).isEmpty()
            assertThat(model.next(listOf("je"), 1)).hasSize(1)
            assertThat(model.next(listOf("je"), 2)).hasSize(2)
            assertThat(model.next(listOf("je"), 100)).hasSize(2)
        }

        @Test
        fun `un modèle sans phrase se charge et ne propose rien`() {
            val model = NgramFixtures.model(emptyList())

            assertThat(model.vocabularySize).isZero()
            assertThat(model.bigramCount).isZero()
            assertThat(model.trigramContextCount).isZero()
            assertThat(model.next(emptyList(), 3)).isEmpty()
            assertThat(model.next(listOf("je"), 3)).isEmpty()
        }

        @Test
        fun `un modèle d'un seul mot`() {
            val model = NgramFixtures.model(listOf("oui" to 1))

            assertThat(model.vocabularySize).isEqualTo(1)
            assertThat(texts(model.next(emptyList(), 3))).containsExactly("oui")
            assertThat(model.next(listOf("oui"), 3)).isEmpty()
            assertThat(model.contains("oui")).isTrue()
            assertThat(model.contains("non")).isFalse()
        }

        @Test
        fun `les mots sont retrouvés par recherche dichotomique quel que soit leur rang`() {
            val words = ('a'..'z').map { "x$it" } + listOf("a", "aa", "b", "z", "zz")
            val sentences = words.windowed(2).map { (a, b) -> "$a $b" to 2 }
            val model = NgramFixtures.model(sentences)

            for ((a, b) in words.windowed(2)) {
                assertThat(texts(model.next(listOf(a), 1))).`as`(a).contains(b)
            }
            assertThat(model.contains("a")).isTrue()
            assertThat(model.contains("zz")).isTrue()
            assertThat(model.contains("0")).isFalse()
            assertThat(model.contains("y")).isFalse()
            assertThat(model.contains("xa")).isTrue()
            assertThat(model.contains("xz")).isTrue()
            assertThat(model.contains("aaa")).isFalse()
            assertThat(model.contains("xa-")).isFalse()
        }

        @Test
        fun `une clé qui n'est qu'un préfixe d'un mot du vocabulaire est inconnue`() {
            val model = NgramFixtures.model(listOf("chat noir" to 2, "chats noirs" to 2))

            assertThat(model.contains("chat")).isTrue()
            assertThat(model.contains("cha")).isFalse()
            assertThat(model.contains("chatx")).isFalse()
        }

        @Test
        fun `la probabilité décodée reste entre zéro et un`() {
            val words = NgramFixtures.model().next(emptyList(), 10)

            assertThat(words).allSatisfy { assertThat(it.probability).isBetween(0.0, 1.0) }
        }
    }

    // Positions dans le fichier des zones lisibles, pour fabriquer des fichiers corrompus.
    private fun wordOffsetsStart(model: NgramModel) = NgramFormat.FIXED_HEADER_SIZE + model.languageTag.length

    private fun bigramOffsetsStart(model: NgramModel): Int {
        val blobLength = NgramFixtures.bytes().let { ByteBuffer.wrap(it).getInt(7 + model.languageTag.length + 4) }
        return wordOffsetsStart(model) + 4 * (model.vocabularySize + 1) + blobLength
    }

    private fun bigramRecordsStart(model: NgramModel) = bigramOffsetsStart(model) + 4 * (model.vocabularySize + 2)
}
