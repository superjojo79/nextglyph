import com.android.build.api.artifact.SingleArtifact
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "nextglyph.ime"
    // API 37 : le plus récent pris en charge par AGP 9.5 parmi les plateformes installées.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.superjojo79.nextglyph"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":engine"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(testFixtures(project(":engine")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Fournit le manifeste fusionné de chaque variante aux tests unitaires : ManifestTest échoue
// si une dépendance ou le manifeste source introduit la permission INTERNET (spec §2.7, §12).
abstract class MergedManifestArgs : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf("-Dnextglyph.mergedManifest=${manifest.get().asFile.absolutePath}")
}

androidComponents {
    onVariants { variant ->
        val unitTestName = "test${variant.name.replaceFirstChar { it.uppercase() }}UnitTest"
        tasks.withType<Test>().configureEach {
            if (name == unitTestName) {
                val args = objects.newInstance<MergedManifestArgs>()
                args.manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                jvmArgumentProviders.add(args)
            }
        }
    }
}
