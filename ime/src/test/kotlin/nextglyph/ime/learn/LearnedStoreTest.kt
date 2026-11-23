package nextglyph.ime.learn

import nextglyph.engine.learn.ImportReport
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.learn.LearnedDataFile
import nextglyph.engine.learn.LoadIssueKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor

/** Stockage de l'état appris et écriture atomique (§10.5, §12), avec des exécuteurs immédiats. */
class LearnedStoreTest {

    @TempDir
    lateinit var dir: File

    private val now = Executor { it.run() }

    /** Exécuteur qui garde les tâches jusqu'à [drain] : vérifie que rien n'est écrit sur le fil appelant. */
    private class Deferred : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private val file get() = File(dir, "learned.ngls")

    private fun store(io: Executor = now) = LearnedStore(file, io, now)

    private fun loaded(io: Executor = now): Pair<LearnedStore, LearnedData> {
        val store = store(io)
        var data: LearnedData? = null
        store.load { data = it }
        (io as? Deferred)?.drain()
        return store to data!!
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `fichier absent - état vide, sans problème`() {
            val (store, data) = loaded()

            assertThat(data.isEmpty).isTrue()
            assertThat(store.issues).isEmpty()
            assertThat(store.data).isSameAs(data)
        }

        @Test
        fun `enregistré puis relu`() {
            val (store, data) = loaded()
            data.personal.importWord("zorglub", "fr", 2)

            assertThat(store.saveIfChanged()).isTrue()
            assertThat(store.saveIfChanged()).isFalse()

            val (_, reread) = loaded()
            assertThat(reread.personal.contains("zorglub", "fr")).isTrue()
            assertThat(AtomicFiles.tempFor(file)).doesNotExist()
        }

        @Test
        fun `l'écriture a lieu sur le fil d'entrées-sorties`() {
            val io = Deferred()
            val (store, data) = loaded(io)
            data.personal.importWord("zorglub", "fr", 2)

            store.saveIfChanged()
            assertThat(file).doesNotExist()
            io.drain()

            assertThat(file).exists()
        }

        @Test
        fun `effacer vide l'état, supprime le fichier et prévient`() {
            val (store, data) = loaded()
            data.personal.importWord("zorglub", "fr", 2)
            store.saveIfChanged()
            var notified = 0
            store.addListener { notified++ }

            store.clear()

            assertThat(data.isEmpty).isTrue()
            assertThat(file).doesNotExist()
            assertThat(notified).isEqualTo(1)
            assertThat(store.saveIfChanged()).isFalse()
        }

        @Test
        fun `retirer un refus l'enlève de la liste, enregistre et prévient`() {
            val (store, data) = loaded()
            repeat(2) { data.rejections.recordRejection("zorglub", "zorglube") }
            store.saveIfChanged()
            var notified = 0
            store.addListener { notified++ }

            val forgotten = store.forgetRefusal("Zorglub", "zorglube")

            assertThat(forgotten).isTrue()
            assertThat(data.rejections.rejections("zorglub", "zorglube")).isZero()
            assertThat(notified).isEqualTo(1)
            assertThat(store.saveIfChanged()).isFalse()
        }

        @Test
        fun `export puis import en TSV`() {
            val (store, data) = loaded()
            data.personal.importWord("zorglub", "fr", 3)
            val out = ByteArrayOutputStream()
            var exported = false

            store.exportTsv({ out }) { exported = it }
            data.clear()
            var report: ImportReport? = null
            store.importTsv({ ByteArrayInputStream(out.toByteArray()) }) { report = it }

            assertThat(exported).isTrue()
            assertThat(report!!.imported).isEqualTo(1)
            assertThat(data.personal.contains("zorglub", "fr")).isTrue()
            assertThat(file).exists()
        }

        @Test
        fun `un auditeur retiré n'est plus prévenu`() {
            val (store, _) = loaded()
            var notified = 0
            val listener = LearnedStore.Listener { notified++ }
            store.addListener(listener)
            store.removeListener(listener)

            store.clear()

            assertThat(notified).isZero()
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `fichier corrompu - état vide et problème signalé`() {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))

            val (store, data) = loaded()

            assertThat(data.isEmpty).isTrue()
            assertThat(store.issues.map { it.kind }).containsExactly(LoadIssueKind.BAD_MAGIC)
        }

