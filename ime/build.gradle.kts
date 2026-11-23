import com.android.build.api.artifact.SingleArtifact
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

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
        // Tests instrumentés (AndroidX Test, Apache-2.0) : `./gradlew :ime:connectedDebugAndroidTest`, voir docs/FIELD-TESTS.md.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Signature de release (§18.1) : la clé reste hors du dépôt et n'est lue que dans l'environnement
    // (workflow de release, ou poste du mainteneur). Sans NEXTGLYPH_KEYSTORE, l'APK de release n'est
    // pas signé : c'est le cas du contrôle de reproductibilité et de la recette F-Droid.
    val releaseKeystore = providers.environmentVariable("NEXTGLYPH_KEYSTORE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("NEXTGLYPH_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NEXTGLYPH_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("NEXTGLYPH_KEY_PASSWORD").get()
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Build reproductible (§18.1) : pas d'empreinte du commit dans l'APK, qui différerait
            // entre une copie de travail et une archive des sources.
            vcsInfo.include = false
        }
    }

    // Bloc chiffré des dépendances, lisible par Google seulement : F-Droid le signale et il empêche
    // de comparer l'APK publié à un build indépendant (§18.1, §18.3).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Les lexiques sont lus par mmap directement dans l'APK : ils ne doivent pas être compressés.
    androidResources {
        noCompress += "nglx"
        noCompress += "ngrm"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// Lexiques binaires produits par :tools:buildLexicons (configuration sortante « lexiconElements »).
val lexicons: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>("nextglyph-lexicons"))
    }
}

dependencies {
    implementation(project(":engine"))
    lexicons(project(":tools"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(testFixtures(project(":engine")))
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
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
// Chaque tâche de test reçoit aussi les manifestes de toutes les variantes
// (`nextglyph.mergedManifest.<variante>`) : le panel de test (§15.3) doit être absent de la release.
abstract class MergedManifestArgs : CommandLineArgumentProvider {
    @get:Input
    abstract val property: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf("-D${property.get()}=${manifest.get().asFile.absolutePath}")
}

androidComponents {
    onVariants { variant ->
        val unitTestName = "test${variant.name.replaceFirstChar { it.uppercase() }}UnitTest"
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        tasks.withType<Test>().configureEach {
            if (name.endsWith("UnitTest")) {
                jvmArgumentProviders.add(
                    objects.newInstance<MergedManifestArgs>().also {
                        it.property.set("nextglyph.mergedManifest.${variant.name}")
                        it.manifest.set(manifest)
                    },
                )
            }
            if (name == unitTestName) {
                jvmArgumentProviders.add(
                    objects.newInstance<MergedManifestArgs>().also {
                        it.property.set("nextglyph.mergedManifest")
                        it.manifest.set(manifest)
                    },
                )
            }
        }
    }
}

// Copie fr-lexique.nglx, fr-dicollecte.nglx et en.nglx (LexiconLanguage.ALL_FILES), ainsi que fr.ngrm et en.ngrm (mot suivant, #51),
// dans les assets de chaque variante. Les entrées viennent de la configuration « lexicons » : Gradle construit donc :tools:buildLexicons au besoin, sans copie manuelle.
abstract class CopyLexiconsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lexicons: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystem.sync {
            from(lexicons.asFileTree.matching { include("*.nglx", "*.ngrm") })
            into(outputDir)
        }
        val missing = listOf("fr-lexique.nglx", "fr-dicollecte.nglx", "en.nglx", "fr.ngrm", "en.ngrm").filterNot { outputDir.file(it).get().asFile.isFile }
        check(missing.isEmpty()) { "Lexiques absents de la sortie de :tools:buildLexicons : $missing" }
    }
}

androidComponents {
    onVariants { variant ->
        val copyLexicons = tasks.register<CopyLexiconsTask>("copy${variant.name.replaceFirstChar { it.uppercase() }}Lexicons") {
            lexicons.from(configurations.named("lexicons"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyLexicons, CopyLexiconsTask::outputDir)
    }
}
