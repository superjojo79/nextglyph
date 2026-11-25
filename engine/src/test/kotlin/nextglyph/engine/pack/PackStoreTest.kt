package nextglyph.engine.pack

import nextglyph.engine.lexicon.LexiconFormat
import nextglyph.engine.ngram.NgramFormat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair

class PackStoreTest {

    @TempDir
    lateinit var dir: File

    private val key: KeyPair = PackFixtures.keyPair()
    private val root: File get() = File(dir, "packs")

    private fun store(limits: PackLimits = PackLimits(), keys: List<java.security.PublicKey> = listOf(key.public)) =
        PackStore(root, keys, limits)

    private fun install(store: PackStore, bytes: ByteArray) = store.install(ByteArrayInputStream(bytes))

    private fun reason(block: () -> Unit): PackException.Reason {
        val e = catchPack(block)
        return e.reason
    }

    private fun catchPack(block: () -> Unit): PackException {
        try {
            block()
        } catch (e: PackException) {
            return e
        }
        throw AssertionError("PackException attendue")
    }

    private fun versions(): List<String> = (root.list() ?: emptyArray()).sorted()

    @Nested
    inner class HappyPath {
        @Test
        fun `n'a pas de paquet avant la première installation`() {
            assertThat(store().current()).isNull()
        }

        @Test
        fun `installe un paquet signé et le retrouve`() {
            val store = store()

            val installed = install(store, PackFixtures.pack(202611241830, key))

            assertThat(installed.version).isEqualTo(202611241830)
            assertThat(installed.directory).isEqualTo(File(root, "202611241830"))
            assertThat(installed.file("fr.ngrm")).isFile()
            assertThat(File(root, "current").readText()).isEqualTo("202611241830")
            assertThat(store.current()).isEqualTo(installed)
            assertThat(PackStore(root, listOf(key.public)).current()?.version).isEqualTo(202611241830)
            assertThat(versions()).containsExactlyInAnyOrder("202611241830", "current")
        }

        @Test
        fun `remplace l'ancienne version par une plus récente et la supprime`() {
            val store = store()
            install(store, PackFixtures.pack(100, key))

            install(store, PackFixtures.pack(101, key))

            assertThat(store.current()?.version).isEqualTo(101)
            assertThat(versions()).containsExactlyInAnyOrder("101", "current")
        }

        @Test
        fun `supprime les dossiers de préparation orphelins`() {
            File(root, ".staging-vieux").mkdirs()
            File(root, ".staging-vieux/x").writeText("x")

            install(store(), PackFixtures.pack(100, key))

            assertThat(versions()).containsExactlyInAnyOrder("100", "current")
        }

        @Test
        fun `ne ferme pas le flux fourni`() {
            var closed = false
            val bytes = PackFixtures.pack(100, key)
            val input = object : java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun close() {
                    closed = true
                }
            }

            store().install(input)

            assertThat(closed).isFalse()
        }

        @Test
        fun `reset efface le pointeur et les dossiers`() {
            val store = store()
            install(store, PackFixtures.pack(100, key))
            File(root, ".staging-x").mkdirs()

            store.reset()

            assertThat(store.current()).isNull()
            assertThat(versions()).isEmpty()
        }

        @Test
        fun `reset accepte un nouveau paquet plus ancien`() {
            val store = store()
            install(store, PackFixtures.pack(200, key))
            store.reset()

            assertThat(install(store, PackFixtures.pack(100, key)).version).isEqualTo(100)
        }

        @Test
        fun `signale une mise à jour plus récente que le paquet installé`() {
            val store = store()
            install(store, PackFixtures.pack(100, key))
            val manifest = PackFixtures.manifest(101).serialize()

            val update = store.checkUpdate(manifest, PackSignature.sign(manifest, key.private))

            assertThat(update?.version).isEqualTo(101)
        }