        @Test
        fun `fichier temporaire abandonné - ignoré et supprimé`() {
            val good = ByteArrayOutputStream().also { LearnedDataFile.write(LearnedData().apply { personal.importWord("zorglub", "fr", 2) }, it) }
            file.writeBytes(good.toByteArray())
            AtomicFiles.tempFor(file).writeBytes(byteArrayOf(9, 9))

            val (_, data) = loaded()

            assertThat(data.personal.contains("zorglub", "fr")).isTrue()
            assertThat(AtomicFiles.tempFor(file)).doesNotExist()
        }

        @Test
        fun `export impossible - échec signalé`() {
            val (store, _) = loaded()
            var result: Boolean? = null

            store.exportTsv({ object : OutputStream() { override fun write(b: Int) = throw IOException("plein") } }) { result = it }
            assertThat(result).isFalse()
            store.exportTsv({ null }) { result = it }
            assertThat(result).isFalse()
            store.exportTsv({ throw SecurityException() }) { result = it }
            assertThat(result).isFalse()
        }

        @Test
        fun `import illisible - compte rendu nul`() {
            val (store, _) = loaded()
            val reports = mutableListOf<ImportReport?>()

            store.importTsv({ null }) { reports += it }
            store.importTsv({ throw IOException() }) { reports += it }
            store.importTsv({ throw SecurityException() }) { reports += it }

            assertThat(reports).containsExactly(null, null, null)
        }

        @Test
        fun `avant chargement, rien n'est fait`() {
            val store = store()
            var exported: Boolean? = null
            var imported: ImportReport? = ImportReport(1, emptyList(), 0, false, false, false)

            store.clear()
            val forgotten = store.forgetRefusal("zorglub", "zorglube")
            store.exportTsv({ ByteArrayOutputStream() }) { exported = it }
            store.importTsv({ ByteArrayInputStream(ByteArray(0)) }) { imported = it }

            assertThat(store.saveIfChanged()).isFalse()
            assertThat(forgotten).isFalse()
            assertThat(exported).isFalse()
            assertThat(imported).isNull()
        }

        @Test
        fun `retirer un refus inconnu ne change rien et ne prévient pas`() {
            val (store, _) = loaded()
            var notified = 0
            store.addListener { notified++ }

            assertThat(store.forgetRefusal("zorglub", "zorglube")).isFalse()

            assertThat(notified).isZero()
        }

        @Test
        fun `écriture impossible - signalée, cible intacte`() {
            val blocker = File(dir, "bloc")
            blocker.writeText("x")
            val store = LearnedStore(File(blocker, "learned.ngls"), now, now)
            var data: LearnedData? = null
            store.load { data = it }
            data!!.personal.importWord("zorglub", "fr", 2)

            store.saveIfChanged()

            assertThat(store.lastWriteFailed).isTrue()
            assertThatThrownBy { AtomicFiles.write(File(blocker, "f"), ByteArray(1)) }.isInstanceOf(IOException::class.java)
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `deux chargements simultanés - une seule lecture, deux rappels`() {
            val io = Deferred()
            val store = store(io)
            val seen = mutableListOf<LearnedData>()

            store.load { seen += it }
            store.load { seen += it }
            assertThat(io.tasks).hasSize(1)
            io.drain()
            store.load { seen += it }

            assertThat(seen).hasSize(3)
            assertThat(seen.distinct()).hasSize(1)
        }

        @Test
        fun `écriture atomique remplace un fichier existant`() {
            file.writeBytes(byteArrayOf(1))

            AtomicFiles.write(file, byteArrayOf(2, 3))

            assertThat(file.readBytes()).containsExactly(2, 3)
            assertThat(AtomicFiles.delete(file)).isTrue()
            assertThat(AtomicFiles.delete(file)).isTrue()
        }

        @Test
        fun `écriture atomique crée le dossier manquant`() {
            val nested = File(dir, "a/b/c.bin")

            AtomicFiles.write(nested, ByteArray(0))

            assertThat(nested).exists().isEmpty()
        }
    }
}
