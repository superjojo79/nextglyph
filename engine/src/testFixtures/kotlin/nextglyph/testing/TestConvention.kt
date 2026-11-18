package nextglyph.testing

import org.junit.jupiter.api.Nested
import org.junit.platform.commons.annotation.Testable
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.nameWithoutExtension

/**
 * Vérifie la convention de tests du projet (docs/TESTING.md) : chaque classe de test
 * contient trois groupes `@Nested` non vides — [REQUIRED_GROUPS].
 */
object TestConvention {

    val REQUIRED_GROUPS: List<String> = listOf("HappyPath", "ErrorCases", "BoundaryValues")

    /** Noms des classes de test de premier niveau (`*Test`) compilées sous [root]. */
    fun testClassNamesIn(root: Path): List<String> = Files.walk(root).use { paths ->
        paths
            .filter { it.extension == "class" }
            .filter { it.nameWithoutExtension.endsWith("Test") && '$' !in it.nameWithoutExtension }
            .map { root.relativize(it).invariantSeparatorsPathString.removeSuffix(".class").replace('/', '.') }
            .sorted()
            .toList()
    }

    /** Liste les manquements de [testClass] à la convention ; vide si elle est respectée. */
    fun violationsOf(testClass: Class<*>): List<String> {
        val nested = testClass.declaredClasses.associateBy { it.simpleName }
        return REQUIRED_GROUPS.mapNotNull { name ->
            val group = nested[name]
            when {
                group == null -> "${testClass.name} : groupe $name absent"
                !group.isAnnotationPresent(Nested::class.java) -> "${testClass.name} : $name doit être annoté @Nested"
                group.declaredMethods.none(::isTestMethod) -> "${testClass.name} : $name ne contient aucun test"
                else -> null
            }
        }
    }

    private fun isTestMethod(method: Method): Boolean =
        method.annotations.any { isTestable(it.annotationClass.java, depth = 0) }

    private fun isTestable(type: Class<out Annotation>, depth: Int): Boolean =
        type == Testable::class.java ||
            (depth < MAX_META_DEPTH && type.annotations.any { isTestable(it.annotationClass.java, depth + 1) })

    private const val MAX_META_DEPTH = 3
}
