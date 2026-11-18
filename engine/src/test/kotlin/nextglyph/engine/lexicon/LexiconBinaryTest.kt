package nextglyph.engine.lexicon

import nextglyph.engine.lexicon.LexiconFixtures.rawFile
import nextglyph.engine.lexicon.LexiconFixtures.rawLexicon
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.ThrowableAssertAlternative
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LexiconBinaryTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun load(bytes: ByteArray) = Lexicon.load(ByteBuffer.wrap(bytes))

    private fun assertCorrupt(action: () -> Unit): ThrowableAssertAlternative<LexiconFormatException> =
        assertThatExceptionOfType(LexiconFormatException::class.java)
            .isThrownBy { action() }
            .withMessageStartingWith("Lexique invalide : ")

    /** Résultats observables d'un lexique, pour comparer deux chargements. */
    private fun observe(lexicon: Lexicon): List<Any> {
        val keys = lexicon.completions("", Int.MAX_VALUE).map { it.key }.distinct()
        val prefixes = keys.flatMap { key -> (0..key.length).map { key.substring(0, it) } }.distinct()
        return listOf(lexicon.languageTag, lexicon.keyCount, lexicon.formCount) +
            prefixes.map { prefix ->
                val distribution = lexicon.distribution(prefix)
                listOf(
                    prefix,
                    distribution.totalMass,
                    distribution.endProbability,
                    LexiconAlphabet.CHARS.map(distribution::probability),
                    lexicon.completions(prefix, 3),
                    lexicon.displayForms(prefix),
                )
            }
    }

    /** "ab" (5) : un seul enregistrement terminal, étiquette de deux caractères. */
    private val singleWordFile = bytes(
        0x4E, 0x47, 0x4C, 0x58, // magic "NGLX"
        0x00, 0x01, // version
        0x02, 0x66, 0x72, // "fr"
        0, 0, 0, 1, // keyCount
        0, 0, 0, 1, // formCount
        0, 0, 0, 0, // rootOffset
        0, 0, 0, 7, // dataLength
        // bloc racine : 1 enregistrement ; TERMINAL | LONG_LABEL | 'a', +1 caractère 'b',
        // 1 forme de fréquence 5 identique à la clé
        1, 0xC2, 1, 3, 1, 5, 0,
    )

    @Nested
    inner class HappyPath {

        @Test
        fun `l'aller-retour binaire est identique`() {
            val original = LexiconFixtures.builder(SyntheticLexicon.entries(2_000)).toByteArray()

            val reloaded = load(original)

            assertThat(reloaded.toByteArray()).isEqualTo(original)
            assertThat(reloaded.sizeInBytes).isEqualTo(original.size)
            assertThat(observe(load(reloaded.toByteArray()))).isEqualTo(observe(reloaded))
        }

        @Test
        fun `le lexique construit et le lexique rechargé répondent pareil`() {
            val built = LexiconFixtures.sample()

            val reloaded = load(built.toByteArray())

            assertThat(observe(reloaded)).isEqualTo(observe(built))
        }

        @Test
        fun `writeTo écrit les mêmes octets que toByteArray`() {
            val lexicon = LexiconFixtures.sample()
            val out = ByteArrayOutputStream()

            lexicon.writeTo(out)

            assertThat(out.toByteArray()).isEqualTo(lexicon.toByteArray())
        }

        @Test
        fun `le format d'un mot unique est figé`() {
            val built = LexiconFixtures.builder(listOf("ab" to 5L)).toByteArray()

            assertThat(built).isEqualTo(singleWordFile)
            assertThat(load(singleWordFile).displayForms("ab")).containsExactly(LexiconEntry("ab", "ab", 5))
        }

        @Test
        fun `le format d'un nœud interne est figé`() {
            val built = LexiconFixtures.builder(listOf("a" to 3L, "ab" to 5L)).toByteArray()

            val expected = rawFile(
                intArrayOf(
                    // bloc enfant de "a", en premier (ordre postfixe) : TERMINAL | 'b', fréquence 5
                    1, 0x43, 1, 5, 0,
                    // bloc racine en 5 : HAS_CHILDREN | TERMINAL | 'a', masse 8 = 2^3 → 3·512 = 0x0600,
                    // fréquence max 5, distance 6 − 0 = 6, puis 1 forme de fréquence 3
                    1, 0x62, 0x06, 0x00, 5, 6, 1, 3, 0,
                ),
                rootOffset = 5,
                keyCount = 2,
                formCount = 2,
            )
            assertThat(built).isEqualTo(expected)
        }

        @Test
        fun `une forme différente de la clé est stockée en UTF-8`() {
            val built = LexiconFixtures.builder(listOf("à" to 2L)).toByteArray()

            assertThat(built).isEqualTo(rawFile(intArrayOf(1, 0x42, 1, 2, 2, 0xC3, 0xA0), rootOffset = 0))
        }

        @Test
        fun `charge un tampon entre sa position et sa limite sans le modifier`() {
            val file = LexiconFixtures.sample().toByteArray()
            val buffer = ByteBuffer.allocate(file.size + 10)
            buffer.position(3)
            buffer.put(file)
            buffer.position(3)
            buffer.limit(3 + file.size)

            val lexicon = Lexicon.load(buffer)
            val forms = lexicon.completions("", 3)

            assertThat(forms.map { it.form }).containsExactly("a", "chat", "à")
            assertThat(lexicon.toByteArray()).isEqualTo(file)
            assertThat(buffer.position()).isEqualTo(3)
            assertThat(buffer.limit()).isEqualTo(3 + file.size)
        }

        @Test
        fun `charge un tampon direct, quel que soit son ordre d'octets`() {
            val file = LexiconFixtures.sample().toByteArray()
            val direct = ByteBuffer.allocateDirect(file.size).order(ByteOrder.LITTLE_ENDIAN)
            direct.put(file)
            direct.flip()

            val lexicon = Lexicon.load(direct)

            assertThat(observe(lexicon)).isEqualTo(observe(LexiconFixtures.sample()))
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest(name = "octet {0}")
        @ValueSource(ints = [0, 1, 2, 3])
        fun `refuse un magic invalide`(index: Int) {
            val file = singleWordFile.copyOf()
            file[index] = (file[index] + 1).toByte()

            assertCorrupt { load(file) }.withMessageContaining("magic")
        }

        @ParameterizedTest(name = "version {0}")
        @ValueSource(ints = [0, 2, 0x0101])
        fun `refuse une version inconnue`(version: Int) {
            val file = singleWordFile.copyOf()
            file[4] = (version ushr 8).toByte()
            file[5] = version.toByte()

            assertCorrupt { load(file) }.withMessageContaining("version $version")
        }

        @Test
        fun `refuse un fichier tronqué à chaque octet`() {
            for (length in 0 until singleWordFile.size) {
                assertCorrupt { load(singleWordFile.copyOf(length)) }
            }
        }

        @Test
        fun `signale la troncature de l'en-tête`() {
            assertCorrupt { load(singleWordFile.copyOf(10)) }.withMessageContaining("en-tête tronqué à l'octet 10")
        }

        @Test
        fun `refuse des octets en trop`() {
            assertCorrupt { load(singleWordFile + 0) }
                .withMessageContaining("taille incohérente : 8 octets de données au lieu de 7")
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["", "f", "f1", "fr_"])
        fun `refuse une étiquette de langue invalide`(tag: String) {
            assertCorrupt { load(rawFile(intArrayOf(0), rootOffset = 0, tag = tag)) }.withMessageContaining("étiquette")
        }

        @Test
        fun `refuse des compteurs incohérents`() {
            assertCorrupt { load(rawFile(intArrayOf(0), 0, keyCount = -1, formCount = 0)) }.withMessageContaining("compteurs")
            assertCorrupt { load(rawFile(intArrayOf(0), 0, keyCount = 2, formCount = 1)) }.withMessageContaining("compteurs")
        }

        @ParameterizedTest(name = "racine en {0}")
        @ValueSource(ints = [-1, 1, Int.MAX_VALUE])
        fun `refuse un bloc racine hors de la zone de données`(rootOffset: Int) {
            assertCorrupt { load(rawFile(intArrayOf(0), rootOffset)) }.withMessageContaining("bloc racine")
        }

        @Test
        fun `refuse une taille de données négative`() {
            assertCorrupt { load(rawFile(intArrayOf(), 0, dataLength = -1)) }
        }

        @Test
        fun `refuse un caractère hors alphabet dans un enregistrement`() {
            val lexicon = rawLexicon(intArrayOf(1, 0x40 or 28, 1, 5, 0), rootOffset = 0)

            assertCorrupt { lexicon.distribution("") }.withMessageContaining("index 28")
            assertCorrupt { lexicon.completions("", 1) }
        }

        @Test
        fun `refuse un caractère hors alphabet dans une étiquette`() {
            val lexicon = rawLexicon(intArrayOf(1, 0xC2, 1, 31, 1, 5, 0), rootOffset = 0)

            assertCorrupt { lexicon.distribution("ab") }.withMessageContaining("index 31")
            assertCorrupt { lexicon.distribution("a") }
            assertCorrupt { lexicon.completions("", 1) }
        }

        @Test
        fun `refuse un bloc enfant placé après son parent`() {
            // HAS_CHILDREN | TERMINAL | 'a' en 1, distance 2 : le bloc enfant serait en −1.
            val lexicon = rawLexicon(intArrayOf(1, 0x62, 0, 0, 5, 2, 1, 3, 0), rootOffset = 0)

            assertCorrupt { lexicon.distribution("") }.withMessageContaining("hors limites")
        }

        @Test
        fun `refuse un bloc enfant qui chevauche son parent, sans boucler`() {
            // L'enregistrement 'a' en 1 désigne le bloc 0, qui le contient : cycle a → a → a…
            val lexicon = rawLexicon(intArrayOf(1, 0x62, 0, 0, 5, 1, 1, 3, 0), rootOffset = 0)

            assertCorrupt { lexicon.distribution("aa") }.withMessageContaining("déborde")
            assertCorrupt { lexicon.distribution("a") }
            assertCorrupt { lexicon.completions("", Int.MAX_VALUE) }
        }

        @Test
        fun `refuse un entier variable de plus de 9 octets`() {
            val lexicon = rawLexicon(intArrayOf(1, 0x42, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01), 0)

            assertCorrupt { lexicon.distribution("") }.withMessageContaining("trop long")
        }

        @Test
        fun `refuse une étiquette plus longue qu'une clé`() {
            val label = IntArray(Lexicon.MAX_KEY_LENGTH) { 2 }
            val lexicon = rawLexicon(intArrayOf(1, 0xC2, 0x80, 0x01) + label + intArrayOf(1, 5, 0), rootOffset = 0)

            assertCorrupt { lexicon.distribution("") }.withMessageContaining("maximum ${Lexicon.MAX_KEY_LENGTH - 1}")
        }

        @Test
        fun `refuse une forme qui dépasse la fin des données`() {
            val lexicon = rawLexicon(intArrayOf(1, 0x42, 1, 5, 10, 0x78), rootOffset = 0)

            assertCorrupt { lexicon.distribution("") }.withMessageContaining("tronquées")
            assertCorrupt { lexicon.displayForms("a") }
        }

        @Test
        fun `refuse un bloc annonçant plus d'enregistrements qu'il n'en contient`() {
            val lexicon = rawLexicon(intArrayOf(3, 0x42, 1, 5, 0), rootOffset = 0)

            assertCorrupt { lexicon.distribution("") }.withMessageContaining("position 5 hors de la zone")
            assertCorrupt { lexicon.distribution("z") }
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `l'aller-retour d'un lexique vide est identique`() {
            val empty = LexiconBuilder("fr").toByteArray()

            assertThat(load(empty).toByteArray()).isEqualTo(empty)
            assertThat(empty).isEqualTo(rawFile(intArrayOf(0), rootOffset = 0, keyCount = 0, formCount = 0))
        }

        @Test
        fun `l'aller-retour conserve les fréquences extrêmes`() {
            val built = LexiconFixtures.lexicon(listOf("a" to 1L, "ab" to Long.MAX_VALUE, "ac" to Long.MAX_VALUE))

            val reloaded = load(built.toByteArray())

            assertThat(reloaded.completions("", 3).map { it.frequency }).containsExactly(Long.MAX_VALUE, Long.MAX_VALUE, 1L)
            assertThat(observe(reloaded)).isEqualTo(observe(built))
        }

        @Test
        fun `accepte le dernier index de l'alphabet`() {
            val lexicon = rawLexicon(intArrayOf(1, 0x40 or 27, 1, 5, 0), rootOffset = 0)

            assertThat(lexicon.distribution("").probability('z')).isEqualTo(1.0)
        }

        @Test
        fun `accepte une forme qui finit exactement à la fin des données`() {
            val lexicon = rawLexicon(intArrayOf(1, 0x42, 1, 5, 1, 0x78), rootOffset = 0)

            assertThat(lexicon.displayForms("a")).containsExactly(LexiconEntry("x", "a", 5))
        }

        @Test
        fun `accepte une étiquette de la longueur maximale d'une clé`() {
            val label = IntArray(Lexicon.MAX_KEY_LENGTH - 1) { 2 }
            val lexicon = rawLexicon(intArrayOf(1, 0xC2, 0x7F) + label + intArrayOf(1, 5, 0), rootOffset = 0)

            assertThat(lexicon.completions("", 1).single().key).isEqualTo("a".repeat(Lexicon.MAX_KEY_LENGTH))
        }

        @Test
        fun `accepte un bloc racine en dernière position et des compteurs égaux`() {
            val lexicon = load(rawFile(intArrayOf(0, 1, 0x42, 1, 5, 0), rootOffset = 1, keyCount = 1, formCount = 1))

            assertThat(lexicon.displayForms("a")).hasSize(1)
            assertThat(load(rawFile(intArrayOf(0), rootOffset = 0, keyCount = 0, formCount = 0)).keyCount).isZero()
        }

        @Test
        fun `lit les compteurs de l'en-tête sur 32 bits`() {
            val lexicon = load(rawFile(intArrayOf(0), rootOffset = 0, keyCount = 0x7F81C3E5, formCount = 0x7F81C3E6))

            assertThat(lexicon.keyCount).isEqualTo(0x7F81C3E5)
            assertThat(lexicon.formCount).isEqualTo(0x7F81C3E6)
        }

        @Test
        fun `accepte un entier variable de exactement 9 octets`() {
            val nine = intArrayOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F)
            val lexicon = rawLexicon(intArrayOf(1, 0x42, 1) + nine + intArrayOf(0), rootOffset = 0)

            assertThat(lexicon.displayForms("a").single().frequency).isEqualTo(Long.MAX_VALUE)
        }

        @Test
        fun `un bloc enfant juste avant le bloc de son parent est accepté`() {
            // Bloc enfant [0, 5) puis bloc racine en 5 dont l'enregistrement commence en 6.
            val lexicon = rawLexicon(intArrayOf(1, 0x43, 1, 5, 0, 1, 0x62, 0x06, 0x00, 5, 6, 1, 3, 0), rootOffset = 5)

            assertThat(lexicon.completions("", 5).map { it.form }).containsExactly("ab", "a")
            assertThat(lexicon.distribution("a").endProbability).isEqualTo(3.0 / 8)
        }
    }
}
