package nextglyph.ime.view

import nextglyph.engine.emoji.EmojiCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EmojiPanelTest {

    // Deux groupes : 30 visages (2 pages de 24) et 2 animaux. Onglets : ABC, 🕘, 😀, 🐶, ⌫.
    private val catalog = EmojiCatalog.parse(
        (0 until 30).map { "smileys\t${'a' + it % 26}${it / 26}\t1.0" }.toMutableList().apply {
            this[0] = "smileys\t😀\t1.0"
        }.plus(listOf("animals\t🐶\t1.0", "animals\t🐱\t1.0")).asSequence(),
    )

    /** Panneau de 400 × (40 + 120) px : onglets de 80 px, cases de 50 × 40 px. */
    private fun panel(recents: List<String> = emptyList()) = EmojiPanel(catalog).apply {
        this.recents = recents
        open()
        layout(400f, 40f, 120f)
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `onglets - ABC, récents, premier émoji de chaque groupe, effacement`() {
            assertThat(panel().tabLabels).containsExactly("ABC", "🕘", "😀", "🐶", "⌫")
        }

        @Test
        fun `ouverture sur les récents quand il y en a`() {
            val panel = panel(listOf("👍"))

            assertThat(panel.tab).isEqualTo(0)
            assertThat(panel.pageItems()).containsExactly("👍")
        }

        @Test
        fun `tap sur une case - insertion de l'émoji`() {
            val panel = panel()
            panel.down(10f, 50f)

            assertThat(panel.up(10f, 50f)).isEqualTo(EmojiPanel.Action.Insert("😀"))
        }

        @Test
        fun `onglets ABC et effacement - leurs actions, onglet de groupe - changement`() {
            val panel = panel()

            assertThat(panel.tap(10f, 10f)).isEqualTo(EmojiPanel.Action.Letters)
            assertThat(panel.tap(390f, 10f)).isEqualTo(EmojiPanel.Action.Backspace)
            assertThat(panel.tap(250f, 10f)).isNull()
            assertThat(panel.tab).isEqualTo(2)
            assertThat(panel.pageItems()).containsExactly("🐶", "🐱")
        }

        @Test
        fun `glisser vers la gauche - page suivante`() {
            val panel = panel()
            panel.down(300f, 80f)

            assertThat(panel.up(100f, 90f)).isNull()
            assertThat(panel.page).isEqualTo(1)
            assertThat(panel.pageItems()).hasSize(6)
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `récents vides - ouverture sur le premier groupe`() {
            val panel = panel()

            assertThat(panel.tab).isEqualTo(1)
            assertThat(panel.pageCount()).isEqualTo(2)
        }

        @Test
        fun `case vide de la dernière page - aucune action`() {
            val panel = panel()
            panel.down(300f, 80f)
            panel.up(100f, 80f)

            assertThat(panel.cellAt(390f, 150f)).isEqualTo(-1)
            assertThat(panel.tap(390f, 150f)).isNull()
        }

        @Test
        fun `panneau sans taille - ni onglet ni case`() {
            val panel = EmojiPanel(catalog)

            assertThat(panel.tabAt(10f, 10f)).isEqualTo(-1)
            assertThat(panel.cellAt(10f, 10f)).isEqualTo(-1)
        }

        @Test
        fun `glissement surtout vertical - tap au point d'appui, pas de changement de page`() {
            val panel = panel()
            panel.down(200f, 45f)

            assertThat(panel.up(100f, 159f)).isEqualTo(EmojiPanel.Action.Insert(panel.pageItems()[4]))
            assertThat(panel.page).isEqualTo(0)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `glissement trop court - tap au point d'appui`() {
            val panel = panel()
            panel.down(100f, 50f)

            assertThat(panel.up(20f, 50f)).isEqualTo(EmojiPanel.Action.Insert(panel.pageItems()[2]))
            assertThat(panel.page).isEqualTo(0)
        }

        @Test
        fun `pages bornées - pas avant la première ni après la dernière`() {
            val panel = panel()
            panel.down(100f, 80f)
            panel.up(300f, 80f)
            assertThat(panel.page).isEqualTo(0)
            repeat(3) {
                panel.down(300f, 80f)
                panel.up(100f, 80f)
            }
            assertThat(panel.page).isEqualTo(1)
        }

        @Test
        fun `glisser depuis les onglets - pas de changement de page`() {
            val panel = panel()
            panel.down(300f, 10f)

            panel.up(100f, 10f)

            assertThat(panel.page).isEqualTo(0)
        }

        @Test
        fun `bords de la grille et des onglets`() {
            val panel = panel()

            assertThat(panel.tabAt(399f, 39f)).isEqualTo(4)
            assertThat(panel.tabAt(10f, 40f)).isEqualTo(-1)
            assertThat(panel.cellAt(0f, 40f)).isEqualTo(0)
            assertThat(panel.cellAt(399f, 159f)).isEqualTo(23)
            assertThat(panel.cellAt(10f, 160f)).isEqualTo(-1)
            assertThat(panel.cellAt(399f, 79f)).isEqualTo(7)
        }

        @Test
        fun `catalogue vide - ouverture sur les récents, même vides`() {
            val panel = EmojiPanel(EmojiCatalog(emptyList())).apply { open() }

            assertThat(panel.tab).isEqualTo(0)
            assertThat(panel.items()).isEmpty()
            assertThat(panel.pageCount()).isEqualTo(1)
        }
    }
}
