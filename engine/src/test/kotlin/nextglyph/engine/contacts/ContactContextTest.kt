package nextglyph.engine.contacts

import nextglyph.engine.lexicon.LexiconFixtures
import nextglyph.engine.lexicon.LexiconSet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ContactContextTest {

    private val base = mapOf("fr" to LexiconSet(LexiconFixtures.lexicon(listOf("le" to 1000L, "rose" to 10L), "fr")))

    private val directory = ContactDirectory(
        listOf(ContactName(givenName = "Hélène", familyName = "Dupont"), ContactName(givenName = "Rose")),
        base,
    )

    private fun levelOf(context: ContactContext) = directory.levelOf(context)

    private fun rejects(message: String, build: () -> Any) {
        assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `aucun indice, niveau 0`() {
            assertThat(levelOf(ContactContext())).isZero()
        }

        @Test
        fun `un champ de nom de personne est un indice`() {
            assertThat(directory.signals(ContactContext(personNameField = true))).isEqualTo(1)
        }

        @Test
        fun `un début de message est un indice`() {
            assertThat(directory.signals(ContactContext(atMessageStart = true))).isEqualTo(1)
        }

        @Test
        fun `une majuscule tapée est un indice`() {
            assertThat(directory.signals(ContactContext(typedCapital = true))).isEqualTo(1)
        }

        @ParameterizedTest
        @ValueSource(strings = ["bonjour", "Bonjour", "salut", "coucou", "cher", "chère", "avec", "chez", "à", "pour", "hello", "Hi", "hey", "dear", "with", "to", "for", "from", "@", " bonjour "])
        fun `un mot déclencheur français ou anglais est un indice`(word: String) {
            assertThat(directory.signals(ContactContext(previousWord = word))).isEqualTo(1)
        }

        @Test
        fun `un nom de contact en majuscule initiale est un indice (prénom puis nom)`() {
            assertThat(directory.signals(ContactContext(previousWord = "Hélène"))).isEqualTo(1)
            assertThat(directory.signals(ContactContext(previousWord = "Helene"))).isEqualTo(1)
        }

        @Test
        fun `les indices se cumulent jusqu'au plafond`() {
            assertThat(levelOf(ContactContext(personNameField = true))).isEqualTo(1)
            assertThat(levelOf(ContactContext(personNameField = true, typedCapital = true))).isEqualTo(2)
            assertThat(directory.signals(ContactContext(true, true, "bonjour", true))).isEqualTo(4)
            assertThat(levelOf(ContactContext(true, true, "bonjour", true))).isEqualTo(2)
        }

        @Test
        fun `les mots déclencheurs de la spec sont la liste par défaut`() {
            assertThat(ContactsConfig().triggerWords).containsAll(
                listOf("bonjour", "salut", "coucou", "cher", "chère", "avec", "chez", "à", "pour", "@"),
            )
            assertThat(ContactsConfig.FRENCH_TRIGGERS).hasSize(9)
            assertThat(ContactsConfig.ENGLISH_TRIGGERS).hasSize(8)
        }

        @Test
        fun `les valeurs par défaut des contacts`() {
            val config = ContactsConfig()

            assertThat(config.baseShare).isEqualTo(1e-5)
            assertThat(config.contextBoost).isEqualTo(4.0)
            assertThat(config.maxBoostLevel).isEqualTo(2)
            assertThat(config.commonWordCap).isEqualTo(0.25)
            assertThat(config.maxMultiplicity).isEqualTo(3.0)
            assertThat(config.maxNames).isEqualTo(20_000)
            assertThat(config.minNameLength).isEqualTo(2)
            assertThat(config.maxNameLength).isEqualTo(32)
            assertThat(config.includeNicknames).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(strings = ["a", "table", "hélène", "helene", "Inconnu", "", "  ", "mot"])
        fun `un autre mot précédent n'est pas un indice`(word: String) {
            assertThat(directory.signals(ContactContext(previousWord = word))).isZero()
        }

        @Test
        fun `la préposition a sans accent n'est pas un déclencheur`() {
            assertThat(directory.signals(ContactContext(previousWord = "a"))).isZero()
            assertThat(directory.signals(ContactContext(previousWord = "A"))).isZero()
        }

        @Test
        fun `rejette les valeurs invalides`() {
            rejects("baseShare") { ContactsConfig(baseShare = 0.0) }
            rejects("baseShare") { ContactsConfig(baseShare = 1.1) }
            rejects("contextBoost") { ContactsConfig(contextBoost = 0.9) }
            rejects("contextBoost") { ContactsConfig(contextBoost = Double.POSITIVE_INFINITY) }
            rejects("maxBoostLevel") { ContactsConfig(maxBoostLevel = -1) }
            rejects("commonWordCap") { ContactsConfig(commonWordCap = 0.0) }
            rejects("commonWordCap") { ContactsConfig(commonWordCap = 1.1) }
            rejects("maxMultiplicity") { ContactsConfig(maxMultiplicity = 0.9) }
            rejects("maxMultiplicity") { ContactsConfig(maxMultiplicity = Double.NaN) }
            rejects("maxNames") { ContactsConfig(maxNames = -1) }
            rejects("minNameLength") { ContactsConfig(minNameLength = 0) }
            rejects("maxNameLength") { ContactsConfig(minNameLength = 3, maxNameLength = 2) }
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un mot précédent nul ou vide n'ajoute rien`() {
            assertThat(directory.signals(ContactContext(previousWord = null))).isZero()
            assertThat(directory.signals(ContactContext(previousWord = ""))).isZero()
        }

        @Test
        fun `un mot précédent de exactement 64 caractères est examiné`() {
            assertThat(directory.signals(ContactContext(previousWord = "A".repeat(64)))).isZero()
        }

        @Test
        fun `les valeurs limites de la configuration sont acceptées`() {
            ContactsConfig(baseShare = 1.0, contextBoost = 1.0, maxBoostLevel = 0, commonWordCap = 1.0, maxMultiplicity = 1.0, maxNames = 0, minNameLength = 1, maxNameLength = 1)
        }

        @Test
        fun `le niveau est borné par maxBoostLevel`() {
            val one = ContactDirectory(listOf(ContactName(givenName = "Hélène")), base, ContactsConfig(maxBoostLevel = 1))

            assertThat(one.levelOf(ContactContext(true, true, "bonjour", true))).isEqualTo(1)
        }
    }
}
