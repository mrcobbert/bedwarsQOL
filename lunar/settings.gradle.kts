pluginManagement {
    repositories {
        gradlePluginPortal()
        // Weave packages (loader, api, gradle plugin)
        maven("https://gitlab.com/api/v4/projects/80566527/packages/maven")
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "BedwarsQOL-Lunar"
