package nextglyph.ime.learn

import nextglyph.engine.learn.ByteCodec
import nextglyph.engine.learn.ImportReport
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.learn.LearnedDataFile
import nextglyph.engine.learn.LearningConfig
import nextglyph.engine.learn.LoadIssue
import nextglyph.engine.learn.LoadResult
import nextglyph.engine.learn.PersonalDictionaryTsv
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor

/**
 * État appris du clavier (spec §10.5, §12) partagé par le service et l'activité de réglages (même
 * processus) : chargement, enregistrement, export, import et effacement.
 *
 * **Fils.** [data] n'est lu et modifié que sur le fil de l'interface ([main]) ; les entrées-sorties ont
 * lieu sur [io]. L'enregistrement sérialise l'état en mémoire sur le fil de l'interface (quelques
 * dizaines de kilo-octets au plus), puis l'écrit en arrière-plan de façon atomique ([AtomicFiles]) : la
 * frappe n'attend jamais le disque. Les écritures passent par un seul fil, dans l'ordre.
 *
 * **Chargement tolérant.** Un fichier absent donne un état vide ; un fichier abîmé, ce qui a pu en être
 * lu ([LoadResult], problèmes dans [issues], sans texte de l'utilisateur).
 *
 * @param file fichier d'état, dans le stockage privé non sauvegardé (`noBackupFilesDir`).
 */
class LearnedStore(
    private val file: File,
    private val io: Executor,
    private val main: Executor,
    private val config: LearningConfig = LearningConfig(),
) {
    /** Prévenu sur le fil de l'interface quand l'état change hors de la frappe (chargement, import, effacement). */
    fun interface Listener {
        fun onLearnedDataChanged(data: LearnedData)
    }

    /** État chargé, ou `null` tant que le chargement n'est pas terminé. */
    var data: LearnedData? = null
        private set

    /** Problèmes rencontrés au chargement (vide si le fichier était sain ou absent). */
    var issues: List<LoadIssue> = emptyList()
        private set

    /** Vrai si la dernière écriture a échoué (disque plein, par exemple). */
    @Volatile
    var lastWriteFailed: Boolean = false
        private set

    private val listeners = LinkedHashSet<Listener>()
    private var loading = false
    private var savedRevision = Long.MIN_VALUE

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Charge l'état en arrière-plan (une seule fois) ; [onLoaded] est appelé sur le fil de l'interface. */
    fun load(onLoaded: (LearnedData) -> Unit = {}) {
        data?.let {
            onLoaded(it)
            return
        }
        if (loading) {
            addListener(OneShot(onLoaded))
            return
        }
        loading = true
        addListener(OneShot(onLoaded))
        io.execute {
            val result = read()
            main.execute {
                loading = false
                data = result.data
                issues = result.issues
                savedRevision = result.data.revision
                notifyChanged(result.data)
            }
        }
    }

    /** Enregistre l'état s'il a changé depuis la dernière écriture. Faux s'il n'y avait rien à écrire. */
    fun saveIfChanged(): Boolean {
        val data = data ?: return false
        if (data.revision == savedRevision) return false
        save(data)
        return true
    }

    /** Efface toutes les données apprises (§12) : état en mémoire et fichier. */
    fun clear() {
        val data = data ?: return
        data.clear()
        savedRevision = data.revision
        io.execute {
            lastWriteFailed = !AtomicFiles.delete(file)
        }
        notifyChanged(data)
    }

    /**
     * Exporte le dictionnaire personnel en TSV dans le flux ouvert par [open] (§14) ; [done] reçoit vrai si
     * tout a été écrit. Le flux est fermé.
     */
    fun exportTsv(open: () -> OutputStream?, done: (Boolean) -> Unit) {
        val data = data ?: return done(false)
        val bytes = ByteArrayOutputStream().also { PersonalDictionaryTsv.export(data.personal, it) }.toByteArray()
        io.execute {
            val ok = try {
                open()?.use { it.write(bytes) } != null
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            main.execute { done(ok) }
        }
    }

    /**
     * Importe en TSV le flux ouvert par [open] dans le dictionnaire personnel ; [done] reçoit le compte rendu
     * (ou `null` si le flux n'a pas pu être ouvert). L'état est ensuite enregistré.
     */
    fun importTsv(open: () -> InputStream?, done: (ImportReport?) -> Unit) {
        if (data == null) return done(null)
        io.execute {
            val bytes = try {
                open()?.use { ByteCodec.readAtMost(it, config.maxImportBytes + 1) }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            main.execute {
                val data = data
                if (bytes == null || data == null) {
                    done(null)
                } else {
                    val report = PersonalDictionaryTsv.import(ByteArrayInputStream(bytes), data.personal)
                    saveIfChanged()
                    notifyChanged(data)
                    done(report)
                }
            }
        }
    }

    private fun save(data: LearnedData) {
        val bytes = ByteArrayOutputStream().also { LearnedDataFile.write(data, it) }.toByteArray()
        savedRevision = data.revision
        io.execute {
            lastWriteFailed = try {
                AtomicFiles.write(file, bytes)
                false
            } catch (e: IOException) {
                true
            }
        }
    }

    private fun read(): LoadResult {
        // Écriture interrompue : le fichier temporaire est abandonné, la cible est intacte.
        AtomicFiles.tempFor(file).delete()
        if (!file.isFile) return LoadResult(LearnedData(config), emptyList())
        return try {
            FileInputStream(file).use { LearnedDataFile.read(it, config) }
        } catch (e: IOException) {
            LearnedDataFile.decode(ByteArray(0), config)
        }
    }

    private fun notifyChanged(data: LearnedData) {
        for (listener in listeners.toList()) {
            listener.onLearnedDataChanged(data)
            if (listener is OneShot) listeners -= listener
        }
    }

    private class OneShot(val block: (LearnedData) -> Unit) : Listener {
        override fun onLearnedDataChanged(data: LearnedData) = block(data)
    }
}
