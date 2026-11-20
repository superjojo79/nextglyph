package nextglyph.engine.emoji

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EmojiTest {

    private val asset = sequenceOf("smileys_emotion\t😀\t1.0", "smileys_emotion\t🫠\t14.0", "", "flags\t🇫🇷\t2.0")

    @Nested
    inner class HappyPath {

        @Test
        fun `catalogue - groupes et émojis dans l'ordre du fichier`() {
            val catalog = EmojiCatalog.parse(asset)

            assertThat(catalog.groups.map { it.id }).containsExactly("smileys_emotion", "flags")
            assertThat(catalog.groups[0].emojis).containsExactly(Emoji("😀", 1f), Emoji("🫠", 14f))
            assertThat(catalog.groups[1].emojis.single().text).isEqualTo("🇫🇷")
        }

        @Test
        fun `filtre - garde les émojis acceptés, retire les groupes vides`() {
            val filtered = EmojiCatalog.parse(asset).filter { it.version < 14f && it.text != "🇫🇷" }

            assertThat(filtered.groups).containsExactly(EmojiGroup("smileys_emotion", listOf(Emoji("😀", 1f))))
        }

        @Test
        fun `récents - le plus récent en tête, sans doublon, aller-retour`() {
            val recents = EmojiRecents()
            recents.add("😀")
            recents.add("🇫🇷")
            recents.add("😀")

            assertThat(recents.list).containsExactly("😀", "🇫🇷")
            assertThat(recents.serialize()).isEqualTo("😀 🇫🇷")
            assertThat(EmojiRecents.parse(recents.serialize()).list).containsExactly("😀", "🇫🇷")
        }

        @Test
        fun `pages - nombre et contenu`() {
            val items = (1..10).toList()

            assertThat(EmojiPages.count(10, 4)).isEqualTo(3)
            assertThat(EmojiPages.page(items, 0, 4)).containsExactly(1, 2, 3, 4)
            assertThat(EmojiPages.page(items, 2, 4)).containsExactly(9, 10)
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `ligne mal formée ou version invalide refusée, avec le numéro de ligne`() {
            assertThatThrownBy { EmojiCatalog.parse(sequenceOf("a\t😀")) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Ligne 1")
            assertThatThrownBy { EmojiCatalog.parse(sequenceOf("a\t😀\t1.0", "\t😀\t1.0")) }.hasMessageContaining("Ligne 2")
            assertThatThrownBy { EmojiCatalog.parse(sequenceOf("a\t\t1.0")) }.hasMessageContaining("mal formée")
            assertThatThrownBy { EmojiCatalog.parse(sequenceOf("a\t😀\tx")) }.hasMessageContaining("version")
            assertThatThrownBy { EmojiCatalog.parse(sequenceOf("a\t😀\t1.0\textra")) }.hasMessageContaining("mal formée")
        }

        @Test
        fun `récents - émoji vide ou avec espace refusé, capacité nulle refusée`() {
            assertThatThrownBy { EmojiRecents().add("") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EmojiRecents().add("😀 😀") }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EmojiRecents(0) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `pages - taille ou page invalide refusée`() {
            assertThatThrownBy { EmojiPages.count(-1, 4) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { EmojiPages.count(3, 0) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `récents enregistrés absents ou vides - liste vide`() {
            assertThat(EmojiRecents.parse(null).list).isEmpty()
            assertThat(EmojiRecents.parse("").list).isEmpty()
            assertThat(EmojiRecents.parse("  ").list).isEmpty()
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `récents - capacité atteinte, le plus ancien est oublié`() {
            val recents = EmojiRecents(2)
            recents.add("a")
            recents.add("b")
            recents.add("c")

            assertThat(recents.list).containsExactly("c", "b")
            assertThat(EmojiRecents.parse("x y z", capacity = 2).list).containsExactly("x", "y")
        }

        @Test
        fun `pages - liste vide, page hors bornes, page pleine exacte`() {
            assertThat(EmojiPages.count(0, 4)).isEqualTo(1)
            assertThat(EmojiPages.page(emptyList<Int>(), 0, 4)).isEmpty()
            assertThat(EmojiPages.page((1..8).toList(), 5, 4)).containsExactly(5, 6, 7, 8)
            assertThat(EmojiPages.page((1..8).toList(), -1, 4)).containsExactly(1, 2, 3, 4)
            assertThat(EmojiPages.count(8, 4)).isEqualTo(2)
            assertThat(EmojiPages.count(9, 4)).isEqualTo(3)
        }

        @Test
        fun `catalogue vide`() {
            assertThat(EmojiCatalog.parse(emptySequence()).groups).isEmpty()
            assertThat(EmojiCatalog.parse(sequenceOf("a\t😀\t1.0")).filter { false }.groups).isEmpty()
        }
    }
}
