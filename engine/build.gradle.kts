import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
    `java-test-fixtures`
}

// Le moteur est consommé par l'application Android : bytecode Java 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Contrôle de convention de tests partagé avec les autres modules (tools, ...).
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // Fuzzing (Apache-2.0) : en test normal, seules les entrées de départ sont rejouées ; JAZZER_FUZZ=1 lance la recherche.
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Tests de fuzzing en Java (Jazzer) : même cible bytecode que le module, avertissements bloquants.
tasks.compileTestJava {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

pitest {
    pitestVersion.set(libs.versions.pitest.core)
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    targetClasses.set(listOf("nextglyph.engine.*"))
    // Les tests de fuzzing (Jazzer) ne servent pas à tuer des mutants : ils tournent en CI dans un job dédié.
    excludedTestClasses.set(listOf("nextglyph.engine.TestConventionTest", "nextglyph.engine.fuzz.*"))
    mutators.set(listOf("STRONGER"))
    // Évite les mutants inutiles sur les vérifications de nullité générées par Kotlin.
    avoidCallsTo.set(listOf("kotlin.jvm.internal"))
    mutationThreshold.set(85)
    coverageThreshold.set(90)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    threads.set(Runtime.getRuntime().availableProcessors())
}
