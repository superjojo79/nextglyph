package nextglyph.ime

import nextglyph.testing.TestConvention
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Garde-fou : toutes les classes de test du module `ime` respectent la convention
 * HappyPath / ErrorCases / BoundaryValues. Le contrôle lui-même (partagé, fixtures du
 * module engine) est testé dans engine.
 */
class TestConventionTest {

    private val compiledTestsRoot: Path =
        Paths.get(TestConventionTest::class.java.protectionDomain!!.codeSource!!.location.toURI())

    @Nested
    inner class HappyPath {

        @Test
        fun `toutes les classes de test du module respectent la convention`() {
            val classNames = TestConvention.testClassNamesIn(compiledTestsRoot)

            val violations = classNames.map(Class<*>::forName).flatMap(TestConvention::violationsOf)

            assertThat(classNames).isNotEmpty()
            assertThat(violations).isEmpty()
        }
    }

    @Nested
    inner class ErrorCases {

        @Test
        fun `un groupe absent est signalé`() {
            assertThat(TestConvention.violationsOf(MissingGroupFixture::class.java))
                .singleElement().asString().contains("BoundaryValues", "absent")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `le scan retrouve les classes de test du module`() {
            val classNames = TestConvention.testClassNamesIn(compiledTestsRoot)

            assertThat(classNames).contains(
                TestConventionTest::class.java.name,
                "nextglyph.ime.ManifestTest",
                "nextglyph.ime.EnterBehaviorResolverTest",
            )
        }
    }
}

// Classe témoin : abstraite, donc ignorée par JUnit lors de la découverte des tests.
private abstract class MissingGroupFixture {
    @Nested inner class HappyPath { @Test fun ok() = Unit }
    @Nested inner class ErrorCases { @Test fun ok() = Unit }
}
