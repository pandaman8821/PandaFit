// ============================================================================
//  Panda Fit - settings.gradle.kts
//  Repositorios y modulos del proyecto.
//  Android Gradle Plugin 9.x + Gradle 9.6 + Kotlin 2.3 (JBR 25 de Android
//  Studio). Verificado sobre el SDK de Android instalado en este equipo
//  (platforms: 35, 36, 37 / build-tools: 35.0.0, 36.0.0).
// ============================================================================
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PandaFit"

include(":app")
