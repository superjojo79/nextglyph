package nextglyph.engine.session

import nextglyph.engine.layout.Key
import nextglyph.engine.layout.KeyKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyPopupsTest {

    private val bilingual = listOf("fr", "en")

    @Nested
    inner class HappyPath {
        @Test
        fun `appui long sur e et c - variantes accentuées`() {
            assertThat(KeyPopups.forKey(Key.letter('e'), false, bilingual).map { it.label }).containsExactly("é", "è", "ê", "ë")
            assertThat(KeyPopups.forKey(Key.letter('c'), false, bilingual))
                .containsExactly(PopupItem("ç", PopupAction.Text("ç")))
        }

        @Test
        fun `variantes en majuscules quand la majuscule est active`() {
            assertThat(KeyPopups.forKey(Key.letter('e'), true, bilingual).map { it.action })
                .containsExactly(PopupAction.Text("É"), PopupAction.Text("È"), PopupAction.Text("Ê"), PopupAction.Text("Ë"))
        }

        @Test
        fun `appui long sur la touche des symboles - ponctuation rapide`() {
            val items = KeyPopups.forKey(Key(KeyKind.SYMBOLS, "?123", 1f), false, bilingual)

            assertThat(items.map { it.label }).containsExactly("-", "(", ")", "\"", ";", "@", "#")
        }

        @Test
        fun `appui long sur l'espace - verrouiller une langue ou mode automatique`() {
            val items = KeyPopups.forKey(Key(KeyKind.SPACE, "", 1.6f), false, bilingual)

            assertThat(items).containsExactly(
                PopupItem("FR", PopupAction.LockLanguage("fr")),
                PopupItem("EN", PopupAction.LockLanguage("en")),
                PopupItem("Auto", PopupAction.LockLanguage(null)),
            )
        }

        @Test
        fun `symboles voisins et majuscule verrouillée depuis ABC`() {
            assertThat(KeyPopups.forKey(Key.character('-'), false, bilingual).map { it.label }).containsExactly("_", "–", "—")
            assertThat(KeyPopups.forKey(Key(KeyKind.LETTERS, "ABC", 1f), false, bilingual))
                .containsExactly(PopupItem("⇪", PopupAction.CapsLock))
        }

        @Test
        fun `menu centré sur la touche et case visée`() {
            assertThat(KeyPopups.popupLeft(100f, 20f, 3, 400f)).isEqualTo(70f)
            assertThat(KeyPopups.indexAt(75f, 70f, 20f, 3)).isEqualTo(0)
            assertThat(KeyPopups.indexAt(95f, 70f, 20f, 3)).isEqualTo(1)
            assertThat(KeyPopups.indexAt(129f, 70f, 20f, 3)).isEqualTo(2)
        }
    }

    @Nested
    inner class ErrorCases {
        @ParameterizedTest
        @ValueSource(chars = ['z', 'b', 'q'])
        fun `consonne sans variante - pas de menu`(letter: Char) {
            assertThat(KeyPopups.forKey(Key.letter(letter), false, bilingual)).isEmpty()
        }

        @Test
        fun `touches sans menu`() {
            assertThat(KeyPopups.forKey(Key(KeyKind.BACKSPACE, "⌫", 1f), false, bilingual)).isEmpty()
            assertThat(KeyPopups.forKey(Key(KeyKind.ENTER, "↵", 1f), false, bilingual)).isEmpty()
            assertThat(KeyPopups.forKey(Key.character('1'), false, bilingual)).isEmpty()
        }

        @Test
        fun `menu vide - aucune case visée`() {
            assertThat(KeyPopups.indexAt(10f, 0f, 20f, 0)).isEqualTo(-1)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `une seule langue - pas de menu sur l'espace`() {
            assertThat(KeyPopups.forKey(Key(KeyKind.SPACE, "", 1.6f), false, listOf("fr"))).isEmpty()
        }

        @Test
        fun `menu contre les bords de la vue`() {
            assertThat(KeyPopups.popupLeft(5f, 20f, 3, 400f)).isEqualTo(0f)
            assertThat(KeyPopups.popupLeft(395f, 20f, 3, 400f)).isEqualTo(340f)
            assertThat(KeyPopups.popupLeft(50f, 100f, 5, 300f)).isEqualTo(0f)
        }

        @Test
        fun `case visée bornée aux extrémités`() {
            assertThat(KeyPopups.indexAt(-50f, 70f, 20f, 3)).isEqualTo(0)
            assertThat(KeyPopups.indexAt(500f, 70f, 20f, 3)).isEqualTo(2)
            assertThat(KeyPopups.indexAt(90f, 70f, 20f, 3)).isEqualTo(1)
        }
    }
}
