pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        if (providers.gradleProperty("usePublishedUpdater").orNull == "true") mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "kmp-updater"
include(":updater")

include(":updater-compose", ":sample-shared", ":sample-android", ":sample-desktop")
