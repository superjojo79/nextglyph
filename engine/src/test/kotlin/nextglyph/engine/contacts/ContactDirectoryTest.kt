package nextglyph.engine.contacts

import nextglyph.engine.layout.KeyboardLayouts
import nextglyph.engine.learn.LearnedData
import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import nextglyph.engine.predict.Predictor
import nextglyph.engine.predict.PredictionSettings
import nextglyph.engine.predict.SpaceCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ContactDirectoryTest {

    private val settings = PredictionSettings()

    private companion object {
        /** `baseShare` × masse du lexique (≈ 1,0005 milliard), à la quantification (0,07 %) près. */
        const val UNIT = 10_005L
        const val TOLERANCE = 30L
    }

    /** Lexique français à grande échelle : masse ≈ 1,0 milliard, `rose` à 2e-5 de la masse (mot courant). */
    private val frenchWords: List<Pair<String, Long>> = listOf(
        "le" to 1_000_000_000L,
        "bonjour" to 200_000L,
        "salut" to 100_000L,
        "rose" to 20_000L,
        "Rose" to 100L,
        "pierre" to 30_000L,
        "marie" to 15_000L,
        "marc" to 500L,
        "mars" to 100_000L,
    )

    private val base: Map<String, LexiconSet> = mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(frenchWords, "fr")))

    private val contacts = listOf(
        ContactName(givenName = "Hélène", familyName = "Dupont"),
        ContactName(givenName = "Jean-Pierre", familyName = "Martin", nickname = "JP"),
        ContactName(givenName = "Rose", familyName = "MARTIN"),
        ContactName(givenName = "Pierre", middleName = "Alain", familyName = "durand"),
    )

    private fun directory(
        list: List<ContactName> = contacts,
        config: ContactsConfig = ContactsConfig(),
        bases: Map<String, LexiconSet> = base,
    ) = ContactDirectory(list, bases, config)

    private fun predictor(directory: ContactDirectory, level: Int) =
        Predictor(directory.sets(level), KeyboardLayouts.AZERTY, blockedAutocommit = directory.autocommitGuard(level))

    @Nested
    inner class HappyPath {

        @Test
        fun `chaque champ est indexé avec sa forme d'affichage et sa clé`() {
            val d = directory()

            assertThat(d.entries.map { it.form }).containsExactlyInAnyOrder(
                "Hélène", "Dupont", "Jean-Pierre", "Martin", "JP", "Rose", "Pierre", "Alain", "Durand",
            )
            assertThat(d.entries.first { it.form == "Hélène" }.key).isEqualTo("helene")
            assertThat(d.entries.first { it.form == "Jean-Pierre" }.key).isEqualTo("jean-pierre")
            assertThat(d.isEmpty).isFalse()
        }

        @Test
        fun `un nom porté par plusieurs contacts est compté`() {
            val d = directory()

            assertThat(d.entries.first { it.form == "Martin" }.contacts).isEqualTo(2)
            assertThat(d.entries.first { it.form == "Dupont" }.contacts).isEqualTo(1)
        }

        @Test
        fun `les noms en minuscules ou en capitales prennent la casse d'un nom propre`() {
            val d = directory(
                listOf(
                    ContactName(givenName = "marie claire", familyName = "o'brien"),
                    ContactName(givenName = "ZOË", familyName = "d’Artagnan", middleName = "McDonald"),
                ),
            )

            assertThat(d.entries.map { it.form }).containsExactlyInAnyOrder("Marie", "Claire", "O'Brien", "Zoë", "d'Artagnan", "McDonald")
            assertThat(d.isName("zoe")).isTrue()
            assertThat(d.isName("d'artagnan")).isTrue()
        }

        @Test
        fun `les surnoms sont facultatifs`() {
            val d = directory(config = ContactsConfig(includeNicknames = false))

            assertThat(d.entries.map { it.form }).doesNotContain("JP")
        }

        @Test
        fun `les particules et les mots invalides ne sont pas indexés`() {
            val d = directory(
                listOf(ContactName(givenName = "Charles de", familyName = "Gaulle"), ContactName(givenName = "A", familyName = "B2"), ContactName(givenName = "Иван")),
            )

            assertThat(d.entries.map { it.form }).containsExactlyInAnyOrder("Charles", "Gaulle")
        }

        @Test
        fun `un nom répété dans un contact compte une fois`() {
            val d = directory(listOf(ContactName(givenName = "Martin", familyName = "Martin")))

            assertThat(d.entries.single().contacts).isEqualTo(1)
        }

        @Test
        fun `les noms enrichissent chaque langue, avec un poids croissant avec le contexte`() {
            val both = base + ("en" to LexiconSet(LexiconFixtures.lexicon(listOf("the" to 1_000_000_000L), "en")))
            val d = directory(bases = both)

            val levels = (0..2).map { d.sets(it) }

            for (set in levels) assertThat(set.keys).containsExactly("fr", "en")
            val dupont = levels.map { it.getValue("en").displayForms("dupont").single().frequency }
            // ≈ 1e9 × 1e-5 = 10 000 ; ×4 puis ×16 avec le contexte.
            assertThat(dupont[0]).isCloseTo(UNIT, within(TOLERANCE))
            assertThat(dupont[1]).isCloseTo(4 * UNIT, within(4 * TOLERANCE))
            assertThat(dupont[2]).isCloseTo(16 * UNIT, within(16 * TOLERANCE))
        }

        @Test
        fun `le poids croît avec le nombre de contacts qui portent le nom, jusqu'à un plafond`() {
            val many = List(30) { ContactName(givenName = "Martin") } + ContactName(givenName = "Dupont")
            val d = directory(many)

            val martin = d.sets(0).getValue("fr").displayForms("martin").single().frequency
            val dupont = d.sets(0).getValue("fr").displayForms("dupont").single().frequency

            assertThat(dupont).isCloseTo(UNIT, within(TOLERANCE))
            // 1 + ln 30 = 4,4 plafonné à 3.
            assertThat(martin).isCloseTo(3 * UNIT, within(3 * TOLERANCE))
        }

        @Test
        fun `un mot courant garde sa forme au niveau 0 et la perd quand le contexte le justifie`() {
            val d = directory()

            val rose0 = predictor(d, 0).predict("Il dit rose", predictor(d, 0).initialLanguageState("fr"), settings)
            val rose1 = predictor(d, 1).predict("Il dit rose", predictor(d, 1).initialLanguageState("fr"), settings)

            assertThat(rose0.inserted).isEqualTo("rose")
            assertThat(rose1.inserted).isEqualTo("Rose")
        }

        @Test
        fun `le nom d'un contact complète au niveau 1 et reste seulement proposé au niveau 0`() {
            val d = directory()
            val p0 = predictor(d, 0)
            val p1 = predictor(d, 1)

            val level0 = p0.predict("Il dit hele", p0.initialLanguageState("fr"), settings)
            val level1 = p1.predict("Il dit hele", p1.initialLanguageState("fr"), settings)

            assertThat(level0.case).isEqualTo(SpaceCase.LITERAL)
            assertThat(level0.candidates.map { it.text }).contains("Hélène")
            assertThat(level1.case).isEqualTo(SpaceCase.DOMINANT)
            assertThat(level1.inserted).isEqualTo("Hélène")
        }

        @Test
        fun `un nom tapé en entier est un mot dont accents et majuscule sont restitués, à tout niveau`() {
            val d = directory()
            val p0 = predictor(d, 0)

            val prediction = p0.predict("Il dit helene", p0.initialLanguageState("fr"), settings)

            assertThat(prediction.case).isEqualTo(SpaceCase.WORD)
            assertThat(prediction.inserted).isEqualTo("Hélène")
        }

        @Test
        fun `un mot courant n'est jamais remplacé par un nom, à aucun niveau`() {
            val d = directory(listOf(ContactName(givenName = "Mars"), ContactName(givenName = "Marc")))
            for (level in 0..2) {
                val p = predictor(d, level)

                val prediction = p.predict("Il dit mars", p.initialLanguageState("fr"), settings)

                assertThat(prediction.case).describedAs("niveau $level").isEqualTo(SpaceCase.WORD)
                assertThat(prediction.inserted.lowercase()).describedAs("niveau $level").isEqualTo("mars")
            }
        }

        @Test
        fun `la garde bloque les noms au niveau 0 seulement`() {
            val d = directory()

            assertThat(d.autocommitGuard(0)("helene")).isTrue()
            assertThat(d.autocommitGuard(0)("bonjour")).isFalse()
            assertThat(d.autocommitGuard(1)("helene")).isFalse()
            assertThat(d.autocommitGuard(2)("helene")).isFalse()
        }

        @Test
        fun `le dictionnaire personnel et les contacts se composent`() {
            val d = directory()
            val learned = LearnedData()
            repeat(2) { learned.personal.observe("zorglub", "fr", true, settings) }

            val p = Predictor(learned.personal.sets(d.sets(1)), KeyboardLayouts.AZERTY, blockedAutocommit = d.autocommitGuard(1))
            val state = p.initialLanguageState("fr")

            assertThat(p.predict("Il dit zorg", state, settings).inserted).isEqualTo("zorglub")
            assertThat(p.predict("Il dit hele", state, settings).inserted).isEqualTo("Hélène")
        }

        @Test
        fun `les noms composés sont proposés en entier`() {
            val d = directory()
            val p = predictor(d, 1)

            val prediction = p.predict("Il dit jean-pi", p.initialLanguageState("fr"), settings)

            assertThat(prediction.candidates.map { it.text }).contains("Jean-Pierre")
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `sans contact exploitable, les lexiques sont renvoyés tels quels`() {
            val d = directory(listOf(ContactName(), ContactName(givenName = "  "), ContactName(givenName = "x")))

            assertThat(d.isEmpty).isTrue()
            for (level in 0..2) assertThat(d.sets(level)).isSameAs(base)
        }

        @Test
        fun `un niveau hors des bornes est refusé`() {
            val d = directory()

            assertThatThrownBy { d.sets(-1) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Niveau")
            assertThatThrownBy { d.sets(3) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "  ", "a", "ab1", "@ab", "a_b", "-ab", "ab-", "'ab", "ab'", "Straße", "http://x"])
        fun `un mot qui n'est pas un nom indexable est ignoré`(word: String) {
            val d = directory(listOf(ContactName(givenName = word)))

            assertThat(d.entries).isEmpty()
        }

        @Test
        fun `le nom d'une personne n'est pas un contexte sans contact`() {
            val d = directory(emptyList())

            assertThat(d.signals(ContactContext(previousWord = "Hélène"))).isZero()
        }

        @Test
        fun `un mot précédent trop long ou invalide n'est pas un nom`() {
            val d = directory()

            assertThat(d.signals(ContactContext(previousWord = "A".repeat(65)))).isZero()
            assertThat(d.signals(ContactContext(previousWord = "Ab1"))).isZero()
            assertThat(d.signals(ContactContext(previousWord = "12"))).isZero()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `le plafond de noms est respecté`() {
            val d = directory(List(10) { ContactName(givenName = "Nom" + ('a' + it)) }, ContactsConfig(maxNames = 3))

            assertThat(d.entries).hasSize(3)
        }

        @Test
        fun `un nom de la longueur minimale ou maximale est indexé, un caractère de plus ou de moins non`() {
            val d = directory(
                listOf(
                    ContactName(givenName = "Ab", familyName = "B"),
                    ContactName(givenName = "a".repeat(32), familyName = "a".repeat(33)),
                ),
            )

            assertThat(d.entries.map { it.form.length }).containsExactlyInAnyOrder(2, 32)
        }

        @Test
        fun `deux majuscules sont des initiales, trois sont un nom en capitales`() {
            val d = directory(listOf(ContactName(givenName = "JP", familyName = "DUPONT")))

            assertThat(d.entries.map { it.form }).containsExactlyInAnyOrder("JP", "Dupont")
        }

        @Test
        fun `un seul niveau de bonus`() {
            val d = directory(config = ContactsConfig(maxBoostLevel = 0))

            assertThat(d.sets(0)).isNotSameAs(base)
            assertThatThrownBy { d.sets(1) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(d.levelOf(ContactContext(personNameField = true, typedCapital = true))).isZero()
        }

        @Test
        fun `un nom absent du lexique principal n'est pas plafonné au niveau 0`() {
            val d = directory()

            val dupont = d.sets(0).getValue("fr").displayForms("dupont").single().frequency

            assertThat(dupont).isCloseTo(UNIT, within(TOLERANCE))
        }

        @Test
        fun `le plafond d'un mot courant vaut une part de sa fréquence`() {
            val d = directory()

            val forms = d.sets(0).getValue("fr").displayForms("rose")

            // rose 20 000 + Rose 100 : plafond de 25 % de 20 100 = 5 025, ajouté à la forme Rose.
            assertThat(forms.map { it.form to it.frequency }).containsExactly("rose" to 20_000L, "Rose" to 5_125L)
        }

        @Test
        fun `un lexique principal vide de masse donne quand même des noms`() {
            val tiny = mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("le" to 1L), "fr")))

            val d = directory(bases = tiny)

            assertThat(d.sets(0).getValue("fr").displayForms("dupont").single().frequency).isEqualTo(1L)
        }
    }
}