        @Test
        fun `signale une mise à jour quand rien n'est installé`() {
            val manifest = PackFixtures.manifest(5).serialize()

            assertThat(store().checkUpdate(manifest, PackSignature.sign(manifest, key.private))?.version).isEqualTo(5)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `refuse un paquet signé par une clé inconnue`() {
            val store = store()

            assertThat(reason { install(store, PackFixtures.pack(100, PackFixtures.keyPair())) }).isEqualTo(PackException.Reason.SIGNATURE)
            assertThat(store.current()).isNull()
            assertThat(versions()).isEmpty()
        }

        @Test
        fun `refuse tout paquet quand aucune clé n'est de confiance`() {
            assertThat(reason { install(store(keys = emptyList()), PackFixtures.pack(100, key)) }).isEqualTo(PackException.Reason.SIGNATURE)
        }

        @Test
        fun `refuse un paquet sans signature`() {
            val files = PackFixtures.files()
            val manifest = PackFixtures.manifest(100).serialize()
            val zip = PackFixtures.zip(listOf(PackFormat.MANIFEST to manifest) + files.toList())

            assertThat(reason { install(store(), zip) }).isEqualTo(PackException.Reason.SIGNATURE)
        }

        @Test
        fun `refuse un paquet sans manifeste`() {
            val zip = PackFixtures.zip(listOf(PackFormat.SIGNATURE to byteArrayOf(1)))

            assertThat(reason { install(store(), zip) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un manifeste signé mais mal formé`() {
            val manifest = "n'importe quoi\n".toByteArray()
            val zip = PackFixtures.zip(
                listOf(PackFormat.MANIFEST to manifest, PackFormat.SIGNATURE to PackSignature.sign(manifest, key.private)),
            )

            assertThat(reason { install(store(), zip) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse des formats binaires différents`() {
            assertThat(reason { install(store(), PackFixtures.pack(100, key, lexiconFormat = LexiconFormat.VERSION + 1)) })
                .isEqualTo(PackException.Reason.INCOMPATIBLE)
            assertThat(reason { install(store(), PackFixtures.pack(100, key, ngramFormat = NgramFormat.VERSION + 1)) })
                .isEqualTo(PackException.Reason.INCOMPATIBLE)
        }

        @Test
        fun `refuse un paquet plus ancien et garde l'état précédent`() {
            val store = store()
            val installed = install(store, PackFixtures.pack(200, key))

            assertThat(reason { install(store, PackFixtures.pack(100, key)) }).isEqualTo(PackException.Reason.OUTDATED)
            assertThat(store.current()).isEqualTo(installed)
            assertThat(versions()).containsExactlyInAnyOrder("200", "current")
        }

        @Test
        fun `refuse un paquet de la même version`() {
            val store = store()
            install(store, PackFixtures.pack(200, key))

            assertThat(reason { install(store, PackFixtures.pack(200, key)) }).isEqualTo(PackException.Reason.OUTDATED)
        }

        @Test
        fun `refuse un fichier modifié après la signature`() {
            val files = PackFixtures.files()
            val tampered = files + ("fr.ngrm" to files.getValue("fr.ngrm").also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

            assertThat(reason { install(store(), PackFixtures.pack(100, key, files = tampered, declared = files)) })
                .isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un fichier de taille différente du manifeste`() {
            val files = PackFixtures.files()

            assertThat(reason { install(store(), PackFixtures.pack(100, key, files = files + (PackFormat.NOTICE to byteArrayOf(1)), declared = files)) })
                .isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un fichier listé mais absent de l'archive`() {
            val files = PackFixtures.files()

            assertThat(reason { install(store(), PackFixtures.pack(100, key, files = files - "en.nglx", declared = files)) })
                .isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un fichier de données qui ne se charge pas même avec la bonne empreinte`() {
            val files = PackFixtures.files() + ("en.nglx" to ByteArray(40) { 7 })

            val e = catchPack { install(store(), PackFixtures.pack(100, key, files = files)) }

            assertThat(e.reason).isEqualTo(PackException.Reason.CORRUPT)
            assertThat(e.message).contains("en.nglx")
            assertThat(versions()).isEmpty()
        }

        @Test
        fun `refuse un modèle de mot suivant qui ne se charge pas`() {
            val files = PackFixtures.files() + ("en.ngrm" to ByteArray(40) { 7 })

            assertThat(reason { install(store(), PackFixtures.pack(100, key, files = files)) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un fichier vide`() {
            val files = PackFixtures.files() + ("en.ngrm" to ByteArray(0))

            assertThat(reason { install(store(), PackFixtures.pack(100, key, files = files)) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un nom d'entrée hors liste`() {
            for (name in listOf("../evil", "dossier/en.nglx", "autre.bin", "..\\evil")) {
                val zip = PackFixtures.pack(100, key, extra = listOf(name to byteArrayOf(1)))

                assertThat(reason { install(store(), zip) }).isEqualTo(PackException.Reason.CORRUPT)
            }
            assertThat(versions()).isEmpty()
        }

        @Test
        fun `refuse un dossier dans l'archive`() {
            val zip = PackFixtures.pack(100, key, extra = listOf("dossier/" to ByteArray(0)))

            assertThat(reason { install(store(), zip) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse une entrée en double`() {
            // ZipOutputStream refuse les doublons : on renomme une entrée dans l'archive (même longueur de nom).
            val zip = PackFixtures.pack(100, key)
            val doubled = String(zip, Charsets.ISO_8859_1).replace("fr.ngrm", "en.ngrm").toByteArray(Charsets.ISO_8859_1)

            assertThat(reason { install(store(), doubled) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un flux qui n'est pas un ZIP`() {
            assertThat(reason { install(store(), "pas un zip".toByteArray()) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse une archive tronquée`() {
            val bytes = PackFixtures.pack(100, key)

            assertThat(reason { install(store(), bytes.copyOf(bytes.size / 2)) }).isEqualTo(PackException.Reason.CORRUPT)
            assertThat(versions()).isEmpty()
        }

        @Test
        fun `refuse une archive vide`() {
            assertThat(reason { install(store(), ByteArray(0)) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `refuse un manifeste trop long`() {
            val big = ByteArray(PackFormat.MAX_MANIFEST_BYTES.toInt() + 1) { 'a'.code.toByte() }
            val zip = PackFixtures.zip(listOf(PackFormat.MANIFEST to big, PackFormat.SIGNATURE to PackSignature.sign(big, key.private)))

            assertThat(reason { install(store(), zip) }).isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `signale une erreur disque en IO et retire le dossier installé`() {
            File(root, "current/occupe").mkdirs()
            File(root, "current/occupe/x").writeText("x")
            val store = store()

            val e = catchPack { install(store, PackFixtures.pack(100, key)) }

            assertThat(e.reason).isEqualTo(PackException.Reason.IO)
            assertThat(versions()).containsExactly("current")
            assertThat(store.current()).isNull()
        }

        @Test
        fun `signale en IO un dossier racine impossible à créer`() {
            val blocker = File(dir, "fichier").apply { writeText("x") }

            assertThat(reason { PackStore(File(blocker, "sous"), listOf(key.public)).install(ByteArrayInputStream(PackFixtures.pack(1, key))) })
                .isEqualTo(PackException.Reason.IO)
        }

        @Test
        fun `retombe sur null si un fichier installé a changé de taille`() {
            val store = store()
            val installed = install(store, PackFixtures.pack(100, key))
            installed.file("en.nglx").appendBytes(byteArrayOf(0))

            assertThat(store.current()).isNull()
        }

        @Test
        fun `retombe sur null si un fichier installé a disparu`() {
            val store = store()
            install(store, PackFixtures.pack(100, key)).file("NOTICE.txt").delete()

            assertThat(store.current()).isNull()
        }

        @Test
        fun `retombe sur null si le pointeur est invalide`() {
            val store = store()
            install(store, PackFixtures.pack(100, key))

            for (content in listOf("", "abc", "99", "100x", "-1")) {
                File(root, "current").writeText(content)

                assertThat(store.current()).describedAs(content).isNull()
            }
        }

        @Test
        fun `retombe sur null si le manifeste installé est de la mauvaise version`() {
            val store = store()
            install(store, PackFixtures.pack(100, key))
            File(root, "current").writeText("101")
            File(root, "100").renameTo(File(root, "101"))

            assertThat(store.current()).isNull()
        }

        @Test
        fun `retombe sur null si le manifeste installé est incompatible`() {
            val store = store()
            val installed = install(store, PackFixtures.pack(100, key))
            val files = PackFixtures.files()
            installed.file("manifest.txt").writeBytes(PackFixtures.manifest(100, files, lexiconFormat = 9).serialize())

            assertThat(store.current()).isNull()
        }

        @Test
        fun `refuse une mise à jour signée par une clé inconnue`() {
            val manifest = PackFixtures.manifest(5).serialize()

            assertThat(reason { store().checkUpdate(manifest, PackSignature.sign(manifest, PackFixtures.keyPair().private)) })
                .isEqualTo(PackException.Reason.SIGNATURE)
        }

        @Test
        fun `refuse une mise à jour incompatible`() {
            val manifest = PackFixtures.manifest(5, ngramFormat = 99).serialize()

            assertThat(reason { store().checkUpdate(manifest, PackSignature.sign(manifest, key.private)) })
                .isEqualTo(PackException.Reason.INCOMPATIBLE)
        }

        @Test
        fun `refuse une mise à jour dont le manifeste est mal formé`() {
            val manifest = "x".toByteArray()

            assertThat(reason { store().checkUpdate(manifest, PackSignature.sign(manifest, key.private)) })
                .isEqualTo(PackException.Reason.CORRUPT)
        }

        @Test
        fun `ne signale pas de mise à jour pour une version égale ou plus ancienne`() {
            val store = store()
            install(store, PackFixtures.pack(100, key))

            for (version in listOf(100L, 99L)) {
                val manifest = PackFixtures.manifest(version).serialize()

                assertThat(store.checkUpdate(manifest, PackSignature.sign(manifest, key.private))).describedAs("$version").isNull()
            }
        }
    }

    @Nested
    inner class BoundaryValues {
        private val archive = PackFixtures.pack(100, key)
        private val sizes = PackFixtures.sizes(archive)

        @Test
        fun `accepte une entrée de exactement la taille maximale`() {
            val limits = PackLimits(maxEntryBytes = sizes.max(), maxTotalBytes = Long.MAX_VALUE)

            assertThat(install(store(limits), archive).version).isEqualTo(100)
        }

        @Test
        fun `refuse une entrée d'un octet au-delà de la taille maximale`() {
            val limits = PackLimits(maxEntryBytes = sizes.max() - 1, maxTotalBytes = Long.MAX_VALUE)

            assertThat(reason { install(store(limits), archive) }).isEqualTo(PackException.Reason.TOO_LARGE)
            assertThat(versions()).isEmpty()
        }

        @Test
        fun `accepte un total de exactement la taille maximale`() {
            val limits = PackLimits(maxTotalBytes = sizes.sum())

            assertThat(install(store(limits), archive).version).isEqualTo(100)
        }

        @Test
        fun `refuse un total d'un octet au-delà de la taille maximale`() {
            val limits = PackLimits(maxTotalBytes = sizes.sum() - 1)

            assertThat(reason { install(store(limits), archive) }).isEqualTo(PackException.Reason.TOO_LARGE)
        }

        @Test
        fun `accepte exactement le nombre maximal d'entrées`() {
            assertThat(install(store(PackLimits(maxEntries = 8)), PackFixtures.pack(100, key)).version).isEqualTo(100)
        }

        @Test
        fun `refuse une entrée de plus que le nombre maximal`() {
            assertThat(reason { install(store(PackLimits(maxEntries = 7)), PackFixtures.pack(100, key)) })
                .isEqualTo(PackException.Reason.TOO_LARGE)
        }

        @Test
        fun `les plafonds par défaut sont ceux du format`() {
            val limits = PackLimits()

            assertThat(limits.maxEntryBytes).isEqualTo(PackFormat.MAX_ENTRY_BYTES)
            assertThat(limits.maxTotalBytes).isEqualTo(PackFormat.MAX_TOTAL_BYTES)
            assertThat(limits.maxEntries).isEqualTo(PackFormat.MAX_ENTRIES)
        }

        @Test
        fun `reset sans rien d'installé ni dossier racine ne lève pas`() {
            store().reset()

            assertThat(root).doesNotExist()
        }

        @Test
        fun `reset laisse les fichiers étrangers`() {
            root.mkdirs()
            File(root, "autre.txt").writeText("x")
            File(root, "journal").mkdirs()

            store().reset()

            assertThat(versions()).containsExactlyInAnyOrder("autre.txt", "journal")
        }

        @Test
        fun `accepte un très grand numéro de version`() {
            assertThat(install(store(), PackFixtures.pack(999_999_999_999_999_999L, key)).version).isEqualTo(999_999_999_999_999_999L)
        }

        @Test
        fun `deux installations successives de versions consécutives`() {
            val store = store()
            install(store, PackFixtures.pack(1, key))

            assertThat(install(store, PackFixtures.pack(2, key)).version).isEqualTo(2)
        }

        @Test
        fun `un paquet n'expose que ses fichiers`() {
            val installed = install(store(), PackFixtures.pack(1, key))

            assertThat(installed.directory.list()!!.toList())
                .containsExactlyInAnyOrderElementsOf(PackFormat.PACKED_FILES + PackFormat.MANIFEST + PackFormat.SIGNATURE)
            assertThatThrownBy { throw PackException(PackException.Reason.IO, "x") }.isInstanceOf(Exception::class.java)
        }
    }
}
