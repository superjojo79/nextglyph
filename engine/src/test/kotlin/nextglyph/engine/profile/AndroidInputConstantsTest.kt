package nextglyph.engine.profile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/** Les constantes recopiées valent exactement celles de la documentation Android (InputType, EditorInfo). */
class AndroidInputConstantsTest {

    /** Valeurs de la documentation Android, nom de constante → valeur. */
    private val documented: Map<String, Int> = mapOf(
        "TYPE_MASK_CLASS" to 15,
        "TYPE_MASK_VARIATION" to 4080,
        "TYPE_MASK_FLAGS" to 16773120,
        "TYPE_NULL" to 0,
        "TYPE_CLASS_TEXT" to 1,
        "TYPE_CLASS_NUMBER" to 2,
        "TYPE_CLASS_PHONE" to 3,
        "TYPE_CLASS_DATETIME" to 4,
        "TYPE_TEXT_FLAG_CAP_CHARACTERS" to 4096,
        "TYPE_TEXT_FLAG_CAP_WORDS" to 8192,
        "TYPE_TEXT_FLAG_CAP_SENTENCES" to 16384,
        "TYPE_TEXT_FLAG_AUTO_CORRECT" to 32768,
        "TYPE_TEXT_FLAG_AUTO_COMPLETE" to 65536,
        "TYPE_TEXT_FLAG_MULTI_LINE" to 131072,
        "TYPE_TEXT_FLAG_IME_MULTI_LINE" to 262144,
        "TYPE_TEXT_FLAG_NO_SUGGESTIONS" to 524288,
        "TYPE_TEXT_VARIATION_NORMAL" to 0,
        "TYPE_TEXT_VARIATION_URI" to 16,
        "TYPE_TEXT_VARIATION_EMAIL_ADDRESS" to 32,
        "TYPE_TEXT_VARIATION_EMAIL_SUBJECT" to 48,
        "TYPE_TEXT_VARIATION_SHORT_MESSAGE" to 64,
        "TYPE_TEXT_VARIATION_LONG_MESSAGE" to 80,
        "TYPE_TEXT_VARIATION_PERSON_NAME" to 96,
        "TYPE_TEXT_VARIATION_POSTAL_ADDRESS" to 112,
        "TYPE_TEXT_VARIATION_PASSWORD" to 128,
        "TYPE_TEXT_VARIATION_VISIBLE_PASSWORD" to 144,
        "TYPE_TEXT_VARIATION_WEB_EDIT_TEXT" to 160,
        "TYPE_TEXT_VARIATION_FILTER" to 176,
        "TYPE_TEXT_VARIATION_PHONETIC" to 192,
        "TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS" to 208,
        "TYPE_TEXT_VARIATION_WEB_PASSWORD" to 224,
        "TYPE_NUMBER_FLAG_SIGNED" to 4096,
        "TYPE_NUMBER_FLAG_DECIMAL" to 8192,
        "TYPE_NUMBER_VARIATION_NORMAL" to 0,
        "TYPE_NUMBER_VARIATION_PASSWORD" to 16,
        "TYPE_DATETIME_VARIATION_NORMAL" to 0,
        "TYPE_DATETIME_VARIATION_DATE" to 16,
        "TYPE_DATETIME_VARIATION_TIME" to 32,
        "IME_MASK_ACTION" to 255,
        "IME_ACTION_GO" to 2,
        "IME_ACTION_SEARCH" to 3,
        "IME_ACTION_SEND" to 4,
        "IME_ACTION_NEXT" to 5,
        "IME_ACTION_DONE" to 6,
        "IME_ACTION_PREVIOUS" to 7,
        "IME_FLAG_NO_PERSONALIZED_LEARNING" to 16777216,
    )

    private val actual: Map<String, Int> = AndroidInputConstants::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.type == Int::class.javaPrimitiveType }
        .associate { it.name to it.getInt(null) }

    @Nested
    inner class HappyPath {
        @Test
        fun `chaque constante a la valeur de la documentation Android`() {
            for ((name, value) in documented) {
                assertThat(actual[name]).`as`(name).isEqualTo(value)
            }
        }
    }

    @Nested
    inner class ErrorCases {
        @Test
        fun `aucune constante n'est omise de la table de référence`() {
            assertThat(documented.keys).containsExactlyInAnyOrderElementsOf(actual.keys)
        }
    }

    @Nested
    inner class BoundaryValues {
        @Test
        fun `les masques ne se recouvrent pas et couvrent les 24 bits bas`() {
            val c = AndroidInputConstants
            assertThat(c.TYPE_MASK_CLASS and c.TYPE_MASK_VARIATION).isZero()
            assertThat(c.TYPE_MASK_VARIATION and c.TYPE_MASK_FLAGS).isZero()
            assertThat(c.TYPE_MASK_CLASS or c.TYPE_MASK_VARIATION or c.TYPE_MASK_FLAGS).isEqualTo(0x00ffffff)
        }

        @Test
        fun `chaque drapeau de saisie tient dans le masque des drapeaux et chaque variation dans celui des variations`() {
            for ((name, value) in documented) {
                if (name.contains("_FLAG_") && name.startsWith("TYPE_")) {
                    assertThat(value and AndroidInputConstants.TYPE_MASK_FLAGS).`as`(name).isEqualTo(value)
                }
                if (name.contains("_VARIATION_")) {
                    assertThat(value and AndroidInputConstants.TYPE_MASK_VARIATION).`as`(name).isEqualTo(value)
                }
            }
        }
    }
}
