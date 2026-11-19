package nextglyph.engine.learn

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.PredictFixtures
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.SpaceCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersonalDictionaryTest {

    private val settings = PredictionSettings()
    private val dictionary = PersonalDictionary()

    private fun PersonalDictionary.see(word: String, language: String = "fr", unknown: Boolean = true, s: PredictionSettings = settings) =
        observe(word, language, unknown, s)

    private fun base(): Map<String, LexiconSet> = mapOf("fr" to PredictFixtures.french(), "en" to PredictFixtures.english())

    @Nested
    inner class HappyPath {

        @Test
        fun `une copie garde mots promus, mots en attente et ordre, et reste indépendante`() {
            dictionary.importWord("zorglub", "fr", 3)
            dictionary.importWord("Bidule", "en", 1)
            dictionary.see("machin")

            val copy = dictionary.copy()
            dictionary.remove("zorglub", "fr")

            assertThat(copy.words().map { it.form }).containsExactly("Bidule", "zorglub")
            assertThat(copy.pendingWords().map { it.form }).containsExactly("machin")
            assertThat(copy.config).isSameAs(dictionary.config)
            assertThat(copy.sets(base())).isNotSameAs(base())
        }

        @Test
        fun `chaque modification fait avancer la révision`() {
            val seen = ArrayList<Long>()
            fun step(action: () -> Unit) {
                val before = dictionary.revision
                action()
                seen += dictionary.revision - before
            }

            step { dictionary.see("zorglub") }
            step { dictionary.see("zorglub") }
            step { dictionary.see("zorglub", unknown = false) }
            step { dictionary.see("blorp") }
            step { dictionary.discard("blorp", "fr") }
            step { dictionary.importWord("quux", "fr", 2) }
            step { dictionary.remove("quux", "fr") }
            step { dictionary.clear() }

            assertThat(seen).hasSize(8).allMatch { it > 0 }
        }

        @Test
        fun `une opération sans effet ne change pas la révision`() {
            val before = dictionary.revision

            dictionary.discard("zorglub", "fr")
            dictionary.remove("zorglub", "fr")
            dictionary.see("a")
            dictionary.see("bonjour", unknown = false)

            assertThat(dictionary.revision).isEqualTo(before)
        }

        @Test
        fun `la restauration remplace tout le contenu`() {
            repeat(2) { dictionary.see("zorglub") }
            dictionary.see("blorp")
            val before = dictionary.revision
            val beforeLexicon = dictionary.lexiconRevision

            dictionary.restore(listOf(StoredWord("en", listOf("quux" to 3))), listOf(StoredWord("en", listOf("flop" to 1))))

            assertThat(dictionary.words()).containsExactly(PersonalWord("en", "quux", 3))
            assertThat(dictionary.pendingWords()).containsExactly(PersonalWord("en", "flop", 1))
            assertThat(dictionary.revision).isGreaterThan(before)
            assertThat(dictionary.lexiconRevision).isGreaterThan(beforeLexicon)
        }

        @Test
        fun `un mot appris d'une langue n'est pas du dictionnaire de l'autre`() {
            repeat(2) { dictionary.see("zorglub", "en") }

            assertThat(dictionary.contains("zorglub", "en")).isTrue()
            assertThat(dictionary.contains("zorglub", "fr")).isFalse()
            assertThat(dictionary.remove("zorglub", "fr")).isFalse()
            assertThat(dictionary.size).isEqualTo(1)
        }

        @Test
        fun `un mot inconnu est mis en attente puis promu à la deuxième validation`() {
            assertThat(dictionary.see("Zorglub")).isEqualTo(LearnOutcome.PENDING)
            assertThat(dictionary.size).isZero()
            assertThat(dictionary.pendingWords()).containsExactly(PersonalWord("fr", "Zorglub", 1))

            assertThat(dictionary.see("Zorglub")).isEqualTo(LearnOutcome.PROMOTED)

            assertThat(dictionary.size).isEqualTo(1)
            assertThat(dictionary.pendingSize).isZero()
            assertThat(dictionary.words()).containsExactly(PersonalWord("fr", "Zorglub", 2))
            assertThat(dictionary.contains("zorglub", "fr")).isTrue()
        }

        @Test
        fun `le seuil de promotion est configurable`() {
            val third = PersonalDictionary(LearningConfig(minOccurrences = 3))

            assertThat(third.see("zorglub")).isEqualTo(LearnOutcome.PENDING)
            assertThat(third.see("zorglub")).isEqualTo(LearnOutcome.PENDING)
            assertThat(third.see("zorglub")).isEqualTo(LearnOutcome.PROMOTED)
        }

        @Test
        fun `un mot promu voit son compteur augmenter à chaque validation`() {
            dictionary.see("zorglub")
            dictionary.see("zorglub")

            assertThat(dictionary.see("zorglub", unknown = false)).isEqualTo(LearnOutcome.USED)

            assertThat(dictionary.words().single().count).isEqualTo(3)
        }

        @Test
        fun `un mot connu des lexiques n'est pas appris`() {
            assertThat(dictionary.see("bonjour", unknown = false)).isEqualTo(LearnOutcome.IGNORED)

            assertThat(dictionary.pendingSize).isZero()
            assertThat(dictionary.size).isZero()
        }

        @Test
        fun `le mot est rattaché à la langue estimée`() {
            dictionary.see("zorglub", "en")
            dictionary.see("zorglub", "en")

            assertThat(dictionary.words("en")).hasSize(1)
            assertThat(dictionary.words("fr")).isEmpty()
            assertThat(dictionary.contains("zorglub", "fr")).isFalse()
            val sets = dictionary.sets(base())
            assertThat(sets.getValue("en").containsKey("zorglub")).isTrue()
            assertThat(sets.getValue("fr").containsKey("zorglub")).isFalse()
        }

        @Test
        fun `un mot de la même clé dans deux langues reste distinct`() {
            repeat(2) { dictionary.see("zorglub", "fr") }
            dictionary.see("zorglub", "en")

            assertThat(dictionary.words()).containsExactly(PersonalWord("fr", "zorglub", 2))
            assertThat(dictionary.pendingWords()).containsExactly(PersonalWord("en", "zorglub", 1))
        }

        @Test
        fun `annuler une validation oublie le mot en attente`() {
            dictionary.see("zorglub")

            assertThat(dictionary.discard("zorglub", "fr")).isTrue()

            assertThat(dictionary.pendingSize).isZero()
            assertThat(dictionary.see("zorglub")).isEqualTo(LearnOutcome.PENDING)
        }

        @Test
        fun `l'utilisateur supprime un mot promu`() {
            repeat(2) { dictionary.see("zorglub") }
            val before = dictionary.lexiconRevision

            assertThat(dictionary.remove("Zorglub", "fr")).isTrue()

            assertThat(dictionary.size).isZero()
            assertThat(dictionary.lexiconRevision).isGreaterThan(before)
            val base = base()
            assertThat(dictionary.sets(base)).isSameAs(base)
        }

        @Test
        fun `la suppression retire aussi un mot en attente`() {
            dictionary.see("zorglub")

            assertThat(dictionary.remove("zorglub", "fr")).isTrue()

            assertThat(dictionary.pendingSize).isZero()
        }

        @Test
        fun `la forme la plus utilisée est affichée`() {
            val third = PersonalDictionary(LearningConfig(minOccurrences = 3))
            third.see("Zorglub")
            third.see("zorglub")
            third.see("zorglub")

            assertThat(third.words().single().form).isEqualTo("zorglub")
        }

        @Test
        fun `en début de phrase, une simple initiale majuscule est ramenée en minuscules`() {
            dictionary.observe("Zorglub", "fr", true, settings, atSentenceStart = true)
            dictionary.observe("Zorglub", "fr", true, settings, atSentenceStart = true)

            assertThat(dictionary.words().single().form).isEqualTo("zorglub")
        }

        @Test
        fun `en début de phrase, une majuscule interne ou un mot en capitales est conservé`() {
            dictionary.observe("McZorg", "fr", true, settings, atSentenceStart = true)
            dictionary.observe("NASA", "fr", true, settings, atSentenceStart = true)

            assertThat(dictionary.pendingWords().map { it.form }).containsExactlyInAnyOrder("McZorg", "NASA")
        }

        @Test
        fun `les apostrophes typographiques sont normalisées`() {
            dictionary.see("aujourd’hui-x")

            assertThat(dictionary.pendingWords().single().form).isEqualTo("aujourd'hui-x")
        }

        @Test
        fun `les mots appris complètent la prédiction`() {
            repeat(2) { dictionary.see("zorglub") }
            val predictor = Predictor(dictionary.sets(base()), KeyboardLayouts.AZERTY)
            val state = predictor.initialLanguageState("fr")

            val typedWhole = predictor.predict("zorglub", state, settings)
            val completion = predictor.predict("zorg", state, settings)

            assertThat(typedWhole.case).isEqualTo(SpaceCase.WORD)
            assertThat(completion.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(completion.inserted).isEqualTo("zorglub")
        }

        @Test
        fun `la fréquence d'un mot appris est une part de la masse du lexique`() {
            val big = mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("le" to 1_000_000_000L), "fr")))
            repeat(2) { dictionary.see("zorglub") }

            val set = dictionary.sets(big).getValue("fr")

            // 1e9 × 1e-5 × (1 + ln 2) = 16 931.
            assertThat(set.displayForms("zorglub").single().frequency).isCloseTo(16_931L, Offset.offset(1L))
        }

        @Test
        fun `le facteur d'usage est plafonné`() {
            val big = mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("le" to 1_000_000_000L), "fr")))
            repeat(2) { dictionary.see("zorglub") }
            repeat(500) { dictionary.see("zorglub", unknown = false) }

            val frequency = dictionary.sets(big).getValue("fr").displayForms("zorglub").single().frequency

            assertThat(frequency).isEqualTo(40_000L)
        }

        @Test
        fun `la fréquence est répartie entre les formes selon leur usage`() {
            val big = mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("le" to 1_000_000_000L), "fr")))
            val three = PersonalDictionary(LearningConfig(minOccurrences = 4))
            repeat(3) { three.see("zorglub") }
            three.see("Zorglub")

            val forms = three.sets(big).getValue("fr").displayForms("zorglub")

            assertThat(forms.map { it.form }).containsExactly("zorglub", "Zorglub")
            assertThat(forms[0].frequency).isCloseTo(3 * forms[1].frequency, org.assertj.core.api.Assertions.within(3L))
        }

        @Test
        fun `sans mot promu, les lexiques sont renvoyés tels quels`() {
            val base = base()
            dictionary.see("zorglub")

            assertThat(dictionary.sets(base)).isSameAs(base)
        }

        @Test
        fun `les lexiques sont gardés tant que le dictionnaire ne change pas`() {
            repeat(2) { dictionary.see("zorglub") }
            val base = base()

            val first = dictionary.sets(base)

            assertThat(dictionary.sets(base)).isSameAs(first)
            dictionary.see("zorglub", unknown = false)
            assertThat(dictionary.sets(base)).isNotSameAs(first)
        }

        @Test
        fun `une langue sans mot appris garde son lexique`() {
            repeat(2) { dictionary.see("zorglub", "en") }
            val base = base()

            val sets = dictionary.sets(base)

            assertThat(sets.getValue("fr")).isSameAs(base.getValue("fr"))
            assertThat(sets.getValue("en")).isNotSameAs(base.getValue("en"))
            assertThat(sets.keys).containsExactly("fr", "en")
        }

        @Test
        fun `les révisions changent à chaque modification`() {
            val start = dictionary.revision

            dictionary.see("zorglub")
            val pending = dictionary.revision
            dictionary.see("zorglub")

            assertThat(pending).isGreaterThan(start)
            assertThat(dictionary.revision).isGreaterThan(pending)
            assertThat(dictionary.lexiconRevision).isGreaterThan(0L)
        }

        @Test
        fun `effacer vide tout`() {
            repeat(2) { dictionary.see("zorglub") }
            dictionary.see("blorp")
            val before = dictionary.lexiconRevision

            dictionary.clear()

            assertThat(dictionary.size).isZero()
            assertThat(dictionary.pendingSize).isZero()
            assertThat(dictionary.lexiconRevision).isGreaterThan(before)
        }

        @Test
        fun `les mots sont triés par langue puis forme`() {
            for (word in listOf("zebra", "Alpha", "beta")) repeat(2) { dictionary.see(word) }
            repeat(2) { dictionary.see("gamma", "en") }

            assertThat(dictionary.words().map { it.form }).containsExactly("gamma", "Alpha", "beta", "zebra")
            assertThat(dictionary.words("fr").map { it.form }).containsExactly("Alpha", "beta", "zebra")
        }

        @Test
        fun `un mot importé est promu et retire l'attente`() {
            dictionary.see("zorglub")

            assertThat(dictionary.importWord("Zorglub", "fr", 5)).isTrue()

            assertThat(dictionary.pendingSize).isZero()
            assertThat(dictionary.words()).containsExactly(PersonalWord("fr", "Zorglub", 5))
        }

        @Test
        fun `importer un mot présent additionne les compteurs`() {
            dictionary.importWord("zorglub", "fr", 5)
            dictionary.importWord("zorglub", "fr", 2)

            assertThat(dictionary.words().single().count).isEqualTo(7)
        }

        @Test
        fun `l'état s'enregistre et se restaure`() {
            repeat(2) { dictionary.see("zorglub") }
            dictionary.see("blorp")
            val (promoted, pending) = dictionary.snapshot()
            val copy = PersonalDictionary()

            copy.restore(promoted, pending)

            assertThat(copy.words()).isEqualTo(dictionary.words())
            assertThat(copy.pendingWords()).isEqualTo(dictionary.pendingWords())
        }

        @Test
        fun `la validation d'une prédiction apprend le mot inséré tel quel`() {
            val predictor = PredictFixtures.frenchPredictor()
            val state = predictor.initialLanguageState("fr")
            val literal = predictor.predict("Il dit zorglub", state, settings)
            val known = predictor.predict("Il dit bonjour", state, settings)
            val dominant = predictor.predict("Il dit bonj", state, settings)

            assertThat(dictionary.onValidated(literal, state, settings)).isEqualTo(LearnOutcome.PENDING)
            assertThat(dictionary.onValidated(known, state, settings)).isEqualTo(LearnOutcome.IGNORED)
            assertThat(dictionary.onValidated(dominant, state, settings)).isEqualTo(LearnOutcome.IGNORED)
            assertThat(dictionary.pendingWords().single().form).isEqualTo("zorglub")
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(strings = ["TEXT_INCOGNITO", "PASSWORD", "EMAIL", "URL", "NUMERIC"])
        fun `rien n'est appris là où c'est interdit`(kind: String) {
            val forbidden = when (kind) {
                "TEXT_INCOGNITO" -> PredictionSettings(incognito = true)
                else -> PredictionSettings(profile = FieldProfile.valueOf(kind))
            }

            assertThat(dictionary.see("zorglub", s = forbidden)).isEqualTo(LearnOutcome.IGNORED)
            assertThat(dictionary.see("zorglub", s = forbidden)).isEqualTo(LearnOutcome.IGNORED)

            assertThat(dictionary.pendingSize).isZero()
            assertThat(dictionary.size).isZero()
        }

        @Test
        fun `rien n'est appris avec NO_SUGGESTIONS`() {
            assertThat(dictionary.see("zorglub", s = PredictionSettings(noSuggestions = true))).isEqualTo(LearnOutcome.IGNORED)
        }

        @ParameterizedTest
        @ValueSource(strings = ["a", "", "abc1", "a@b.fr", "-ab", "ab-", "'ab", "ab'", "ab cd", "ab\tcd", "ab_cd", "Straße", "Привет", "https://x.fr", "12"])
        fun `un mot invalide n'est pas appris`(word: String) {
            assertThat(dictionary.see(word)).isEqualTo(LearnOutcome.IGNORED)

            assertThat(dictionary.pendingSize).isZero()
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "f", "FR", "fran", "f1", "fr-FR"])
        fun `une langue invalide est ignorée`(language: String) {
            assertThat(dictionary.see("zorglub", language)).isEqualTo(LearnOutcome.IGNORED)
            assertThat(dictionary.importWord("zorglub", language, 1)).isFalse()
            assertThat(dictionary.remove("zorglub", language)).isFalse()
            assertThat(dictionary.discard("zorglub", language)).isFalse()
            assertThat(dictionary.contains("zorglub", language)).isFalse()
        }

        @Test
        fun `une prédiction sans cas 1 ou 3 n'apprend rien`() {
            val predictor = PredictFixtures.frenchPredictor()
            val state = predictor.initialLanguageState("fr")
            val none = predictor.predict("", state, settings)

            assertThat(dictionary.onValidated(none, state, settings)).isEqualTo(LearnOutcome.IGNORED)
        }

        @Test
        fun `supprimer ou annuler un mot absent renvoie faux`() {
            assertThat(dictionary.remove("zorglub", "fr")).isFalse()
            assertThat(dictionary.discard("zorglub", "fr")).isFalse()
            assertThat(dictionary.remove("a", "fr")).isFalse()
            assertThat(dictionary.contains("a", "fr")).isFalse()
        }

        @Test
        fun `annuler un mot promu n'a pas d'effet`() {
            repeat(2) { dictionary.see("zorglub") }

            assertThat(dictionary.discard("zorglub", "fr")).isFalse()
            assertThat(dictionary.size).isEqualTo(1)
        }

        @Test
        fun `une importation invalide est refusée sans effet`() {
            assertThat(dictionary.importWord("zorglub", "fr", 0)).isFalse()
            assertThat(dictionary.importWord("zorglub", "fr", 1_000_001)).isFalse()
            assertThat(dictionary.importWord("a", "fr", 1)).isFalse()

            assertThat(dictionary.size).isZero()
        }

        @Test
        fun `une restauration invalide lève une exception sans rien modifier`() {
            repeat(2) { dictionary.see("zorglub") }
            val good = StoredWord("fr", listOf("blorp" to 2))
            val cases = mapOf(
                "langue" to StoredWord("FR", listOf("blorp" to 2)),
                "formes" to StoredWord("fr", emptyList()),
                "trop de formes" to StoredWord("fr", listOf("blorp" to 1, "Blorp" to 1, "BLORP" to 1, "bLorp" to 1)),
                "forme invalide" to StoredWord("fr", listOf("bl orp" to 2)),
                "forme non canonique" to StoredWord("fr", listOf("blo’rp" to 2)),
                "compteur nul" to StoredWord("fr", listOf("blorp" to 0)),
                "compteur trop grand" to StoredWord("fr", listOf("blorp" to 1_000_001)),
                "clés différentes" to StoredWord("fr", listOf("blorp" to 1, "blurp" to 1)),
                "forme en double" to StoredWord("fr", listOf("blorp" to 1, "blorp" to 1)),
            )

            for ((name, bad) in cases) {
                assertThatThrownBy { dictionary.restore(listOf(bad), emptyList()) }
                    .describedAs(name).isInstanceOf(IllegalArgumentException::class.java)
            }
            assertThatThrownBy { dictionary.restore(listOf(good, good), emptyList()) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("double")
            assertThatThrownBy { dictionary.restore(listOf(good), listOf(good)) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("promu et en attente")
            assertThat(dictionary.words().single().form).isEqualTo("zorglub")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `accepte un mot de la longueur minimale et refuse un caractère de moins`() {
            assertThat(dictionary.see("ab")).isEqualTo(LearnOutcome.PENDING)
            assertThat(dictionary.see("a")).isEqualTo(LearnOutcome.IGNORED)
        }

        @Test
        fun `accepte un mot de 32 caractères et refuse 33`() {
            assertThat(dictionary.see("a".repeat(32))).isEqualTo(LearnOutcome.PENDING)
            assertThat(dictionary.see("a".repeat(33))).isEqualTo(LearnOutcome.IGNORED)
        }

        @Test
        fun `le plafond du dictionnaire évince le mot le moins récemment utilisé`() {
            val small = PersonalDictionary(LearningConfig(maxWords = 3))
            for (word in listOf("alpha", "bravo", "delta")) small.importWord(word, "fr", 2)
            small.see("alpha", unknown = false) // alpha devient le plus récent.
            val before = small.lexiconRevision

            small.importWord("echoo", "fr", 2)

            assertThat(small.words().map { it.form }).containsExactlyInAnyOrder("alpha", "delta", "echoo")
            assertThat(small.lexiconRevision).isGreaterThan(before)
        }

        @Test
        fun `un dictionnaire plein de exactement maxWords mots n'évince rien`() {
            val small = PersonalDictionary(LearningConfig(maxWords = 2))

            small.importWord("alpha", "fr", 2)
            small.importWord("bravo", "fr", 2)

            assertThat(small.size).isEqualTo(2)
        }

        @Test
        fun `la liste d'attente évince le mot le moins récemment vu`() {
            val small = PersonalDictionary(LearningConfig(maxPending = 2, minOccurrences = 3))
            small.see("alpha")
            small.see("bravo")
            small.see("alpha") // alpha plus récent que bravo.

            small.see("delta")

            assertThat(small.pendingWords().map { it.form }).containsExactlyInAnyOrder("alpha", "delta")
        }

        @Test
        fun `la liste d'attente de exactement maxPending mots n'évince rien`() {
            val small = PersonalDictionary(LearningConfig(maxPending = 2))

            small.see("alpha")
            small.see("bravo")

            assertThat(small.pendingSize).isEqualTo(2)
        }

        @Test
        fun `le compteur d'un mot est plafonné`() {
            val small = PersonalDictionary(LearningConfig(maxCount = 3))
            repeat(2) { small.see("zorglub") }
            repeat(5) { small.see("zorglub", unknown = false) }

            assertThat(small.words().single().count).isEqualTo(3)
            assertThat(small.importWord("zorglub", "fr", 3)).isTrue()
            assertThat(small.words().single().count).isEqualTo(3)
            assertThat(small.importWord("blorp", "fr", 3)).isTrue()
            assertThat(small.importWord("blurp", "fr", 4)).isFalse()
        }

        @Test
        fun `le nombre de formes est plafonné`() {
            val one = PersonalDictionary(LearningConfig(maxFormsPerWord = 1, minOccurrences = 3))
            one.see("zorglub")
            one.see("Zorglub")
            one.see("zorglub")

            assertThat(one.words()).isEmpty()
            assertThat(one.pendingWords().single().count).isEqualTo(1)
        }

        @Test
        fun `l'importation garde les trois formes les plus utilisées`() {
            dictionary.importWord("zorglub", "fr", 5)
            dictionary.importWord("Zorglub", "fr", 4)
            dictionary.importWord("ZORGLUB", "fr", 3)
            dictionary.importWord("zORGLUB", "fr", 1)

            assertThat(dictionary.words().single().count).isEqualTo(12)
        }

        @Test
        fun `le compteur importé de exactement maxCount est accepté`() {
            assertThat(dictionary.importWord("zorglub", "fr", 1)).isTrue()
            assertThat(dictionary.importWord("blorp", "fr", 1_000_000)).isTrue()
        }

        @Test
        fun `la reconstruction des lexiques de 2 000 mots reste rapide`() {
            for (i in 0 until 2000) {
                dictionary.importWord("mot" + i.toString().map { 'a' + (it - '0') }.joinToString(""), "fr", 2)
            }
            val base = base()

            val start = System.nanoTime()
            val sets = dictionary.sets(base)
            val millis = (System.nanoTime() - start) / 1_000_000

            assertThat(dictionary.size).isEqualTo(2000)
            assertThat(sets.getValue("fr").containsKey("motbaaa")).isTrue()
            assertThat(millis).isLessThan(2000L)
        }

        @Test
        fun `la restauration accepte exactement les plafonds`() {
            val small = PersonalDictionary(LearningConfig(maxWords = 1, maxPending = 1))
            val stored = StoredWord("fr", listOf("alpha" to 1))
            val other = StoredWord("fr", listOf("bravo" to 1))

            small.restore(listOf(stored), listOf(other))

            assertThat(small.size).isEqualTo(1)
            assertThat(small.pendingSize).isEqualTo(1)
            assertThatThrownBy { small.restore(listOf(stored, other), emptyList()) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { small.restore(emptyList(), listOf(stored, other)) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
