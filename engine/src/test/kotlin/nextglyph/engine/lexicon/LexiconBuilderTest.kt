package nextglyph.engine.lexicon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class LexiconBuilderTest {

    @Nested
    inner class HappyPath {

        @Test
        fun `indexe chaque forme sous sa clé normalisée`() {
            val lexicon = LexiconFixtures.sample()

            assertThat(lexicon.displayForms("peut-etre")).containsExactly(LexiconEntry("peut-être", "peut-etre", 25))
            assertThat(lexicon.displayForms("aujourd'hui")).containsExactly(LexiconEntry("aujourd'hui", "aujourd'hui", 15))
        }

        @Test
        fun `conserve toutes les formes d'une clé avec leur fréquence`() {
            val lexicon = LexiconFixtures.lexicon(listOf("ou" to 40L, "où" to 25L, "Où" to 2L))

            assertThat(lexicon.displayForms("ou")).containsExactly(
                LexiconEntry("ou", "ou", 40),
                LexiconEntry("où", "ou", 25),
                LexiconEntry("Où", "ou", 2),
            )
            assertThat(lexicon.keyCount).isEqualTo(1)
            assertThat(lexicon.formCount).isEqualTo(3)
        }

        @Test
        fun `additionne les doublons exacts d'une forme`() {
            val lexicon = LexiconFixtures.lexicon(listOf("chat" to 10L, "chien" to 1L, "chat" to 5L))

            assertThat(lexicon.displayForms("chat")).containsExactly(LexiconEntry("chat", "chat", 15))
            assertThat(lexicon.formCount).isEqualTo(2)
        }

        @Test
        fun `enregistre l'étiquette de langue`() {
            val builder = LexiconBuilder("en-GB")

            assertThat(builder.languageTag).isEqualTo("en-GB")
            assertThat(builder.build().languageTag).isEqualTo("en-GB")
        }

        @Test
        fun `produit le même fichier quel que soit l'ordre d'ajout`() {
            val forward = LexiconFixtures.builder(LexiconFixtures.SAMPLE).toByteArray()
            val backward = LexiconFixtures.builder(LexiconFixtures.SAMPLE.reversed()).toByteArray()

            assertThat(backward).isEqualTo(forward)
        }

        @Test
        fun `ignore les formes invalides en mode SKIP et les consigne`() {
            val builder = LexiconBuilder("de", InvalidFormPolicy.SKIP)
                .add("Straße", 5)
                .add("haus", 3)
                .add("", 1)
                .add("3D", 2)

            val lexicon = builder.build()

            assertThat(builder.skippedForms).containsExactly("Straße", "", "3D")
            assertThat(lexicon.keyCount).isEqualTo(1)
            assertThat(lexicon.displayForms("haus")).hasSize(1)
        }

        @Test
        fun `build peut être appelé plusieurs fois`() {
            val builder = LexiconFixtures.builder(listOf("chat" to 1L))

            val first = builder.build()
            builder.add("chien", 2)
            val second = builder.build()

            assertThat(first.keyCount).isEqualTo(1)
            assertThat(second.keyCount).isEqualTo(2)
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["Straße", "3D", "co op", "ab c", "x²"])
        fun `refuse par défaut une forme non indexable en la citant`(form: String) {
            val builder = LexiconBuilder("fr")

            assertThatIllegalArgumentException()
                .isThrownBy { builder.add(form, 1) }
                .withMessageContaining("« $form »")
            assertThat(builder.build().keyCount).isZero()
        }

        @Test
        fun `cite la raison donnée par TextNormalizer`() {
            assertThatIllegalArgumentException()
                .isThrownBy { LexiconBuilder("fr").add("", 1) }
                .withMessageContaining("vide")
        }

        @Test
        fun `cite le caractère hors alphabet dans la clé`() {
            assertThatIllegalArgumentException()
                .isThrownBy { LexiconBuilder("fr", InvalidFormPolicy.REJECT).add("Straße", 1) }
                .withMessageContaining("straße")
                .withMessageContaining(LexiconAlphabet.CHARS)
        }

        @ParameterizedTest(name = "fréquence {0}")
        @ValueSource(longs = [0, -1, Long.MIN_VALUE])
        fun `refuse une fréquence nulle ou négative, même en mode SKIP`(frequency: Long) {
            val builder = LexiconBuilder("fr", InvalidFormPolicy.SKIP)

            assertThatIllegalArgumentException()
                .isThrownBy { builder.add("chat", frequency) }
                .withMessageContaining("chat")
                .withMessageContaining("$frequency")
            assertThat(builder.skippedForms).isEmpty()
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = ["", "f", "fr_FR", "français", "abcdefg-abcdefgh-abcdefgh-abcdefgh-a"])
        fun `refuse une étiquette de langue invalide`(tag: String) {
            assertThatIllegalArgumentException()
                .isThrownBy { LexiconBuilder(tag) }
                .withMessageContaining("« $tag »")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `construit un lexique vide`() {
            val lexicon = LexiconBuilder("fr").build()

            assertThat(lexicon.keyCount).isZero()
            assertThat(lexicon.formCount).isZero()
        }

        @Test
        fun `construit un lexique d'une seule entrée d'un caractère`() {
            val lexicon = LexiconFixtures.lexicon(listOf("z" to 1L))

            assertThat(lexicon.keyCount).isEqualTo(1)
            assertThat(lexicon.displayForms("z")).containsExactly(LexiconEntry("z", "z", 1))
        }

        @Test
        fun `sature à Long MAX_VALUE la somme des doublons`() {
            val lexicon = LexiconFixtures.lexicon(listOf("x" to Long.MAX_VALUE, "x" to 1L, "y" to Long.MAX_VALUE - 1, "y" to 1L))

            assertThat(lexicon.displayForms("x").single().frequency).isEqualTo(Long.MAX_VALUE)
            assertThat(lexicon.displayForms("y").single().frequency).isEqualTo(Long.MAX_VALUE)
        }

        @Test
        fun `additionne exactement juste sous la saturation`() {
            val lexicon = LexiconFixtures.lexicon(listOf("x" to Long.MAX_VALUE - 2, "x" to 1L))

            assertThat(lexicon.displayForms("x").single().frequency).isEqualTo(Long.MAX_VALUE - 1)
        }

        @Test
        fun `trie les formes de même fréquence par ordre alphabétique`() {
            val lexicon = LexiconFixtures.lexicon(listOf("là" to 7L, "la" to 7L))

            assertThat(lexicon.displayForms("la").map { it.form }).containsExactly("la", "là")
        }

        @Test
        fun `accepte une forme de longueur maximale dont la clé double de longueur`() {
            val form = "œ".repeat(64)

            val lexicon = LexiconFixtures.lexicon(listOf(form to 3L))

            assertThat(lexicon.displayForms("oe".repeat(64))).containsExactly(LexiconEntry(form, "oe".repeat(64), 3))
            assertThat("oe".repeat(64)).hasSize(Lexicon.MAX_KEY_LENGTH)
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(InvalidFormPolicy::class)
        fun `une forme valide est acceptée quelle que soit la règle`(policy: InvalidFormPolicy) {
            val builder = LexiconBuilder("fr", policy).add("École", 1)

            assertThat(builder.skippedForms).isEmpty()
            assertThat(builder.build().displayForms("ecole").single().form).isEqualTo("École")
        }

        @Test
        fun `la liste des formes ignorées est une copie`() {
            val builder = LexiconBuilder("fr", InvalidFormPolicy.SKIP).add("3D", 1)
            val snapshot = builder.skippedForms

            builder.add("4K", 1)

            assertThat(snapshot).containsExactly("3D")
        }
    }
}
