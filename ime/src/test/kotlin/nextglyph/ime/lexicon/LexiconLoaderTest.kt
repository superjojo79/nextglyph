package nextglyph.ime.lexicon

import nextglyph.engine.lexicon.Lexicon
import nextglyph.engine.lexicon.LexiconBuilder
import nextglyph.engine.lexicon.LexiconFormatException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.Executor

class LexiconLoaderTest {

    /** Exécuteur manuel : les tâches s'accumulent jusqu'à [runAll], pour maîtriser l'ordre des fils. */
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private val french: Lexicon = LexiconBuilder("fr").apply { add("bonjour", 10) }.build()
    private val english: Lexicon = LexiconBuilder("en").apply { add("hello", 10) }.build()

    private val background = ManualExecutor()
    private val main = ManualExecutor()
    private val opened = mutableListOf<String>()
    private val delivered = mutableListOf<Pair<String, Lexicon?>>()

    private fun loader(open: (String) -> Lexicon = { language ->
        opened += language
        if (language == "fr") french else english
    }) = LexiconLoader(open, background, main)

    private val record: (String, Lexicon?) -> Unit = { language, lexicon -> delivered += language to lexicon }

    @Nested
    inner class HappyPath {

        @Test
        fun `le chargement se fait en arrière-plan puis la livraison sur le fil principal`() {
            val loader = loader()

            loader.load("fr", record)
            assertThat(opened).isEmpty()
            assertThat(background.tasks).hasSize(1)

            background.runAll()
            assertThat(opened).containsExactly("fr")
            assertThat(delivered).isEmpty()

            main.runAll()
            assertThat(delivered).containsExactly("fr" to french)
        }

        @Test
        fun `une nouvelle langue remplace la précédente`() {
            val loader = loader()
            loader.load("fr", record)
            background.runAll()
            main.runAll()

            loader.load("en", record)
            background.runAll()
            main.runAll()

            assertThat(delivered).containsExactly("fr" to french, "en" to english)
        }

        @Test
        fun `la même langue n'est pas rechargée`() {
            val loader = loader()
            loader.load("fr", record)
            background.runAll()
            main.runAll()

            loader.load("fr", record)

            assertThat(background.tasks).isEmpty()
            assertThat(opened).containsExactly("fr")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un échec de chargement est livré comme absence de lexique`() {
            for (failure in listOf<Exception>(IOException("absent"), LexiconFormatException("corrompu"), IllegalStateException())) {
                delivered.clear()
                val loader = loader { throw failure }

                loader.load("fr", record)
                background.runAll()
                main.runAll()

                assertThat(delivered).containsExactly("fr" to null)
            }
        }

        @Test
        fun `un résultat périmé est ignoré`() {
            val loader = loader()
            loader.load("fr", record)
            loader.load("en", record)

            background.runAll()
            main.runAll()

            assertThat(delivered).containsExactly("en" to english)
        }

        @Test
        fun `un résultat annulé n'est pas livré`() {
            val loader = loader()
            loader.load("fr", record)

            loader.cancel()
            background.runAll()
            main.runAll()

            assertThat(delivered).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `après une annulation, la même langue peut être redemandée`() {
            val loader = loader()
            loader.load("fr", record)
            loader.cancel()

            loader.load("fr", record)
            background.runAll()
            main.runAll()

            assertThat(delivered).containsExactly("fr" to french)
        }

        @Test
        fun `annuler sans demande ne fait rien`() {
            val loader = loader()

            loader.cancel()

            assertThat(background.tasks).isEmpty()
            assertThat(main.tasks).isEmpty()
        }

        @Test
        fun `un échec n'empêche pas la demande suivante`() {
            var failing = true
            val loader = loader { language ->
                if (failing) throw IOException("absent")
                if (language == "fr") french else english
            }
            loader.load("fr", record)
            background.runAll()
            main.runAll()
            failing = false

            loader.load("en", record)
            background.runAll()
            main.runAll()

            assertThat(delivered).containsExactly("fr" to null, "en" to english)
        }
    }
}
