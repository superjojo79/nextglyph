package nextglyph.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Garde-fou : toutes les classes de test du module respectent la convention
 * HappyPath / ErrorCases / BoundaryValues, et le contrôle lui-même est testé.
 */
class TestConventionTest {

    private val compiledTestsRoot: Path =
        Paths.get(TestConventionTest::class.java.protectionDomain.codeSource.location.toURI())

    @Nested
    inner class HappyPath {

        @Test
        fun `toutes les classes de test du module respectent la convention`() {
            val classNames = TestConvention.testClassNamesIn(compiledTestsRoot)

            val violations = classNames.map(Class<*>::forName).flatMap(TestConvention::violationsOf)

            assertThat(classNames).isNotEmpty()
            assertThat(violations).isEmpty()
        }

        @Test
        fun `une classe conforme ne produit aucune violation`() {
            assertThat(TestConvention.violationsOf(CompliantFixture::class.java)).isEmpty()
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un groupe absent est signalé`() {
            assertThat(TestConvention.violationsOf(MissingGroupFixture::class.java))
                .singleElement().asString().contains("BoundaryValues", "absent")
        }

        @Test
        fun `un groupe sans @Nested est signalé`() {
            assertThat(TestConvention.violationsOf(NotNestedFixture::class.java))
                .singleElement().asString().contains("ErrorCases", "@Nested")
        }

        @Test
        fun `un groupe sans méthode de test est signalé`() {
            assertThat(TestConvention.violationsOf(EmptyGroupFixture::class.java))
                .singleElement().asString().contains("HappyPath", "aucun test")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `un seul test paramétré par groupe suffit`() {
            assertThat(TestConvention.violationsOf(ParameterizedOnlyFixture::class.java)).isEmpty()
        }

        @Test
        fun `une classe sans aucun groupe produit une violation par groupe requis`() {
            assertThat(TestConvention.violationsOf(NoGroupFixture::class.java))
                .hasSize(TestConvention.REQUIRED_GROUPS.size)
        }

        @Test
        fun `le scan ne retient que les classes de premier niveau dont le nom finit par Test`() {
            val classNames = TestConvention.testClassNamesIn(compiledTestsRoot)

            assertThat(classNames)
                .contains(TestConventionTest::class.java.name)
                .noneMatch { '$' in it || it.endsWith("Fixture") || it.endsWith("TestConvention") }
        }
    }
}

// Classes témoins : abstraites, donc ignorées par JUnit lors de la découverte des tests.

private abstract class CompliantFixture {
    @Nested inner class HappyPath { @Test fun ok() = Unit }
    @Nested inner class ErrorCases { @Test fun ok() = Unit }
    @Nested inner class BoundaryValues { @Test fun ok() = Unit }
}

private abstract class MissingGroupFixture {
    @Nested inner class HappyPath { @Test fun ok() = Unit }
    @Nested inner class ErrorCases { @Test fun ok() = Unit }
}

private abstract class NotNestedFixture {
    @Nested inner class HappyPath { @Test fun ok() = Unit }
    inner class ErrorCases { @Test fun ok() = Unit }
    @Nested inner class BoundaryValues { @Test fun ok() = Unit }
}

private abstract class EmptyGroupFixture {
    @Nested inner class HappyPath { fun notATest() = Unit }
    @Nested inner class ErrorCases { @Test fun ok() = Unit }
    @Nested inner class BoundaryValues { @Test fun ok() = Unit }
}

private abstract class ParameterizedOnlyFixture {
    @Nested inner class HappyPath { @ParameterizedTest @ValueSource(ints = [1]) fun ok(value: Int) = Unit }
    @Nested inner class ErrorCases { @ParameterizedTest @ValueSource(ints = [1]) fun ok(value: Int) = Unit }
    @Nested inner class BoundaryValues { @ParameterizedTest @ValueSource(ints = [1]) fun ok(value: Int) = Unit }
}

private abstract class NoGroupFixture
