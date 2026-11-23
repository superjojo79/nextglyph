package nextglyph.engine.ngram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NextWordContextTest {

    private val start = NgramModel.START

    private fun read(text: String?, elisions: Boolean = false, lookback: Int = NextWordContext.LOOKBACK) =
        NextWordContext.read(text, elisions, lookback)

    @Nested
    inner class HappyPath {
        @Test
        fun `un champ vide commence une phrase`() {
            assertThat(read("")).containsExactly(start)
        }

        @Test
        fun `après le premier mot, le contexte est le début de phrase et ce mot`() {
            assertThat(read("Bonjour ")).containsExactly(start, "Bonjour")
        }

        @Test
        fun `après deux mots ou plus, le contexte est formé des deux derniers`() {
            assertThat(read("Je suis ")).containsExactly("Je", "suis")
            assertThat(read("Aujourd'hui je suis très ")).containsExactly("suis", "très")
        }

        @Test
        fun `après une fin de phrase, la phrase suivante commence`() {
            for (end in listOf(". ", "! ", "? ", "… ", ".\n", "\n")) {
                assertThat(read("Il dort$end")).`as`(end).containsExactly(start)
            }
        }

        @Test
        fun `seuls les mots de la phrase en cours comptent`() {
            assertThat(read("Il dort. Elle ")).containsExactly(start, "Elle")
            assertThat(read("Il dort. Elle lit ")).containsExactly("Elle", "lit")
        }

        @Test
        fun `une virgule ou un point-virgule limite le contexte au dernier mot`() {
            assertThat(read("Oui, bien ")).containsExactly("bien")
            assertThat(read("Je pense; donc ")).containsExactly("donc")
            assertThat(read("Je suis, ")).containsExactly("suis")
            assertThat(read("Voici : ")).containsExactly("Voici")
            assertThat(read("Oui, ")).containsExactly("Oui")
        }

        @Test
        fun `un signe ouvrant recommence le contexte`() {
            for (text in listOf("« ", "Il dit « ", "Il dit \" ", "( ", "Il ( ", "Il dit - ", "Il dit — ")) {
                assertThat(read(text)).`as`(text).containsExactly(start)
            }
        }

        @Test
        fun `en français, l'élision est un mot à part`() {
            assertThat(read("J'ai ", elisions = true)).containsExactly("J'", "ai")
            assertThat(read("Il aime l’école ", elisions = true)).containsExactly("l'", "école")
            assertThat(read("Il aime l'", elisions = true)).containsExactly("aime", "l'")
            assertThat(read("J'", elisions = true)).containsExactly(start, "J'")
            assertThat(read("qu'", elisions = true)).containsExactly(start, "qu'")
            assertThat(read("jusqu’", elisions = true)).containsExactly(start, "jusqu'")
        }

        @Test
        fun `sans élisions, l'apostrophe reste dans le mot`() {
            assertThat(read("I don't ")).containsExactly("I", "don't")
            assertThat(read("Don’t ")).containsExactly(start, "Don't")
            assertThat(read("J'ai ")).containsExactly(start, "J'ai")
            assertThat(read("peut-être ")).containsExactly(start, "peut-être")
        }

        @Test
        fun `les espaces insécables comptent comme des espaces`() {
            assertThat(read("Bonjour ")).containsExactly(start, "Bonjour")
            assertThat(read("Bonjour ")).containsExactly(start, "Bonjour")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `un texte indisponible ne donne pas de contexte`() {
            assertThat(read(null)).isNull()
        }

        @Test
        fun `un curseur dans un mot ne donne pas de contexte`() {
            assertThat(read("Bon")).isNull()
            assertThat(read("Je su")).isNull()
            assertThat(read("Bonjour,")).isNull()
            assertThat(read("Fin.")).isNull()
        }

        @Test
        fun `un chiffre juste avant le curseur ne donne pas de contexte`() {
            assertThat(read("Vers 5 ")).isNull()
            assertThat(read("Il a 3D ")).isNull()
        }

        @Test
        fun `un chiffre plus loin casse seulement le trigramme`() {
            assertThat(read("a 3 b ")).containsExactly("b")
        }

        @Test
        fun `une apostrophe finale hors élision française ne donne pas de contexte`() {
            assertThat(read("aujourd'", elisions = true)).isNull()
            assertThat(read("l'", elisions = false)).isNull()
            assertThat(read("'", elisions = true)).isNull()
            assertThat(read("peut-l'", elisions = true)).isNull()
            assertThat(read("xl'", elisions = true)).isNull()
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `un texte fait d'espaces seulement commence une phrase`() {
            assertThat(read("   ")).containsExactly(start)
            assertThat(read(" ")).containsExactly(start)
        }

        @Test
        fun `exactement deux mots donnent un trigramme ordinaire`() {
            assertThat(read("a b ")).containsExactly("a", "b")
            assertThat(read("a b c ")).containsExactly("b", "c")
        }

        @Test
        fun `une fenêtre pleine sans fin de phrase ne garantit pas le début de phrase`() {
            assertThat(read("abc ", lookback = 4)).containsExactly("abc")
            assertThat(read("abc ", lookback = 5)).containsExactly(start, "abc")
            assertThat(read("abc ", lookback = 3)).containsExactly("abc")
        }

        @Test
        fun `une fin de phrase dans la fenêtre garantit le début de phrase`() {
            assertThat(read("x. abc ", lookback = 3)).containsExactly(start, "abc")
        }

        @Test
        fun `un mot de la phrase déjà coupé par la virgule ne reçoit pas le début de phrase`() {
            assertThat(read("Oui, ")).containsExactly("Oui")
        }

        @Test
        fun `le signe trouvé avant un mot sépare les contextes`() {
            assertThat(read("a (b ")).containsExactly("b")
            assertThat(read("a b ")).containsExactly("a", "b")
        }

        @Test
        fun `un mot de deux lettres élidable juste avant le curseur`() {
            assertThat(read("Il a lu qu'", elisions = true)).containsExactly("lu", "qu'")
            assertThat(read("Il a lu c'", elisions = true)).containsExactly("lu", "c'")
            assertThat(read("Il a lu x'", elisions = true)).isNull()
        }
    }
}
