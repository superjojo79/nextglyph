package nextglyph.engine.profile

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DecimalSeparatorPolicyTest {

    /**
     * Champ simulé : [accepts] décide des caractères acceptés ; un caractère refusé ne change pas le texte.
     * [window] borne la longueur du texte rendu avant le curseur, comme `getTextBeforeCursor(n)`.
     */
    private class FakeField(
        initial: String = "",
        private val accepts: (Char, String) -> Boolean,
        private val window: Int = 64,
        private val visible: Boolean = true,
    ) : SeparatorEditor {
        var text = initial
        val commits = mutableListOf<String>()

        override fun textBeforeCursor(): String? = if (visible) text.takeLast(window) else null

        override fun commitText(text: String) {
            commits += text
            if (accepts(text[0], this.text)) this.text += text
        }
    }

    private val field = FieldKey("com.exemple", 7)
    private fun policy() = DecimalSeparatorPolicy()
    private val acceptsAll: (Char, String) -> Boolean = { _, _ -> true }
    private val dotOnly: (Char, String) -> Boolean = { c, _ -> c != ',' }
    private val commaOnly: (Char, String) -> Boolean = { c, _ -> c != '.' }
    private val neither: (Char, String) -> Boolean = { c, _ -> c != ',' && c != '.' }

    @Nested
    inner class HappyPath {
        @Test
        fun `insère la virgule quand le champ l'accepte`() {
            val editor = FakeField("12", acceptsAll)

            val inserted = policy().insert(field, editor)

            assertThat(inserted).isEqualTo(',')
            assertThat(editor.text).isEqualTo("12,")
            assertThat(editor.commits).containsExactly(",")
        }

        @Test
        fun `repli sur le point quand la virgule est refusée`() {
            val editor = FakeField("12", dotOnly)

            val inserted = policy().insert(field, editor)

            assertThat(inserted).isEqualTo('.')
            assertThat(editor.text).isEqualTo("12.")
            assertThat(editor.commits).containsExactly(",", ".")
        }

        @Test
        fun `le choix du point est mémorisé - plus de tâtonnement dans ce champ`() {
            val policy = policy()
            policy.insert(field, FakeField("1", dotOnly))

            val second = FakeField("3", dotOnly)
            val inserted = policy.insert(field, second)

            assertThat(inserted).isEqualTo('.')
            assertThat(second.commits).containsExactly(".")
            assertThat(policy.separatorFor(field)).isEqualTo('.')
        }

        @Test
        fun `la mémoire est propre à chaque champ et à chaque paquet`() {
            val policy = policy()
            policy.insert(field, FakeField("1", dotOnly))

            assertThat(policy.separatorFor(FieldKey("com.exemple", 8))).isEqualTo(',')
            assertThat(policy.separatorFor(FieldKey("com.autre", 7))).isEqualTo(',')
            assertThat(policy.separatorFor(field)).isEqualTo('.')
        }

        @Test
        fun `un champ mémorisé qui accepte à nouveau la virgule est oublié`() {
            val policy = policy()
            policy.insert(field, FakeField("1", dotOnly))
            val editor = FakeField("2", commaOnly)

            val inserted = policy.insert(field, editor)

            assertThat(inserted).isEqualTo(',')
            assertThat(editor.commits).containsExactly(".", ",")
            assertThat(policy.separatorFor(field)).isEqualTo(',')
        }

        @Test
        fun `le séparateur préféré peut être le point`() {
            val policy = DecimalSeparatorPolicy(preferred = '.')
            val accepting = FakeField("1", acceptsAll)

            assertThat(policy.insert(field, accepting)).isEqualTo('.')
            assertThat(policy.separatorFor(field)).isEqualTo('.')
            val refusing = FakeField("1", commaOnly)
            assertThat(policy.insert(FieldKey("b", 1), refusing)).isEqualTo(',')
            assertThat(refusing.commits).containsExactly(".", ",")
            assertThat(policy.separatorFor(FieldKey("b", 1))).isEqualTo(',')
        }

        @Test
        fun `clear oublie les champs mémorisés`() {
            val policy = policy()
            policy.insert(field, FakeField("1", dotOnly))

            policy.clear()

            assertThat(policy.separatorFor(field)).isEqualTo(',')
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `rien n'est inséré et rien n'est mémorisé si le champ refuse les deux`() {
            val policy = policy()
            val editor = FakeField("12", neither)

            val inserted = policy.insert(field, editor)

            assertThat(inserted).isNull()
            assertThat(editor.text).isEqualTo("12")
            assertThat(policy.separatorFor(field)).isEqualTo(',')
        }

        @Test
        fun `sans accès au texte, la virgule est supposée acceptée et rien n'est mémorisé`() {
            val policy = policy()
            val editor = FakeField("12", dotOnly, visible = false)

            val inserted = policy.insert(field, editor)

            assertThat(inserted).isEqualTo(',')
            assertThat(editor.commits).containsExactly(",")
            assertThat(policy.separatorFor(field)).isEqualTo(',')
        }

        @Test
        fun `un refus dû à un second séparateur n'est pas retenu comme refus de la virgule`() {
            val policy = policy()
            // Le champ n'accepte qu'un séparateur : « 1,5 » + « , » est refusé, et « . » aussi.
            val editor = FakeField("1,5", { c, text -> !(c == ',' || c == '.') || text.none { it == ',' || it == '.' } })

            val inserted = policy.insert(field, editor)

            assertThat(inserted).isNull()
            assertThat(policy.separatorFor(field)).isEqualTo(',')
        }

        @Test
        fun `un repli réussi après un nombre déjà séparé n'est pas mémorisé`() {
            val policy = policy()
            val editor = FakeField("1.5", dotOnly)

            val inserted = policy.insert(field, editor)

            assertThat(inserted).isEqualTo('.')
            assertThat(policy.separatorFor(field)).isEqualTo(',')
        }

        @Test
        fun `rejette un séparateur préféré qui n'est ni virgule ni point`() {
            assertThatThrownBy { DecimalSeparatorPolicy(preferred = ';') }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("séparateur")
        }

        @Test
        fun `rejette une capacité non strictement positive`() {
            assertThatThrownBy { DecimalSeparatorPolicy(capacity = 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("capacité")
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `champ vide - le repli est mémorisé`() {
            val policy = policy()

            val inserted = policy.insert(field, FakeField("", dotOnly))

            assertThat(inserted).isEqualTo('.')
            assertThat(policy.separatorFor(field)).isEqualTo('.')
        }

        @Test
        fun `un séparateur séparé du nombre par une lettre ou une espace ne compte pas`() {
            val policy = policy()

            policy.insert(field, FakeField("a,5 ", dotOnly))
            assertThat(policy.separatorFor(field)).isEqualTo('.')

            policy.clear()
            policy.insert(field, FakeField("a,b", dotOnly))
            assertThat(policy.separatorFor(field)).isEqualTo('.')
        }

        @Test
        fun `un point seul avant le curseur empêche de conclure`() {
            val policy = policy()

            policy.insert(field, FakeField("7.", dotOnly))

            assertThat(policy.separatorFor(field)).isEqualTo(',')
        }

        @Test
        fun `fenêtre de texte pleine - le refus est détecté malgré le décalage`() {
            val editor = FakeField("1234", dotOnly, window = 4)

            val inserted = policy().insert(field, editor)

            assertThat(inserted).isEqualTo('.')
            assertThat(editor.text).isEqualTo("1234.")
        }

        @Test
        fun `un champ qui reformate le texte en gardant le séparateur final est accepté`() {
            val editor = FakeField("1", acceptsAll, window = 2)

            assertThat(policy().insert(field, editor)).isEqualTo(',')
        }

        @Test
        fun `un champ qui change le texte sans finir par le séparateur est considéré comme refus`() {
            val editor = object : SeparatorEditor {
                var text = "1"
                override fun textBeforeCursor(): String = text
                override fun commitText(text: String) { this.text = "1 " }
            }

            assertThat(policy().insert(field, editor)).isNull()
        }

        @Test
        fun `la mémoire est bornée - le champ le moins récent est oublié`() {
            val policy = DecimalSeparatorPolicy(capacity = 2)
            val a = FieldKey("p", 1)
            val b = FieldKey("p", 2)
            val c = FieldKey("p", 3)
            policy.insert(a, FakeField("", dotOnly))
            policy.insert(b, FakeField("", dotOnly))
            policy.separatorFor(a) // a redevient récent

            policy.insert(c, FakeField("", dotOnly))

            assertThat(policy.separatorFor(a)).isEqualTo('.')
            assertThat(policy.separatorFor(b)).isEqualTo(',')
            assertThat(policy.separatorFor(c)).isEqualTo('.')
        }

        @Test
        fun `capacité de un champ`() {
            val policy = DecimalSeparatorPolicy(capacity = 1)
            policy.insert(FieldKey("p", 1), FakeField("", dotOnly))
            policy.insert(FieldKey("p", 2), FakeField("", dotOnly))

            assertThat(policy.separatorFor(FieldKey("p", 1))).isEqualTo(',')
            assertThat(policy.separatorFor(FieldKey("p", 2))).isEqualTo('.')
            assertThat(DecimalSeparatorPolicy.DEFAULT_CAPACITY).isEqualTo(256)
        }
    }
}
