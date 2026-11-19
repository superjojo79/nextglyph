package nextglyph.engine.profile

import nextglyph.engine.layout.DateTimeVariant
import nextglyph.engine.profile.AndroidInputConstants as C
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class InputProfileResolverTest {

    private fun resolve(inputType: Int, imeOptions: Int = 0) = InputProfileResolver.resolve(inputType, imeOptions, null)

    companion object {
        private const val TEXT = C.TYPE_CLASS_TEXT
        private const val NUMBER = C.TYPE_CLASS_NUMBER
        private const val PHONE = C.TYPE_CLASS_PHONE
        private const val DATETIME = C.TYPE_CLASS_DATETIME

        private fun row(name: String, inputType: Int, type: ProfileType, layout: ProfileLayout): Arguments =
            Arguments.of(name, inputType, type, layout)

        /** Table du §4 : une ligne par détection, y compris les combinaisons contradictoires. */
        @JvmStatic
        fun priorityTable(): Stream<Arguments> = Stream.of(
            row("nombre + variation mot de passe : code PIN", NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD, ProfileType.PIN, ProfileLayout.PIN_PAD),
            row("PIN avec signé et décimal : toujours PIN", NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD or C.TYPE_NUMBER_FLAG_SIGNED or C.TYPE_NUMBER_FLAG_DECIMAL, ProfileType.PIN, ProfileLayout.PIN_PAD),
            row("nombre normal", NUMBER, ProfileType.NUMBER, ProfileLayout.NUMBER_PAD),
            row("nombre signé décimal", NUMBER or C.TYPE_NUMBER_FLAG_SIGNED or C.TYPE_NUMBER_FLAG_DECIMAL, ProfileType.NUMBER, ProfileLayout.NUMBER_PAD),
            row("nombre avec drapeaux de texte", NUMBER or C.TYPE_TEXT_FLAG_NO_SUGGESTIONS or C.TYPE_TEXT_FLAG_MULTI_LINE, ProfileType.NUMBER, ProfileLayout.NUMBER_PAD),
            row("nombre avec variation inconnue", NUMBER or 0x20, ProfileType.NUMBER, ProfileLayout.NUMBER_PAD),
            row("nombre avec variation d'e-mail : reste numérique", NUMBER or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, ProfileType.NUMBER, ProfileLayout.NUMBER_PAD),
            row("téléphone", PHONE, ProfileType.PHONE, ProfileLayout.PHONE_PAD),
            row("téléphone avec variation de mot de passe de texte : reste téléphone", PHONE or C.TYPE_TEXT_VARIATION_PASSWORD, ProfileType.PHONE, ProfileLayout.PHONE_PAD),
            row("téléphone avec variation de mot de passe de nombre : reste téléphone", PHONE or C.TYPE_NUMBER_VARIATION_PASSWORD, ProfileType.PHONE, ProfileLayout.PHONE_PAD),
            row("date/heure normal", DATETIME, ProfileType.DATE_TIME, ProfileLayout.DATE_TIME_PAD),
            row("date", DATETIME or C.TYPE_DATETIME_VARIATION_DATE, ProfileType.DATE_TIME, ProfileLayout.DATE_TIME_PAD),
            row("heure", DATETIME or C.TYPE_DATETIME_VARIATION_TIME, ProfileType.DATE_TIME, ProfileLayout.DATE_TIME_PAD),
            row("date/heure avec variation de mot de passe de texte : reste date/heure", DATETIME or C.TYPE_TEXT_VARIATION_PASSWORD, ProfileType.DATE_TIME, ProfileLayout.DATE_TIME_PAD),
            row("texte mot de passe", TEXT or C.TYPE_TEXT_VARIATION_PASSWORD, ProfileType.PASSWORD, ProfileLayout.PASSWORD),
            row("texte mot de passe web", TEXT or C.TYPE_TEXT_VARIATION_WEB_PASSWORD, ProfileType.PASSWORD, ProfileLayout.PASSWORD),
            row("texte mot de passe visible", TEXT or C.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, ProfileType.PASSWORD, ProfileLayout.PASSWORD),
            row("mot de passe avec drapeaux de majuscules et de suggestions", TEXT or C.TYPE_TEXT_VARIATION_PASSWORD or C.TYPE_TEXT_FLAG_CAP_SENTENCES or C.TYPE_TEXT_FLAG_AUTO_CORRECT, ProfileType.PASSWORD, ProfileLayout.PASSWORD),
            row("e-mail", TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, ProfileType.EMAIL, ProfileLayout.COMPACT),
            row("e-mail web", TEXT or C.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, ProfileType.EMAIL, ProfileLayout.COMPACT),
            row("URL", TEXT or C.TYPE_TEXT_VARIATION_URI, ProfileType.URL, ProfileLayout.COMPACT),
            row("nom de personne", TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME, ProfileType.PERSON_NAME, ProfileLayout.COMPACT),
            row("texte normal", TEXT, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("sujet d'e-mail : texte", TEXT or C.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("message court : texte", TEXT or C.TYPE_TEXT_VARIATION_SHORT_MESSAGE, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("message long : texte", TEXT or C.TYPE_TEXT_VARIATION_LONG_MESSAGE, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("adresse postale : texte", TEXT or C.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("zone web : texte", TEXT or C.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("filtre : texte", TEXT or C.TYPE_TEXT_VARIATION_FILTER, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("phonétique : texte", TEXT or C.TYPE_TEXT_VARIATION_PHONETIC, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("texte avec variation inconnue", TEXT or 0xff0, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("TYPE_NULL : texte", C.TYPE_NULL, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("classe inconnue 5 : texte", 5, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("classe inconnue avec variation d'e-mail et drapeaux : texte ordinaire", 5 or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or C.TYPE_TEXT_FLAG_CAP_SENTENCES, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("classe inconnue 15 : texte", 15, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("classe inconnue avec variation de mot de passe : texte", 5 or C.TYPE_TEXT_VARIATION_PASSWORD, ProfileType.TEXT, ProfileLayout.COMPACT),
            row("texte avec drapeaux de nombre : drapeaux sans effet", TEXT or C.TYPE_NUMBER_FLAG_SIGNED or C.TYPE_NUMBER_FLAG_DECIMAL, ProfileType.TEXT, ProfileLayout.COMPACT),
        )
    }

    @Nested
    inner class HappyPath {
        @ParameterizedTest(name = "{0}")
        @MethodSource("nextglyph.engine.profile.InputProfileResolverTest#priorityTable")
        fun `le profil suit l'ordre de priorité du §4`(name: String, inputType: Int, type: ProfileType, layout: ProfileLayout) {
            val profile = resolve(inputType)

            assertThat(profile.type).`as`(name).isEqualTo(type)
            assertThat(profile.layout).`as`(name).isEqualTo(layout)
        }

        @Test
        fun `le profil texte active tout`() {
            val profile = resolve(TEXT)

            assertThat(profile).isEqualTo(
                InputProfile(
                    type = ProfileType.TEXT, layout = ProfileLayout.COMPACT,
                    adaptation = true, suggestions = true, readsText = true, learning = true,
                    autoCapitalize = false, capitalization = CapitalizationMode.NONE, typography = true,
                    zeroLambda = false, keyPreview = true, multiLine = false, incognito = false,
                    contactsPriority = false, shortcutBar = ShortcutBar.NONE,
                    numberSigned = false, numberDecimal = false, dateTimeVariant = null,
                ),
            )
        }

        @Test
        fun `le mot de passe suit le tableau du §7-2`() {
            val profile = resolve(TEXT or C.TYPE_TEXT_VARIATION_PASSWORD)

            assertThat(profile.adaptation).isFalse()
            assertThat(profile.zeroLambda).isTrue()
            assertThat(profile.suggestions).isFalse()
            assertThat(profile.autoCapitalize).isFalse()
            assertThat(profile.learning).isFalse()
            assertThat(profile.keyPreview).isFalse()
            assertThat(profile.readsText).isFalse()
            assertThat(profile.typography).isFalse()
        }

        @Test
        fun `la disposition classique d'un texte a la disposition et les règles du mot de passe`() {
            val text = resolve(TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES or C.TYPE_TEXT_FLAG_MULTI_LINE)
            val password = resolve(TEXT or C.TYPE_TEXT_VARIATION_PASSWORD)

            val classic = InputProfileResolver.classic(text)

            assertThat(classic).isEqualTo(password.copy(type = ProfileType.TEXT, multiLine = true))
        }

        @Test
        fun `la disposition classique garde le type et l'incognito du champ`() {
            val email = resolve(TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, C.IME_FLAG_NO_PERSONALIZED_LEARNING)

            val classic = InputProfileResolver.classic(email)

            assertThat(classic.type).isEqualTo(ProfileType.EMAIL)
            assertThat(classic.layout).isEqualTo(ProfileLayout.PASSWORD)
            assertThat(classic.incognito).isTrue()
            assertThat(classic.shortcutBar).isEqualTo(ShortcutBar.NONE)
            assertThat(classic.multiLine).isFalse()
        }

        @Test
        fun `le code PIN a les règles du mot de passe`() {
            val pin = resolve(NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD)
            val password = resolve(TEXT or C.TYPE_TEXT_VARIATION_PASSWORD)

            assertThat(pin).isEqualTo(password.copy(type = ProfileType.PIN, layout = ProfileLayout.PIN_PAD))
            assertThat(pin.numberSigned).isFalse()
            assertThat(pin.numberDecimal).isFalse()
        }

        @Test
        fun `les pavés sont purement spatiaux, sans lecture ni apprentissage`() {
            for (inputType in listOf(NUMBER, PHONE, DATETIME)) {
                val profile = resolve(inputType)

                assertThat(profile.adaptation).isFalse()
                assertThat(profile.zeroLambda).isTrue()
                assertThat(profile.readsText).isFalse()
                assertThat(profile.learning).isFalse()
                assertThat(profile.suggestions).isFalse()
                assertThat(profile.keyPreview).isFalse()
            }
        }

        @Test
        fun `signé et décimal pilotent le pavé numérique`() {
            assertThat(resolve(NUMBER).numberSigned).isFalse()
            assertThat(resolve(NUMBER).numberDecimal).isFalse()
            assertThat(resolve(NUMBER or C.TYPE_NUMBER_FLAG_SIGNED).numberSigned).isTrue()
            assertThat(resolve(NUMBER or C.TYPE_NUMBER_FLAG_SIGNED).numberDecimal).isFalse()
            assertThat(resolve(NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL).numberDecimal).isTrue()
            assertThat(resolve(NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL).numberSigned).isFalse()
        }

        @Test
        fun `la variation date, heure ou normale donne la variante du pavé`() {
            assertThat(resolve(DATETIME or C.TYPE_DATETIME_VARIATION_DATE).dateTimeVariant).isEqualTo(DateTimeVariant.DATE)
            assertThat(resolve(DATETIME or C.TYPE_DATETIME_VARIATION_TIME).dateTimeVariant).isEqualTo(DateTimeVariant.TIME)
            assertThat(resolve(DATETIME).dateTimeVariant).isEqualTo(DateTimeVariant.DATE_TIME)
            assertThat(resolve(DATETIME or 0x30).dateTimeVariant).isEqualTo(DateTimeVariant.DATE_TIME)
        }

        @Test
        fun `l'e-mail et l'URL ont une barre de raccourcis, sans suggestions ni typographie ni majuscules`() {
            val email = resolve(TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or C.TYPE_TEXT_FLAG_CAP_SENTENCES)
            val url = resolve(TEXT or C.TYPE_TEXT_VARIATION_URI or C.TYPE_TEXT_FLAG_CAP_WORDS)

            assertThat(email.shortcutBar).isEqualTo(ShortcutBar.EMAIL)
            assertThat(url.shortcutBar).isEqualTo(ShortcutBar.URL)
            assertThat(ShortcutBar.EMAIL.shortcuts).containsExactly("@", ".", ".com", ".fr", "-", "_")
            assertThat(ShortcutBar.URL.shortcuts).containsExactly("/", ".", ".com", ".fr", "-")
            assertThat(ShortcutBar.NONE.shortcuts).isEmpty()
            for (profile in listOf(email, url)) {
                assertThat(profile.adaptation).isTrue()
                assertThat(profile.suggestions).isFalse()
                assertThat(profile.typography).isFalse()
                assertThat(profile.autoCapitalize).isFalse()
                assertThat(profile.capitalization).isEqualTo(CapitalizationMode.NONE)
                assertThat(profile.zeroLambda).isFalse()
            }
        }

        @Test
        fun `le nom de personne donne la priorité aux contacts`() {
            assertThat(resolve(TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME).contactsPriority).isTrue()
            assertThat(resolve(TEXT).contactsPriority).isFalse()
            assertThat(resolve(TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).contactsPriority).isFalse()
        }

        @Test
        fun `l'incognito désactive l'apprentissage sans changer le reste`() {
            val normal = resolve(TEXT)
            val incognito = resolve(TEXT, C.IME_FLAG_NO_PERSONALIZED_LEARNING)

            assertThat(incognito.incognito).isTrue()
            assertThat(incognito.learning).isFalse()
            assertThat(incognito).isEqualTo(normal.copy(incognito = true, learning = false))
        }

        @Test
        fun `NO_SUGGESTIONS retire les suggestions mais garde l'adaptation`() {
            val profile = resolve(TEXT or C.TYPE_TEXT_FLAG_NO_SUGGESTIONS)

            assertThat(profile.suggestions).isFalse()
            assertThat(profile.adaptation).isTrue()
            assertThat(profile.readsText).isTrue()
            assertThat(profile.zeroLambda).isFalse()
        }

        @Test
        fun `MULTI_LINE fait de Entrée un saut de ligne dans les profils texte`() {
            assertThat(resolve(TEXT or C.TYPE_TEXT_FLAG_MULTI_LINE).multiLine).isTrue()
            assertThat(resolve(TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME or C.TYPE_TEXT_FLAG_MULTI_LINE).multiLine).isTrue()
            assertThat(resolve(TEXT or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or C.TYPE_TEXT_FLAG_MULTI_LINE).multiLine).isTrue()
            assertThat(resolve(TEXT).multiLine).isFalse()
        }

        @Test
        fun `les drapeaux CAP donnent la règle de majuscules`() {
            assertThat(resolve(TEXT or C.TYPE_TEXT_FLAG_CAP_CHARACTERS).capitalization).isEqualTo(CapitalizationMode.CHARACTERS)
            assertThat(resolve(TEXT or C.TYPE_TEXT_FLAG_CAP_WORDS).capitalization).isEqualTo(CapitalizationMode.WORDS)
            assertThat(resolve(TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES).capitalization).isEqualTo(CapitalizationMode.SENTENCES)
            assertThat(resolve(TEXT).capitalization).isEqualTo(CapitalizationMode.NONE)
            assertThat(resolve(TEXT or C.TYPE_TEXT_FLAG_CAP_SENTENCES).autoCapitalize).isTrue()
            assertThat(resolve(TEXT).autoCapitalize).isFalse()
            assertThat(resolve(TEXT or C.TYPE_TEXT_VARIATION_PERSON_NAME or C.TYPE_TEXT_FLAG_CAP_WORDS).autoCapitalize).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `les combinaisons contradictoires ne lèvent rien et donnent un profil cohérent`() {
            val inputs = listOf(
                -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x7fffffff.inv(),
                NUMBER or C.TYPE_TEXT_VARIATION_PASSWORD or C.TYPE_TEXT_FLAG_CAP_SENTENCES,
                PHONE or C.TYPE_NUMBER_VARIATION_PASSWORD or C.TYPE_NUMBER_FLAG_DECIMAL,
                DATETIME or C.TYPE_TEXT_VARIATION_URI or C.TYPE_TEXT_FLAG_MULTI_LINE,
            )

            for (inputType in inputs) {
                val profile = resolve(inputType, -1)

                assertThat(profile.incognito).isTrue()
                assertThat(profile.learning).isFalse()
            }
        }

        @Test
        fun `un nombre avec les drapeaux de texte CAP ne produit pas de majuscules`() {
            val profile = resolve(NUMBER or C.TYPE_TEXT_FLAG_CAP_CHARACTERS or C.TYPE_TEXT_FLAG_CAP_WORDS or C.TYPE_TEXT_FLAG_CAP_SENTENCES)

            assertThat(profile.autoCapitalize).isFalse()
            assertThat(profile.capitalization).isEqualTo(CapitalizationMode.NONE)
            assertThat(profile.multiLine).isFalse()
        }

        @Test
        fun `le texte avec les drapeaux de nombre ne propose ni signe ni séparateur`() {
            val profile = resolve(TEXT or C.TYPE_NUMBER_FLAG_SIGNED or C.TYPE_NUMBER_FLAG_DECIMAL)

            assertThat(profile.numberSigned).isFalse()
            assertThat(profile.numberDecimal).isFalse()
            assertThat(profile.dateTimeVariant).isNull()
            // 0x1000 et 0x2000 sont ici CAP_CHARACTERS et CAP_WORDS : la règle la plus large l'emporte.
            assertThat(profile.capitalization).isEqualTo(CapitalizationMode.CHARACTERS)
        }

        @Test
        fun `NO_SUGGESTIONS et MULTI_LINE sont sans effet sur un nombre, un mot de passe ou un téléphone`() {
            val bits = C.TYPE_TEXT_FLAG_NO_SUGGESTIONS or C.TYPE_TEXT_FLAG_MULTI_LINE

            assertThat(resolve(NUMBER or bits)).isEqualTo(resolve(NUMBER))
            assertThat(resolve(PHONE or bits)).isEqualTo(resolve(PHONE))
            assertThat(resolve(TEXT or C.TYPE_TEXT_VARIATION_PASSWORD or bits)).isEqualTo(resolve(TEXT or C.TYPE_TEXT_VARIATION_PASSWORD))
        }

        @Test
        fun `les options privées sont acceptées et ignorées`() {
            assertThat(InputProfileResolver.resolve(TEXT, 0, "nextglyph.foo=1,autre")).isEqualTo(resolve(TEXT))
            assertThat(InputProfileResolver.resolve(NUMBER, 0, "")).isEqualTo(resolve(NUMBER))
        }

        @Test
        fun `les autres drapeaux d'imeOptions sont sans effet`() {
            val others = 0x02000000 or 0x10000000 or 0x40000000 or C.IME_MASK_ACTION

            assertThat(resolve(TEXT, others)).isEqualTo(resolve(TEXT))
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `la disposition classique ne change ni un mot de passe ni un pavé`() {
            val locked = listOf(
                resolve(TEXT or C.TYPE_TEXT_VARIATION_PASSWORD),
                resolve(NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD),
                resolve(NUMBER or C.TYPE_NUMBER_FLAG_DECIMAL),
                resolve(PHONE),
                resolve(DATETIME),
            )

            for (profile in locked) assertThat(InputProfileResolver.classic(profile)).isSameAs(profile)
        }

        @Test
        fun `la variation est lue sur 8 bits, pas plus`() {
            // Le bit 0x1000 appartient aux drapeaux, pas à la variation.
            assertThat(resolve(TEXT or 0x1000 or C.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).type).isEqualTo(ProfileType.EMAIL)
            // La variation 0x180 (bit 0x100 en plus) n'est pas le mot de passe.
            assertThat(resolve(TEXT or 0x180).type).isEqualTo(ProfileType.TEXT)
        }

        @Test
        fun `les valeurs voisines des variations de mot de passe sont du texte`() {
            for (variation in listOf(0x70, 0xf0, 0x100, 0x180)) {
                assertThat(resolve(TEXT or variation).type).`as`("variation $variation").isEqualTo(ProfileType.TEXT)
            }
        }

        @Test
        fun `les classes voisines de 1 à 4 sont du texte`() {
            assertThat(resolve(0).type).isEqualTo(ProfileType.TEXT)
            assertThat(resolve(5).type).isEqualTo(ProfileType.TEXT)
        }

        @Test
        fun `la précédence des majuscules est caractères, mots, phrases`() {
            val all = C.TYPE_TEXT_FLAG_CAP_CHARACTERS or C.TYPE_TEXT_FLAG_CAP_WORDS or C.TYPE_TEXT_FLAG_CAP_SENTENCES

            assertThat(resolve(TEXT or all).capitalization).isEqualTo(CapitalizationMode.CHARACTERS)
            assertThat(resolve(TEXT or C.TYPE_TEXT_FLAG_CAP_WORDS or C.TYPE_TEXT_FLAG_CAP_SENTENCES).capitalization).isEqualTo(CapitalizationMode.WORDS)
        }

        @Test
        fun `le drapeau d'apprentissage est lu sur le bon bit`() {
            assertThat(resolve(TEXT, C.IME_FLAG_NO_PERSONALIZED_LEARNING).incognito).isTrue()
            assertThat(resolve(TEXT, C.IME_FLAG_NO_PERSONALIZED_LEARNING shr 1).incognito).isFalse()
            assertThat(resolve(TEXT, C.IME_FLAG_NO_PERSONALIZED_LEARNING shl 1).incognito).isFalse()
        }

        @Test
        fun `l'incognito vaut pour tous les profils`() {
            for (inputType in listOf(NUMBER, PHONE, DATETIME, NUMBER or C.TYPE_NUMBER_VARIATION_PASSWORD, TEXT or C.TYPE_TEXT_VARIATION_PASSWORD, TEXT or C.TYPE_TEXT_VARIATION_URI)) {
                val profile = resolve(inputType, C.IME_FLAG_NO_PERSONALIZED_LEARNING)

                assertThat(profile.incognito).isTrue()
                assertThat(profile.learning).isFalse()
            }
        }

        @Test
        fun `balayage exhaustif classe x variation x drapeaux - invariants`() {
            val variations = (0..0xff).map { it shl 4 }
            val flagSets = listOf(0, C.TYPE_TEXT_FLAG_CAP_SENTENCES, C.TYPE_TEXT_FLAG_NO_SUGGESTIONS, C.TYPE_TEXT_FLAG_MULTI_LINE, 0x00fff000)
            for (cls in 0..15) for (variation in variations) for (flags in flagSets) for (ime in listOf(0, C.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
                val profile = resolve(cls or variation or flags, ime)

                val locked = profile.type in setOf(ProfileType.PIN, ProfileType.NUMBER, ProfileType.PHONE, ProfileType.DATE_TIME, ProfileType.PASSWORD)
                if (locked) {
                    assertThat(profile.adaptation || profile.suggestions || profile.readsText || profile.learning || profile.autoCapitalize || profile.typography || profile.multiLine).isFalse()
                    assertThat(profile.zeroLambda).isTrue()
                } else {
                    assertThat(profile.zeroLambda).isFalse()
                    assertThat(profile.adaptation).isTrue()
                }
                assertThat(profile.learning && profile.incognito).isFalse()
                assertThat(profile.autoCapitalize).isEqualTo(profile.capitalization != CapitalizationMode.NONE)
                assertThat(profile.keyPreview).isEqualTo(!locked)
                assertThat(profile.layout == ProfileLayout.COMPACT).isEqualTo(!locked)
                assertThat(profile.type == ProfileType.NUMBER || (!profile.numberSigned && !profile.numberDecimal)).isTrue()
                assertThat(profile.type == ProfileType.DATE_TIME).isEqualTo(profile.dateTimeVariant != null)
                val expectedClass = when (cls) {
                    2 -> if (variation == 0x10) ProfileType.PIN else ProfileType.NUMBER
                    3 -> ProfileType.PHONE
                    4 -> ProfileType.DATE_TIME
                    else -> null
                }
                if (expectedClass != null) assertThat(profile.type).isEqualTo(expectedClass)
            }
        }
    }
}
