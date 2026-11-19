package nextglyph.tools.wordlists

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TokenizerTest {

    private val fr = Tokenizer(Language.FR)
    private val en = Tokenizer(Language.EN)

    @Nested
    inner class HappyPath {
        @Test
        fun `français - une élision est un mot séparé du mot élidé`() {
            assertThat(fr.tokens("J'ai vu l'école, qu'il aime."))
                .containsExactly("J'", "ai", "vu", "l'", "école", "qu'", "il", "aime")
        }

        @Test
        fun `français - aujourd'hui et les traits d'union restent un seul mot`() {
            assertThat(fr.tokens("Aujourd'hui, peut-être ? Est-ce vrai"))
                .containsExactly("Aujourd'hui", "peut-être", "Est-ce", "vrai")
        }

        @Test
        fun `français - élisions longues et enchaînées`() {
            assertThat(fr.tokens("jusqu'à lorsqu'il puisqu'on quoiqu'elle qu'l'on c'est-à-dire"))
                .containsExactly("jusqu'", "à", "lorsqu'", "il", "puisqu'", "on", "quoiqu'", "elle", "qu'", "l'", "on", "c'", "est-à-dire")
        }

        @Test
        fun `français - les huit élisions courtes`() {
            assertThat(fr.tokens("c'a d'a j'a l'a m'a n'a s'a t'a"))
                .containsExactly("c'", "a", "d'", "a", "j'", "a", "l'", "a", "m'", "a", "n'", "a", "s'", "a", "t'", "a")
        }

        @Test
        fun `anglais - les contractions et possessifs restent entiers`() {
            assertThat(en.tokens("Don't stop Tom's rock'n'roll, I'm well-known."))
                .containsExactly("Don't", "stop", "Tom's", "rock'n'roll", "I'm", "well-known")
        }

        @Test
        fun `anglais - aucune coupure d'élision`() {
            assertThat(en.tokens("l'école")).containsExactly("l'école")
        }

        @Test
        fun `les apostrophes typographiques et traits d'union insécables sont normalisés`() {
            assertThat(en.tokens("don’t ʼtis‑ok")).containsExactly("don't", "tis-ok")
            assertThat(fr.tokens("L’homme qu’il")).containsExactly("L'", "homme", "qu'", "il")
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `une suite contenant un chiffre est ignorée`() {
            assertThat(en.tokens("3D b2b 42 ok 7up")).containsExactly("ok")
            assertThat(fr.tokens("l'an2000 oui")).containsExactly("oui")
        }

        @Test
        fun `une ponctuation seule ne donne aucun mot`() {
            assertThat(en.tokens("... -- ' - ,;!?  ")).isEmpty()
        }

        @Test
        fun `apostrophes et traits d'union non encadrés de lettres sont écartés`() {
            assertThat(en.tokens("-hello- 'quoted' dogs' a--b")).containsExactly("hello", "quoted", "dogs", "a", "b")
        }

        @Test
        fun `un préfixe qui n'est pas une élision n'est pas coupé`() {
            assertThat(fr.tokens("prud'homme quelqu'un x'y")).containsExactly("prud'homme", "quelqu'un", "x'y")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `texte vide ou blanc`() {
            assertThat(fr.tokens("")).isEmpty()
            assertThat(en.tokens("   ")).isEmpty()
        }

        @Test
        fun `une seule lettre est un mot`() {
            assertThat(en.tokens("a")).containsExactly("a")
        }

        @Test
        fun `une élision en fin de mot n'est pas coupée en deux mots vides`() {
            assertThat(fr.tokens("l'")).containsExactly("l")
            assertThat(fr.tokens("l'a")).containsExactly("l'", "a")
        }

        @Test
        fun `la casse est conservée et les accents combinants restent dans le mot`() {
            assertThat(fr.tokens("Éte ÉTÉ")).containsExactly("Éte", "ÉTÉ")
        }

        @Test
        fun `un mot en fin de texte est émis`() {
            assertThat(en.tokens("fin")).containsExactly("fin")
            assertThat(en.tokens("un deux")).containsExactly("un", "deux")
        }

        @Test
        fun `les codes de langue Tatoeba sont les codes ISO 639-3`() {
            assertThat(Language.FR.tatoebaCode).isEqualTo("fra")
            assertThat(Language.EN.tatoebaCode).isEqualTo("eng")
            assertThat(Language.FR.code).isEqualTo("fr")
            assertThat(Language.EN.code).isEqualTo("en")
        }
    }
}
