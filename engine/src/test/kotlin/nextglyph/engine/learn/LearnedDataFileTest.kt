package nextglyph.engine.learn

import nextglyph.engine.predict.PredictionSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32

class LearnedDataFileTest {

    private val settings = PredictionSettings()

    private fun populated(): LearnedData {
        val data = LearnedData()
        for (word in listOf("Zorglub", "blorp")) repeat(2) { data.personal.observe(word, "fr", true, settings) }
        data.personal.observe("quux", "en", true, settings)
        data.rejections.recordRejection("ecole", "école")
        data.rejections.recordRejection("ecole", "école")
        data.rejections.recordRejection("bonj", "bonjour")
        repeat(30) { data.spatial.stage('e', 0.1, -0.05, settings) }
        repeat(30) { data.spatial.stage('t', -0.2, 0.1, settings) }
        data.spatial.confirm()
        return data
    }

    private fun bytesOf(data: LearnedData): ByteArray = ByteArrayOutputStream().also { LearnedDataFile.write(data, it) }.toByteArray()

    private fun snapshotOf(data: LearnedData): List<Any> = listOf(
        data.personal.words(),
        data.personal.pendingWords(),
        data.rejections.snapshot(),
        data.spatial.snapshot().map { listOf(it.samples, it.meanX, it.meanY, it.varX, it.varY) },
    )

    /** Fichier assemblé à la main : en-tête, nombre de sections, puis chaque section (tag, version, charge). */
    private fun file(vararg sections: Triple<Int, Int, ByteArray>, version: Int = 1, count: Int = sections.size): ByteArray {
        val w = ByteWriter()
        w.bytes("NGLS".toByteArray())
        w.u16(version)
        w.u8(count)
        for ((tag, sectionVersion, payload) in sections) w.bytes(section(tag, sectionVersion, payload))
        return w.toByteArray()
    }

    private fun section(tag: Int, version: Int, payload: ByteArray): ByteArray {
        val body = ByteWriter().apply {
            u8(tag)
            u8(version)
            u32(payload.size.toLong())
            bytes(payload)
        }.toByteArray()
        val crc = CRC32().apply { update(body) }.value
        return ByteWriter().apply {
            bytes(body)
            u32(crc)
        }.toByteArray()
    }

    private fun payload(block: ByteWriter.() -> Unit): ByteArray = ByteWriter().apply(block).toByteArray()

    private fun emptySpatial(): ByteArray = payload {
        repeat(26) {
            varInt(0)
            repeat(4) { double(0.0) }
        }
    }

    private val emptyPersonal = payload { varInt(0); varInt(0) }
    private val emptyRejections = payload { varInt(0) }

