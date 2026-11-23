package nextglyph.engine.stats

import nextglyph.engine.layout.KeyKind
import nextglyph.engine.predict.FieldProfile
import nextglyph.engine.predict.PredictionSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KeyHeatmapTest {

    private val text = PredictionSettings()
    private val incognito = PredictionSettings(incognito = true)
    private val password = PredictionSettings(profile = FieldProfile.PASSWORD)
    private val numeric = PredictionSettings(profile = FieldProfile.NUMERIC)

    /** Forme enregistrée dont le premier bloc est [first] (champs séparés par des virgules) et les autres nuls. */
    private fun stored(first: List<Int>): String =
        (listOf("h1", first.joinToString(",")) + List(29) { List(50) { 0 }.joinToString(",") }).joinToString(";")

    private fun KeyHeatmap.heat(letter: Char) = keys().first { it.letter == letter }
    private fun KeyHeatmap.heat(kind: KeyKind) = keys().first { it.kind == kind }

    @Nested
    inner class HappyPath {
        @Test
        fun `une frappe incrémente les frappes de la touche et la bonne case`() {
            val map = KeyHeatmap()

            val counted = map.onTap('e', -0.45, -0.45, text)

            assertThat(counted).isTrue()
            assertThat(map.heat('e').taps).isEqualTo(1)
            assertThat(map.heat('e').cells[0]).isEqualTo(1)
            assertThat(map.heat('e').cells.sum()).isEqualTo(1)
            assertThat(map.heat('a').taps).isZero()
            assertThat(map.changed).isTrue()
            assertThat(map.isEmpty).isFalse()
        }

        @Test
        fun `la case dépend de dx en colonnes et de dy en rangées`() {
            val map = KeyHeatmap()

            map.onTap('a', 0.0, 0.0, text)
            map.onTap('b', 0.3, -0.3, text)

            assertThat(map.heat('a').cells[3 * KeyHeatmap.COLS + 4]).isEqualTo(1)
            assertThat(map.heat('b').cells[1 * KeyHeatmap.COLS + 6]).isEqualTo(1)
        }

        @Test
        fun `les touches de fonction sont comptées à leur place`() {
            val map = KeyHeatmap()

            assertThat(map.onTap(KeyKind.SPACE, 0.1, 0.1, text)).isTrue()
            assertThat(map.onTap(KeyKind.ENTER, 0.1, 0.1, text)).isTrue()
            assertThat(map.onTap(KeyKind.BACKSPACE, 0.1, 0.1, text)).isTrue()
            assertThat(map.onTap(KeyKind.SYMBOLS, 0.1, 0.1, text)).isTrue()

            assertThat(map.keys().filter { it.letter == null }.map { it.kind to it.taps })
                .containsExactly(KeyKind.SPACE to 1, KeyKind.ENTER to 1, KeyKind.BACKSPACE to 1, KeyKind.SYMBOLS to 1)
            assertThat(map.keys()).hasSize(30)
            assertThat(map.keys().take(26).map { it.letter }).containsExactlyElementsOf(('a'..'z').toList())
        }

        @Test
        fun `un effacement compte une erreur pour la dernière touche tapée`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)
            map.onTap('b', 0.0, 0.0, text)

            map.onErase()

            assertThat(map.heat('b').errors).isEqualTo(1)
            assertThat(map.heat('a').errors).isZero()
            map.onErase()
            assertThat(map.heat('a').errors).isEqualTo(1)
        }

        @Test
        fun `un marqueur sans position absorbe un effacement sans erreur`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)
            map.onUntracked()

            map.onErase()

            assertThat(map.keys().sumOf { it.errors }).isZero()
            map.onErase()
            assertThat(map.heat('a').errors).isEqualTo(1)
        }

        @Test
        fun `oublier les dernières frappes évite toute erreur`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)

            map.forgetRecent()
            map.onErase()

            assertThat(map.keys().sumOf { it.errors }).isZero()
        }

        @Test
        fun `la frappe sur retour arrière ou symboles n'est pas empilée`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)
            map.onTap(KeyKind.BACKSPACE, 0.0, 0.0, text)
            map.onTap(KeyKind.SYMBOLS, 0.0, 0.0, text)

            map.onErase()

            assertThat(map.heat('a').errors).isEqualTo(1)
            assertThat(map.heat(KeyKind.BACKSPACE).errors).isZero()
            assertThat(map.heat(KeyKind.SYMBOLS).errors).isZero()
        }

        @Test
        fun `espace et entrée sont empilées`() {
            val map = KeyHeatmap()
            map.onTap(KeyKind.SPACE, 0.0, 0.0, text)
            map.onTap(KeyKind.ENTER, 0.0, 0.0, text)

            map.onErase()
            map.onErase()

            assertThat(map.heat(KeyKind.ENTER).errors).isEqualTo(1)
            assertThat(map.heat(KeyKind.SPACE).errors).isEqualTo(1)
        }

        @Test
        fun `le taux d'erreur est calculé à partir de 20 frappes`() {
            val map = KeyHeatmap()
            repeat(20) { map.onTap('e', 0.0, 0.0, text) }
            repeat(5) {
                map.onTap('e', 0.0, 0.0, text)
                map.onErase()
            }

            val e = map.heat('e')
            assertThat(e.taps).isEqualTo(25)
            assertThat(e.errorRate).isEqualTo(0.2)
        }

        @Test
        fun `enregistrement puis relecture identiques`() {
            val map = KeyHeatmap()
            map.onTap('e', 0.2, -0.1, text)
            map.onTap(KeyKind.SPACE, -0.3, 0.3, text)
            map.onErase()

            val back = KeyHeatmap.parse(map.serialize())

            assertThat(back.serialize()).isEqualTo(map.serialize())
            assertThat(back.heat('e').taps).isEqualTo(1)
            assertThat(back.heat(KeyKind.SPACE).errors).isEqualTo(1)
            assertThat(back.heat('e').cells).isEqualTo(map.heat('e').cells)
        }

        @Test
        fun `la forme enregistrée ne dépend pas de l'ordre des frappes`() {
            val first = KeyHeatmap()
            first.onTap('a', 0.1, 0.1, text)
            first.onTap('b', -0.2, 0.2, text)
            first.onTap(KeyKind.SPACE, 0.0, 0.0, text)
            val second = KeyHeatmap()
            second.onTap(KeyKind.SPACE, 0.0, 0.0, text)
            second.onTap('b', -0.2, 0.2, text)
            second.onTap('a', 0.1, 0.1, text)

            assertThat(first.serialize()).isEqualTo(second.serialize())
        }

        @Test
        fun `la forme enregistrée ne contient que les compteurs`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)

            val parts = map.serialize().split(";")

            assertThat(parts).hasSize(31)
            assertThat(parts[0]).isEqualTo("h1")
            assertThat(parts[1].split(",")).hasSize(2 + KeyHeatmap.COLS * KeyHeatmap.ROWS)
            assertThat(map.serialize()).matches("[h0-9,;]+")
        }

        @Test
        fun `remises à zéro séparées de la densité et des erreurs`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)
            map.onErase()
            map.markSaved()

            map.resetErrors()

            assertThat(map.changed).isTrue()
            assertThat(map.heat('a').errors).isZero()
            assertThat(map.heat('a').taps).isEqualTo(1)
            map.onTap('a', 0.0, 0.0, text)
            map.onErase()
            map.resetDensity()
            assertThat(map.heat('a').taps).isZero()
            assertThat(map.heat('a').cells.sum()).isZero()
            assertThat(map.heat('a').errors).isEqualTo(1)
            map.reset()
            assertThat(map.heat('a').errors).isZero()
            assertThat(map.isEmpty).isTrue()
            assertThat(map.maxCell).isZero()
        }

        @Test
        fun `la plus grande case sert d'échelle`() {
            val map = KeyHeatmap()
            repeat(3) { map.onTap('a', 0.0, 0.0, text) }
            map.onTap('b', 0.0, 0.0, text)

            assertThat(map.maxCell).isEqualTo(3)
        }

        @Test
        fun `marquer enregistré efface le drapeau`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)

            map.markSaved()

            assertThat(map.changed).isFalse()
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `incognito, mot de passe et pavé numérique ne comptent rien`() {
            val map = KeyHeatmap()

            assertThat(map.onTap('a', 0.0, 0.0, incognito)).isFalse()
            assertThat(map.onTap(KeyKind.SPACE, 0.0, 0.0, password)).isFalse()
            assertThat(map.onTap('a', 0.0, 0.0, numeric)).isFalse()

            assertThat(map.isEmpty).isTrue()
            assertThat(map.changed).isFalse()
            assertThat(map.serialize()).isEqualTo(KeyHeatmap().serialize())
        }

        @Test
        fun `une frappe interdite n'est pas empilée`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, incognito)

            map.onErase()

            assertThat(map.changed).isFalse()
        }

        @Test
        fun `une frappe hors de la touche ou non finie est ignorée`() {
            val map = KeyHeatmap()

            assertThat(map.onTap('a', 0.51, 0.0, text)).isFalse()
            assertThat(map.onTap('a', 0.0, -0.51, text)).isFalse()

            assertThat(map.isEmpty).isTrue()
            map.onErase()
            assertThat(map.changed).isFalse()
        }

        @Test
        fun `un caractère qui n'est pas une lettre a-z ou une touche apprise est refusé`() {
            val map = KeyHeatmap()

            assertThat(map.onTap('é', 0.0, 0.0, text)).isFalse()
            assertThat(map.onTap('`', 0.0, 0.0, text)).isFalse()
            assertThat(map.onTap('{', 0.0, 0.0, text)).isFalse()
            assertThat(map.onTap('A', 0.0, 0.0, text)).isFalse()
            assertThat(map.onTap(KeyKind.SHIFT, 0.0, 0.0, text)).isFalse()
            assertThat(map.onTap(KeyKind.LETTER, 0.0, 0.0, text)).isFalse()

            assertThat(map.isEmpty).isTrue()
        }

        @Test
        fun `un effacement sans frappe récente ne change rien`() {
            val map = KeyHeatmap()

            map.onErase()

            assertThat(map.changed).isFalse()
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "x", "h2;0", "h1", "h1;1,2,3"])
        fun `forme absente, abîmée ou d'une autre version - carte vide`(stored: String) {
            assertThat(KeyHeatmap.parse(stored).serialize()).isEqualTo(KeyHeatmap().serialize())
        }

        @Test
        fun `forme nulle - carte vide`() {
            assertThat(KeyHeatmap.parse(null).isEmpty).isTrue()
        }

        @Test
        fun `valeur négative, non numérique ou incohérente - carte vide`() {
            val empty = KeyHeatmap().serialize()
            val blocks = empty.split(";").toMutableList()
            val fields = blocks[1].split(",").toMutableList()

            fun with(change: (MutableList<String>) -> Unit): String {
                val f = fields.toMutableList()
                change(f)
                val b = blocks.toMutableList()
                b[1] = f.joinToString(",")
                return b.joinToString(";")
            }

            assertThat(KeyHeatmap.parse(with { it[0] = "-1" }).serialize()).isEqualTo(empty)
            assertThat(KeyHeatmap.parse(with { it[2] = "x" }).serialize()).isEqualTo(empty)
            assertThat(KeyHeatmap.parse(with { it[0] = "1"; it[2] = "2" }).serialize()).isEqualTo(empty)
            assertThat(KeyHeatmap.parse(with { it[1] = "2000" }).serialize()).isEqualTo(empty)
            assertThat(KeyHeatmap.parse(with { it[0] = "2000" }).serialize()).isEqualTo(empty)
        }

        @Test
        fun `une forme valide avec des cases à zéro est relue`() {
            val parsed = KeyHeatmap.parse(stored(listOf(5, 2, 3) + List(47) { 0 }))

            assertThat(parsed.heat('a').taps).isEqualTo(5)
            assertThat(parsed.heat('a').errors).isEqualTo(2)
            assertThat(parsed.heat('a').cells[0]).isEqualTo(3)
        }

        @Test
        fun `un bloc de longueur fausse, une case négative ou une erreur négative - carte vide`() {
            val empty = KeyHeatmap().serialize()

            assertThat(KeyHeatmap.parse(stored(listOf(5, 0) + List(47) { 0 })).serialize()).isEqualTo(empty)
            assertThat(KeyHeatmap.parse(stored(listOf(5, 0) + List(49) { 0 })).serialize()).isEqualTo(empty)
            assertThat(KeyHeatmap.parse(stored(listOf(5, 0, -1) + List(47) { 0 })).serialize()).isEqualTo(empty)
            assertThat(KeyHeatmap.parse(stored(listOf(5, -1) + List(48) { 0 })).serialize()).isEqualTo(empty)
        }

        @Test
        fun `la remise à zéro complète efface frappes, cases et erreurs`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)
            map.onTap('a', 0.0, 0.0, text)
            map.onErase()

            map.reset()

            assertThat(map.heat('a').taps).isZero()
            assertThat(map.heat('a').errors).isZero()
            assertThat(map.heat('a').cells.sum()).isZero()
            map.onTap('a', 0.0, 0.0, text)
            assertThat(map.heat('a').taps).isEqualTo(1)
        }

        @ParameterizedTest
        @ValueSource(doubles = [0.5000001, -0.5000001, 0.51, -0.51, 10.0, -10.0])
        fun `un décalage hors de la touche est ignoré sur chaque axe`(offset: Double) {
            val map = KeyHeatmap()

            assertThat(map.onTap('a', offset, 0.0, text)).isFalse()
            assertThat(map.onTap('a', 0.0, offset, text)).isFalse()
            assertThat(map.onTap(KeyKind.SPACE, offset, 0.0, text)).isFalse()
            assertThat(map.isEmpty).isTrue()
        }

        @ParameterizedTest
        @ValueSource(doubles = [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY])
        fun `une valeur non finie est ignorée sur chaque axe`(value: Double) {
            val map = KeyHeatmap()

            assertThat(map.onTap('a', value, 0.0, text)).isFalse()
            assertThat(map.onTap('a', 0.0, value, text)).isFalse()
            assertThat(map.isEmpty).isTrue()
        }

        @Test
        fun `la lecture vide la pile mémoire`() {
            val map = KeyHeatmap.parse(KeyHeatmap().serialize())

            map.onErase()

            assertThat(map.changed).isFalse()
        }

        @Test
        fun `la forme relue n'a aucune pile - un effacement ne compte rien`() {
            val source = KeyHeatmap()
            source.onTap('a', 0.0, 0.0, text)

            val back = KeyHeatmap.parse(source.serialize())
            back.onErase()

            assertThat(back.heat('a').errors).isZero()
        }

        @Test
        fun `les remises à zéro vident la pile`() {
            val a = KeyHeatmap()
            a.onTap('a', 0.0, 0.0, text)
            a.resetErrors()
            a.onErase()
            val b = KeyHeatmap()
            b.onTap('a', 0.0, 0.0, text)
            b.resetDensity()
            b.onErase()

            assertThat(a.heat('a').errors).isZero()
            assertThat(b.heat('a').errors).isZero()
        }

        @Test
        fun `les copies de cases ne modifient pas la carte`() {
            val map = KeyHeatmap()
            map.onTap('a', 0.0, 0.0, text)

            map.heat('a').cells.fill(99)

            assertThat(map.maxCell).isEqualTo(1)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `les bords -0,5 et 0,5 sont acceptés dans les cases extrêmes`() {
            val map = KeyHeatmap()

            assertThat(map.onTap('a', -0.5, -0.5, text)).isTrue()
            assertThat(map.onTap('a', 0.5, 0.5, text)).isTrue()

            val cells = map.heat('a').cells
            assertThat(cells[0]).isEqualTo(1)
            assertThat(cells[KeyHeatmap.COLS * KeyHeatmap.ROWS - 1]).isEqualTo(1)
        }

        @Test
        fun `les limites de cases se répartissent sans trou`() {
            val map = KeyHeatmap()

            map.onTap('a', -0.376, 0.0, text)
            map.onTap('a', -0.374, 0.0, text)

            val row = 3 * KeyHeatmap.COLS
            assertThat(map.heat('a').cells[row]).isEqualTo(1)
            assertThat(map.heat('a').cells[row + 1]).isEqualTo(1)
        }

        @Test
        fun `à 2000 frappes, tout est divisé par deux`() {
            val map = KeyHeatmap()
            repeat(1999) { map.onTap('a', 0.0, 0.0, text) }
            assertThat(map.heat('a').taps).isEqualTo(1999)

            map.onTap('a', 0.0, 0.0, text)

            val a = map.heat('a')
            assertThat(a.taps).isEqualTo(1000)
            assertThat(a.cells.sum()).isEqualTo(1000)
        }

        @Test
        fun `la division concerne aussi les erreurs et épargne les autres touches`() {
            val map = KeyHeatmap()
            repeat(10) { map.onTap('b', 0.0, 0.0, text) }
            repeat(1999) { map.onTap('a', 0.0, 0.0, text) }
            map.onTap('a', 0.0, 0.0, text)
            map.onTap('a', 0.0, 0.0, text)
            map.onErase()
            map.onErase()
            assertThat(map.heat('a').errors).isEqualTo(2)

            assertThat(map.heat('a').taps).isEqualTo(1001)
            assertThat(map.heat('b').taps).isEqualTo(10)
        }

        @Test
        fun `1999 erreurs ne déclenchent pas la division, 2000 oui`() {
            val map = KeyHeatmap.parse(
                (listOf("h1") + List(30) { i -> (listOf(if (i == 0) 1000 else 0, if (i == 0) 1998 else 0) + List(48) { 0 }).joinToString(",") })
                    .joinToString(";"),
            )
            map.onTap('a', 0.0, 0.0, text)
            map.onErase()
            assertThat(map.heat('a').errors).isEqualTo(1999)
            assertThat(map.heat('a').taps).isEqualTo(1001)

            map.onTap('a', 0.0, 0.0, text)
            map.onErase()

            assertThat(map.heat('a').errors).isEqualTo(1000)
            assertThat(map.heat('a').taps).isEqualTo(501)
        }

        @Test
        fun `un taux d'erreur n'existe pas à 19 frappes, existe à 20`() {
            val map = KeyHeatmap()
            repeat(19) { map.onTap('e', 0.0, 0.0, text) }
            assertThat(map.heat('e').errorRate).isNull()

            map.onTap('e', 0.0, 0.0, text)

            assertThat(map.heat('e').errorRate).isEqualTo(0.0)
        }

        @Test
        fun `le taux d'erreur de retour arrière est toujours nul`() {
            val map = KeyHeatmap()
            repeat(30) { map.onTap(KeyKind.BACKSPACE, 0.0, 0.0, text) }

            assertThat(map.heat(KeyKind.BACKSPACE).errorRate).isNull()
            assertThat(map.heat(KeyKind.BACKSPACE).taps).isEqualTo(30)
        }

        @Test
        fun `le taux d'erreur est plafonné à 1`() {
            val map = KeyHeatmap()
            val blocks = List(30) { i ->
                if (i == 0) (listOf(20, 1500) + List(48) { 0 }).joinToString(",") else List(50) { 0 }.joinToString(",")
            }

            val parsed = KeyHeatmap.parse((listOf("h1") + blocks).joinToString(";"))

            assertThat(parsed.heat('a').errorRate).isEqualTo(1.0)
            assertThat(map.heat('a').errorRate).isNull()
        }

        @Test
        fun `la pile garde 32 frappes - la 33e plus ancienne est oubliée`() {
            val map = KeyHeatmap()
            map.onTap('z', 0.0, 0.0, text)
            repeat(31) { map.onTap('a', 0.0, 0.0, text) }
            repeat(31) { map.onErase() }
            map.onErase()
            assertThat(map.heat('z').errors).isEqualTo(1)

            val other = KeyHeatmap()
            other.onTap('z', 0.0, 0.0, text)
            repeat(32) { other.onTap('a', 0.0, 0.0, text) }
            repeat(32) { other.onErase() }
            other.onErase()
            assertThat(other.heat('z').errors).isZero()
            assertThat(other.heat('a').errors).isEqualTo(32)
        }

        @Test
        fun `les marqueurs comptent dans la limite de la pile`() {
            val map = KeyHeatmap()
            map.onTap('z', 0.0, 0.0, text)
            repeat(32) { map.onUntracked() }

            repeat(33) { map.onErase() }

            assertThat(map.heat('z').errors).isZero()
        }

        @Test
        fun `la forme enregistrée d'une carte vide est stable`() {
            val empty = KeyHeatmap()

            assertThat(empty.serialize().split(";")).hasSize(31)
            assertThat(empty.maxCell).isZero()
            assertThat(empty.keys().all { it.errorRate == null }).isTrue()
            assertThat(KeyHeatmap.parse(empty.serialize()).isEmpty).isTrue()
        }
    }
}
