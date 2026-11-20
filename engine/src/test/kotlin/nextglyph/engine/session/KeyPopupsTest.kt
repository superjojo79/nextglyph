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
        fun `indices - premier symbole voisin, majuscule verrouillée sur ABC, menu des symboles`() {
            assertThat(KeyPopups.hint(Key.character('-'))).isEqualTo("_")
            assertThat(KeyPopups.hint(Key.character('+'))).isEqualTo("=")
            assertThat(KeyPopups.hint(Key(KeyKind.LETTERS, "ABC", 1f))).isEqualTo("⇪")
            assertThat(KeyPopups.hint(Key(KeyKind.SYMBOLS, "?123", 1f))).isEqualTo("…")
            assertThat(KeyPopups.hint(Key(KeyKind.SYMBOLS, "?123", 1f), SymbolsMenu.BACK_FROM_CLASSIC)).isEqualTo("…")
        }

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
        fun `profil texte - la ponctuation rapide puis l'échappatoire Classique en dernière case`() {
            val items = KeyPopups.forKey(Key(KeyKind.SYMBOLS, "?123", 1f), false, bilingual, SymbolsMenu.QUICK_PUNCTUATION_AND_CLASSIC)

            assertThat(items).hasSize(8)
            assertThat(items.last()).isEqualTo(PopupItem("Classique", PopupAction.ClassicLayout(true)))
            assertThat(items.dropLast(1).map { it.label }).containsExactly("-", "(", ")", "\"", ";", "@", "#")
        }

        @Test
        fun `tables des menus et contenu des actions`() {
            val classic = KeyPopups.forKey(Key(KeyKind.SYMBOLS, "?123", 1f), false, bilingual, SymbolsMenu.QUICK_PUNCTUATION_AND_CLASSIC)
            val back = KeyPopups.forKey(Key(KeyKind.SYMBOLS, "?123", 1f), false, bilingual, SymbolsMenu.BACK_FROM_CLASSIC)

            assertThat((classic.last().action as PopupAction.ClassicLayout).enabled).isTrue()
            assertThat((back.single().action as PopupAction.ClassicLayout).enabled).isFalse()
            assertThat((classic.first().action as PopupAction.Text).text).isEqualTo("-")
            assertThat(PopupAction.LockLanguage("fr").language).isEqualTo("fr")
            assertThat(KeyPopups.ACCENTS).containsKey('e')
            assertThat(KeyPopups.QUICK_PUNCTUATION).hasSize(7)
            assertThat(KeyPopups.SYMBOLS).containsKey("-")
        }

        @Test
        fun `disposition classique - une seule case pour revenir`() {
            val items = KeyPopups.forKey(Key(KeyKind.SYMBOLS, "?123", 1.5f), false, bilingual, SymbolsMenu.BACK_FROM_CLASSIC)

            assertThat(items).containsExactly(PopupItem("Adaptative", PopupAction.ClassicLayout(false)))
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
        @Test
        fun `pas d'indice - lettres, symbole sans voisin, touches de fonction`() {
            assertThat(KeyPopups.hint(Key.letter('e'))).isNull()
            assertThat(KeyPopups.hint(Key.character('@'))).isNull()
            assertThat(KeyPopups.hint(Key(KeyKind.SPACE, "", 1.6f))).isNull()
            assertThat(KeyPopups.hint(Key(KeyKind.BACKSPACE, "⌫", 1f))).isNull()
        }

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
        fun `disposition du mot de passe - pas de menu sur la touche des symboles`() {
            assertThat(KeyPopups.forKey(Key(KeyKind.SYMBOLS, "?123", 1.5f), false, bilingual, SymbolsMenu.NONE)).isEmpty()
        }

        @Test
        fun `menu vide - aucune case visée`() {
            assertThat(KeyPopups.indexAt(10f, 0f, 20f, 0)).isEqualTo(-1)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `touche des symboles sans menu (mot de passe) - pas d'indice`() {
            assertThat(KeyPopups.hint(Key(KeyKind.SYMBOLS, "?123", 1.5f), SymbolsMenu.NONE)).isNull()
        }

        @Test
        fun `chaque symbole voisin a un indice égal à son premier voisin`() {
            for ((symbol, neighbours) in KeyPopups.SYMBOLS) {
                assertThat(KeyPopups.hint(Key.character(symbol[0]))).isEqualTo(neighbours.first())
            }
        }

        @Test
        fun `une seule langue - pas de menu sur l'espace`() {
            assertThat(KeyPopups.forKey(Key(KeyKind.SPACE, "", 1.6f), false, listOf("fr"))).isEmpty()
        }

        @Test
        fun `le menu de la touche des symboles ne concerne pas les autres touches`() {
            for (menu in SymbolsMenu.entries) {
                assertThat(KeyPopups.forKey(Key.letter('e'), false, bilingual, menu)).hasSize(4)
                assertThat(KeyPopups.forKey(Key(KeyKind.ENTER, "↵", 1f), false, bilingual, menu)).isEmpty()
            }
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