    @Nested
    inner class HappyPath {

        @Test
        fun `l'écriture vide le flux sans le fermer`() {
            var flushed = 0
            var closed = false
            val out = object : ByteArrayOutputStream() {
                override fun flush() {
                    flushed++
                }

                override fun close() {
                    closed = true
                }
            }

            LearnedDataFile.write(populated(), out)

            assertThat(flushed).isPositive()
            assertThat(closed).isFalse()
            assertThat(out.size()).isPositive()
        }

        @Test
        fun `l'état écrit puis relu est identique`() {
            val original = populated()

            val result = LearnedDataFile.read(ByteArrayInputStream(bytesOf(original)))

            assertThat(result.isClean).isTrue()
            assertThat(snapshotOf(result.data)).isEqualTo(snapshotOf(original))
            assertThat(result.data.personal.words("fr").map { it.form }).containsExactly("blorp", "Zorglub")
            assertThat(result.data.personal.pendingWords().single().form).isEqualTo("quux")
            assertThat(result.data.rejections.rejections("ecole", "école")).isEqualTo(2)
            assertThat(result.data.spatial.offsetX('t')).isEqualTo(original.spatial.offsetX('t'))
        }

        @Test
        fun `un état vide s'écrit et se relit`() {
            val result = LearnedDataFile.decode(bytesOf(LearnedData()))

            assertThat(result.isClean).isTrue()
            assertThat(result.data.isEmpty).isTrue()
        }

        @Test
        fun `le format est stable octet par octet`() {
            val bytes = bytesOf(LearnedData())

            // "NGLS", version 1, 3 sections ; la première (dictionnaire personnel vide) : tag 1, version 1, 2 octets.
            assertThat(bytes.copyOfRange(0, 7)).containsExactly('N'.code.toByte(), 'G'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 0, 1, 3)
            assertThat(bytes.copyOfRange(7, 15)).containsExactly(1, 1, 0, 0, 0, 2, 0, 0)
            assertThat(bytes).hasSize(7 + (6 + 2 + 4) + (6 + 1 + 4) + (6 + 26 * 33 + 4))
        }

        @Test
        fun `les sections inconnues d'une version plus récente sont ignorées`() {
            val original = populated()
            val known = bytesOf(original)
            val extra = file(Triple(9, 7, byteArrayOf(1, 2, 3)), count = 1).copyOfRange(7, 7 + 6 + 3 + 4)
            // On insère la section inconnue entre l'en-tête et les sections connues, et on corrige le compte.
            val withExtra = known.copyOfRange(0, 6) + byteArrayOf(4) + extra + known.copyOfRange(7, known.size)

            val result = LearnedDataFile.decode(withExtra)

            assertThat(result.isClean).isTrue()
            assertThat(snapshotOf(result.data)).isEqualTo(snapshotOf(original))
        }

        @Test
        fun `une lecture écrit dans l'état lu seulement`() {
            val original = populated()
            val result = LearnedDataFile.decode(bytesOf(original))

            result.data.clear()

            assertThat(original.isEmpty).isFalse()
            assertThat(result.data.isEmpty).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `une charge utile avec des octets en trop est invalide pour chaque section`() {
            val personal = LearnedDataFile.decode(file(Triple(1, 1, emptyPersonal + byteArrayOf(0))))
            val spatial = LearnedDataFile.decode(file(Triple(3, 1, emptySpatial() + byteArrayOf(0))))

            assertThat(personal.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
            assertThat(spatial.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
            assertThat(personal.isClean).isFalse()
        }

        @Test
        fun `chaque octet altéré est détecté, sans exception, et l'état est identique ou vide par section`() {
            val original = populated()
            val bytes = bytesOf(original)
            val expected = snapshotOf(original)
            val empty = snapshotOf(LearnedData())

            for (mask in listOf(0xFF, 0x01, 0x80)) {
                for (i in bytes.indices) {
                    val corrupted = bytes.copyOf().also { it[i] = (it[i].toInt() xor mask).toByte() }

                    val result = LearnedDataFile.decode(corrupted)

                    assertThat(result.issues).describedAs("octet $i, masque $mask").isNotEmpty()
                    val actual = snapshotOf(result.data)
                    for (part in actual.indices) {
                        assertThat(actual[part]).describedAs("partie $part, octet $i, masque $mask").isIn(expected[part], empty[part])
                    }
                }
            }
        }

        @Test
        fun `chaque troncature est détectée, sans exception`() {
            val original = populated()
            val bytes = bytesOf(original)
            val expected = snapshotOf(original)
            val empty = snapshotOf(LearnedData())

            for (length in 0 until bytes.size) {
                val result = LearnedDataFile.decode(bytes.copyOf(length))

                assertThat(result.issues).describedAs("longueur $length").isNotEmpty()
                val actual = snapshotOf(result.data)
                for (part in actual.indices) assertThat(actual[part]).isIn(expected[part], empty[part])
            }
        }

        @Test
        fun `des octets en trop sont signalés`() {
            val result = LearnedDataFile.decode(bytesOf(populated()) + byteArrayOf(0))

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.TRAILING_DATA)
        }

        @Test
        fun `un fichier plus ancien que ce lecteur ou plus récent donne un état vide`() {
            for (version in listOf(0, 2, 256)) {
                val bytes = bytesOf(populated()).also {
                    it[4] = (version shr 8).toByte()
                    it[5] = version.toByte()
                }

                val result = LearnedDataFile.decode(bytes)

                assertThat(result.issues.map { it.kind }).describedAs("version $version").containsExactly(LoadIssueKind.UNSUPPORTED_VERSION)
                assertThat(result.data.isEmpty).isTrue()
            }
        }

        @ParameterizedTest
        @ValueSource(ints = [0, 1, 5, 6])
        fun `un fichier qui n'est pas un fichier Nextglyph est refusé`(length: Int) {
            val result = LearnedDataFile.decode(ByteArray(length) { 'x'.code.toByte() })

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.BAD_MAGIC)
            assertThat(result.data.isEmpty).isTrue()
        }

        @Test
        fun `une section dont la somme de contrôle est fausse arrête la lecture, les précédentes sont gardées`() {
            val original = populated()
            val bytes = bytesOf(original)
            // Octet au milieu de la troisième section (modèle spatial).
            bytes[bytes.size - 50] = (bytes[bytes.size - 50].toInt() xor 1).toByte()

            val result = LearnedDataFile.decode(bytes)

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.CHECKSUM)
            assertThat(result.data.personal.words()).isEqualTo(original.personal.words())
            assertThat(result.data.rejections.snapshot()).isEqualTo(original.rejections.snapshot())
            assertThat(result.data.spatial.isEmpty).isTrue()
        }

        @Test
        fun `une section en double est signalée et ignorée`() {
            val bytes = file(Triple(1, 1, emptyPersonal), Triple(1, 1, emptyPersonal), Triple(2, 1, emptyRejections))

            val result = LearnedDataFile.decode(bytes)

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
        }

        @Test
        fun `une version de section inconnue est signalée pour une section connue seulement`() {
            val known = LearnedDataFile.decode(file(Triple(2, 9, emptyRejections)))
            val unknown = LearnedDataFile.decode(file(Triple(42, 9, byteArrayOf(1))))

            assertThat(known.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
            assertThat(unknown.isClean).isTrue()
        }

        @Test
        fun `une section au contenu invalide est signalée, les autres sont lues`() {
            val badWord = payload {
                varInt(1)
                string("fr")
                varInt(1)
                string("a b")
                varInt(2)
                varInt(0)
            }
            val goodRejections = payload {
                varInt(1)
                string("a")
                string("b")
                varInt(3)
            }

            val result = LearnedDataFile.decode(file(Triple(1, 1, badWord), Triple(2, 1, goodRejections)))

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
            assertThat(result.data.personal.size).isZero()
            assertThat(result.data.rejections.rejections("a", "b")).isEqualTo(3)
        }

        @Test
        fun `des plafonds dépassés dans une section sont refusés avant toute allocation`() {
            val tooMany = payload { varInt(Int.MAX_VALUE) }
            val config = LearningConfig()

            val personal = LearnedDataFile.decode(file(Triple(1, 1, tooMany)), config)
            val rejections = LearnedDataFile.decode(file(Triple(2, 1, tooMany)), config)

            assertThat(personal.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
            assertThat(rejections.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
        }

        @Test
        fun `une section dont la charge a des octets en trop est invalide`() {
            val result = LearnedDataFile.decode(file(Triple(2, 1, emptyRejections + byteArrayOf(0))))

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
        }

        @Test
        fun `un modèle spatial aux valeurs absurdes est refusé`() {
            val bad = payload {
                repeat(26) {
                    varInt(1)
                    double(Double.NaN)
                    double(0.0)
                    double(0.0)
                    double(0.0)
                }
            }

            val result = LearnedDataFile.decode(file(Triple(3, 1, bad)))

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
            assertThat(result.data.spatial.isEmpty).isTrue()
        }

        @Test
        fun `un flux illisible donne un état vide et un signalement`() {
            val failing = object : InputStream() {
                override fun read(): Int = throw IOException("disque")
            }

            val result = LearnedDataFile.read(failing)

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.UNREADABLE)
            assertThat(result.data.isEmpty).isTrue()
        }

        @Test
        fun `un fichier plus gros que le plafond est refusé`() {
            val config = LearningConfig(maxFileBytes = 100)

            val result = LearnedDataFile.read(ByteArrayInputStream(ByteArray(101)), config)

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.TOO_LARGE)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un fichier de exactement maxFileBytes est lu`() {
            val bytes = bytesOf(LearnedData())
            val config = LearningConfig(maxFileBytes = bytes.size)

            assertThat(LearnedDataFile.read(ByteArrayInputStream(bytes), config).isClean).isTrue()
            assertThat(LearnedDataFile.read(ByteArrayInputStream(bytes), LearningConfig(maxFileBytes = bytes.size - 1)).issues.map { it.kind })
                .containsExactly(LoadIssueKind.TOO_LARGE)
        }

        @Test
        fun `un dictionnaire au plafond s'écrit et se relit`() {
            val config = LearningConfig(maxWords = 20, maxPending = 5, maxRejections = 5)
            val data = LearnedData(config)
            for (i in 0 until 25) data.personal.importWord("mot" + 'a'.plus(i), "fr", 2)
            for (i in 0 until 8) data.rejections.recordRejection("t$i", "i$i")
            for (i in 0 until 8) data.personal.observe("att" + 'a'.plus(i), "en", true, settings)

            val result = LearnedDataFile.decode(bytesOf(data), config)

            assertThat(result.isClean).isTrue()
            assertThat(result.data.personal.size).isEqualTo(20)
            assertThat(result.data.personal.pendingSize).isEqualTo(5)
            assertThat(result.data.rejections.size).isEqualTo(5)
        }

        @Test
        fun `un fichier écrit avec des plafonds plus larges est refusé section par section par un lecteur plus strict`() {
            val data = LearnedData()
            for (i in 0 until 10) data.personal.importWord("mot" + 'a'.plus(i), "fr", 2)
            data.rejections.recordRejection("a", "b")

            val result = LearnedDataFile.decode(bytesOf(data), LearningConfig(maxWords = 5))

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.INVALID_SECTION)
            assertThat(result.data.personal.size).isZero()
            assertThat(result.data.rejections.size).isEqualTo(1)
        }

        @Test
        fun `le compteur de sections à zéro et sans section est un fichier valide mais vide`() {
            val result = LearnedDataFile.decode(file())

            assertThat(result.isClean).isTrue()
            assertThat(result.data.isEmpty).isTrue()
        }

        @Test
        fun `un en-tête de section tronqué est signalé`() {
            val bytes = file().also { }.copyOf(7 + 3)
            bytes[6] = 1 // une section annoncée, mais seulement 3 octets d'en-tête.

            val result = LearnedDataFile.decode(bytes)

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.TRUNCATED)
        }

        @Test
        fun `une longueur annoncée plus grande que le reste est signalée`() {
            val bytes = file(Triple(2, 1, emptyRejections))
            bytes[10] = 0x7F // octet de poids fort de la longueur (u32) : plusieurs Gio annoncés.

            val result = LearnedDataFile.decode(bytes)

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.TRUNCATED)
        }

        @Test
        fun `une section sans la place de sa somme de contrôle est signalée`() {
            val bytes = file(Triple(2, 1, emptyRejections))

            val result = LearnedDataFile.decode(bytes.copyOf(bytes.size - 2))

            assertThat(result.issues.map { it.kind }).containsExactly(LoadIssueKind.TRUNCATED)
        }
    }
}
