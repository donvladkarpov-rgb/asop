pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Общая NFC/VCM1-библиотека (composite build) — см. ../asop-nfc-lib
includeBuild("../android-nfc")

rootProject.name = "asop-distributor"
include(":app")