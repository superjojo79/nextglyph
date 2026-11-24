pluginManagement {
    repositories {
        // Dépôt Google : plugin Android et ses dépendances de build uniquement.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Outils Android et AndroidX (Apache-2.0) ; aucune bibliothèque Play Services / Firebase.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// Planchers de sécurité pour des dépendances transitives des outils de build (alertes Dependabot).
// Elles viennent du classpath du plugin Android (AGP 9.5.1 : bundletool, jetifier, sdklib, apkzlib…)
// et des configurations de lint du module ime : rien de tout cela n'est embarqué dans l'APK. Une
// version inférieure au plancher est remontée au plancher ; une version supérieure (montée d'AGP par
// exemple) est conservée telle quelle. Retirer une entrée dès que les plugins tirent d'eux-mêmes une
// version corrigée (vérifier avec `./gradlew buildEnvironment` et `./gradlew :ime:dependencies`).
val securityFloors = mapOf(
    // Contournement des contraintes de nom (critique), garde de profondeur ASN.1, algorithmes faibles.
    "org.bouncycastle:bcprov-jdk18on" to "1.87",
    "org.bouncycastle:bcpkix-jdk18on" to "1.87",
    "org.bouncycastle:bcutil-jdk18on" to "1.87",
    // Allocation mémoire non bornée à la désérialisation (CompactHashMap…).
    "com.google.guava:guava" to "33.8.0-jre",
    // XXE (CVE-2021-33813).
    "org.jdom:jdom2" to "2.0.6.1",
    // Déni de service par JWE compressé (CVE-2023-51775).
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    // Récursion non contrôlée sur entrées longues (CVE-2025-48924).
    "org.apache.commons:commons-lang3" to "3.18.0",
    // XSS (CVE-2020-13956).
    "org.apache.httpcomponents:httpclient" to "4.5.13",
)

// Compare deux versions Maven sur leurs composantes numériques (33.4.0-jre < 33.8.0-jre,
// 2.0.6 < 2.0.6.1) ; suffisant pour les versions ci-dessus.
fun isBelow(version: String, floor: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(version)
    val b = parts(floor)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

fun Configuration.applySecurityFloors() {
    resolutionStrategy.eachDependency {
        val floor = securityFloors["${requested.group}:${requested.name}"]
        val version = requested.version
        if (floor != null && !version.isNullOrEmpty() && isBelow(version, floor)) {
            useVersion(floor)
            because("plancher de sécurité (alerte Dependabot), voir settings.gradle.kts")
        }
    }
}

gradle.beforeProject {
    buildscript.configurations.configureEach { applySecurityFloors() }
    configurations.configureEach { applySecurityFloors() }
}

rootProject.name = "nextglyph"

include(":engine")
include(":tools")
include(":ime")
