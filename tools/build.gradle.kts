import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
}

// Outils exécutés sur la JVM de développement ; même bytecode que le moteur par cohérence.
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
    implementation(libs.gson)
    // Décompression bzip2 (exports Tatoeba) et tar (SCOWL) ; le JDK ne lit pas bzip2.
    implementation(libs.commons.compress)
    implementation(project(":engine"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(testFixtures(project(":engine")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Télécharge les données linguistiques listées dans data/sources.json vers build/data/,
// en vérifiant leur SHA-256. Toute la logique est dans nextglyph.tools.data (testée).
tasks.register<JavaExec>("fetchData") {
    group = "data"
    description = "Télécharge et vérifie les données linguistiques (data/sources.json) dans build/data/."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("nextglyph.tools.data.FetchDataMainKt")
    args(
        layout.projectDirectory.file("data/sources.json").asFile.path,
        layout.buildDirectory.dir("data").get().asFile.path,
    )
    // Toujours exécutée : elle est elle-même idempotente (ne retélécharge pas un fichier valide).
    outputs.upToDateWhen { false }
}

// Listes de mots (fréquences) et corpus d'évaluation, construits à partir de build/data/ :
// build/wordlists/<lang>.tsv, build/wordlists/<lang>-stats.txt, build/corpus/*.txt.
// Toute la logique est dans nextglyph.tools.wordlists (testée).
tasks.register<JavaExec>("buildWordLists") {
    group = "data"
    description = "Construit les listes de mots et les corpus d'évaluation (build/wordlists, build/corpus)."
    dependsOn("fetchData")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("nextglyph.tools.wordlists.BuildWordListsMainKt")
    // Le corpus anglais (environ 2 millions de phrases) est tenu en mémoire.
    maxHeapSize = "3g"
    args(
        layout.projectDirectory.file("data/sources.json").asFile.path,
        layout.buildDirectory.dir("data").get().asFile.path,
        layout.buildDirectory.get().asFile.path,
    )
    outputs.upToDateWhen { false }
}

pitest {
    pitestVersion.set(libs.versions.pitest.core)
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    targetClasses.set(listOf("nextglyph.tools.*"))
    // Point d'entrée sans logique (délègue à FetchDataCli) et contrôle de convention.
    excludedClasses.set(listOf("nextglyph.tools.data.FetchDataMainKt", "nextglyph.tools.wordlists.BuildWordListsMainKt"))
    excludedTestClasses.set(listOf("nextglyph.tools.TestConventionTest"))
    mutators.set(listOf("STRONGER"))
    // Évite les mutants inutiles sur les vérifications de nullité générées par Kotlin.
    avoidCallsTo.set(listOf("kotlin.jvm.internal"))
    mutationThreshold.set(85)
    coverageThreshold.set(90)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    threads.set(Runtime.getRuntime().availableProcessors())
}
