package nextglyph.engine.edit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AutoCapitalizationTest {

    private fun sentences(text: String, profile: TextProfile = TextProfile.TEXT) =
        AutoCapitalization.shouldCapitalize(text, profile, CapsMode.SENTENCES)

    @Nested
    inner class HappyPath {
        @ParameterizedTest
        @ValueSource(strings = ["Bonjour. ", "Quoi ? ", "Non ! ", "Bon… ", "Fin. ", "Fin ? ", "Fin.   "])
        fun `majuscule après un signe de fin de phrase suivi d'une espace`(text: String) {
            assertThat(sentences(text)).isTrue()
        }

        @ParameterizedTest
        @ValueSource(strings = ["Bonjour", "Bonjour,", "Bonjour, ", "Bonjour : ", "bonjour. m", "a, b"])
        fun `pas de majuscule au milieu d'une phrase`(text: String) {
            assertThat(sentences(text)).isFalse()
        }

        @Test
        fun `majuscule en début de champ et après un saut de ligne`() {
            assertThat(sentences("")).isTrue()
            assertThat(sentences("Bonjour\n")).isTrue()
            assertThat(sentences("Bonjour,\n  ")).isTrue()
        }

        @Test
        fun `les guillemets et parenthèses fermants s'intercalent`() {
            assertThat(sentences("Il a dit « oui » ")).isFalse()
            assertThat(sentences("Il a dit « oui ». ")).isTrue()
            assertThat(sentences("Il a dit « oui. » ")).isTrue()
            assertThat(sentences("(Fin.) ")).isTrue()
            assertThat(sentences("Non ?\" ")).isTrue()
        }

        @ParameterizedTest
        @ValueSource(strings = ["M. ", "Bonjour M. ", "Mme. ", "etc. ", "Voir e.g. ", "Voir E.G. ", "i.e. ", "Dr. ", "Prof. "])
        fun `pas de majuscule après une abréviation courante`(text: String) {
            assertThat(sentences(text)).isFalse()
        }

        @Test
        fun `majuscule après un point qui ne termine pas une abréviation`() {
            assertThat(sentences("Il est parti. ")).isTrue()
            assertThat(sentences("Mmmm. ")).isTrue()
            assertThat(sentences("... ")).isTrue()
            assertThat(sentences("Le 12. ")).isTrue()
        }

        @Test
        fun `le mode mots exige une espace ou le début du champ`() {
            assertThat(AutoCapitalization.shouldCapitalize("", TextProfile.TEXT, CapsMode.WORDS)).isTrue()
            assertThat(AutoCapitalization.shouldCapitalize("un ", TextProfile.TEXT, CapsMode.WORDS)).isTrue()
            assertThat(AutoCapitalization.shouldCapitalize("un ", TextProfile.TEXT, CapsMode.WORDS)).isTrue()
            assertThat(AutoCapitalization.shouldCapitalize("un\n", TextProfile.TEXT, CapsMode.WORDS)).isTrue()
            assertThat(AutoCapitalization.shouldCapitalize("un", TextProfile.TEXT, CapsMode.WORDS)).isFalse()
        }

        @Test
        fun `le mode caractères est toujours actif`() {
            assertThat(AutoCapitalization.shouldCapitalize("abc", TextProfile.TEXT, CapsMode.CHARACTERS)).isTrue()
            assertThat(AutoCapitalization.shouldCapitalize("", TextProfile.PERSON_NAME, CapsMode.CHARACTERS)).isTrue()
        }

        @Test
        fun `le mode aucun n'active jamais`() {
            assertThat(AutoCapitalization.shouldCapitalize("", TextProfile.TEXT, CapsMode.NONE)).isFalse()
            assertThat(AutoCapitalization.shouldCapitalize("A. ", TextProfile.TEXT, CapsMode.NONE)).isFalse()
        }

        @Test
        fun `l'état manuel l'emporte sur l'automatique`() {
            val once = AutoCapitalization.shiftFor("abc", TextProfile.TEXT, CapsMode.SENTENCES, ShiftState.ONCE)
            val locked = AutoCapitalization.shiftFor("A. ", TextProfile.TEXT, CapsMode.SENTENCES, ShiftState.LOCKED)

            assertThat(once).isEqualTo(ShiftState.ONCE)
            assertThat(locked).isEqualTo(ShiftState.LOCKED)
        }

        @Test
        fun `sans état manuel la majuscule automatique donne une lettre`() {
            assertThat(AutoCapitalization.shiftFor("", TextProfile.TEXT, CapsMode.SENTENCES, ShiftState.OFF)).isEqualTo(ShiftState.ONCE)
            assertThat(AutoCapitalization.shiftFor("abc", TextProfile.TEXT, CapsMode.SENTENCES, ShiftState.OFF)).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `une liste d'abréviations personnalisée est respectée`() {
            val config = EditConfig(abbreviations = setOf("tel"))

            assertThat(AutoCapitalization.shouldCapitalize("Tel. ", TextProfile.TEXT, CapsMode.SENTENCES, config)).isFalse()
            assertThat(AutoCapitalization.shouldCapitalize("M. ", TextProfile.TEXT, CapsMode.SENTENCES, config)).isTrue()
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @ValueSource(strings = ["EMAIL", "URL", "PASSWORD"])
        fun `aucune majuscule automatique dans les profils sans typographie`(profile: String) {
            val p = TextProfile.valueOf(profile)

            for (mode in CapsMode.entries) {
                assertThat(AutoCapitalization.shouldCapitalize("", p, mode)).isFalse()
                assertThat(AutoCapitalization.shouldCapitalize("Fin. ", p, mode)).isFalse()
            }
        }

        @Test
        fun `l'état manuel reste possible dans un champ e-mail`() {
            val shift = AutoCapitalization.shiftFor("", TextProfile.EMAIL, CapsMode.SENTENCES, ShiftState.ONCE)

            assertThat(shift).isEqualTo(ShiftState.ONCE)
            assertThat(AutoCapitalization.shiftFor("", TextProfile.EMAIL, CapsMode.SENTENCES, ShiftState.OFF)).isEqualTo(ShiftState.OFF)
        }

        @Test
        fun `un signe sans espace ne déclenche pas la majuscule`() {
            assertThat(sentences("Fin.")).isFalse()
            assertThat(sentences("Fin?")).isFalse()
            assertThat(sentences("www.exemple.fr")).isFalse()
        }

        @Test
        fun `un point après une espace seule ne termine rien`() {
            assertThat(sentences("a . ")).isTrue()
            assertThat(sentences("a , ")).isFalse()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `champ réduit à des espaces`() {
            assertThat(sentences(" ")).isTrue()
            assertThat(sentences("  ")).isTrue()
        }

        @Test
        fun `uniquement des guillemets fermants avant une espace`() {
            assertThat(sentences("\" ")).isFalse()
            assertThat(sentences(") ")).isFalse()
        }

        @Test
        fun `un point précédé d'un emoji hors BMP n'est pas une abréviation`() {
            assertThat(sentences("😀. ")).isTrue()
            assertThat(sentences("a😀. ")).isTrue()
        }

        @Test
        fun `emoji avant le curseur sans ponctuation`() {
            assertThat(sentences("Super 😀 ")).isFalse()
            assertThat(sentences("😀")).isFalse()
        }

        @Test
        fun `une abréviation précédée de lettres n'en est plus une`() {
            assertThat(sentences("Bonjour Mx. ")).isTrue()
            assertThat(sentences("Mme. ")).isFalse()
        }

        @Test
        fun `le point seul au début d'une abréviation vide`() {
            assertThat(sentences(". ")).isTrue()
            assertThat(sentences("a.. ")).isTrue()
        }

        @Test
        fun `la constante de lecture couvre une abréviation`() {
            assertThat(AutoCapitalization.LOOKBACK).isEqualTo(64)
        }

        @Test
        fun `les abréviations sont reconnues en majuscules et minuscules`() {
            assertThat(sentences("ETC. ")).isFalse()
            assertThat(sentences("m. ")).isFalse()
        }

        @Test
        fun `un point entre deux lettres isolées fait partie de l'abréviation`() {
            assertThat(sentences("u.s. ")).isFalse()
            assertThat(sentences("x.y. ")).isTrue()
        }
    }
}
