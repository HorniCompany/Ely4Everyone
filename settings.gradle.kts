pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        mavenCentral()
    }
}

plugins {
    // Downloads the JDKs the build asks for (Java 25 for Loom 1.15+ and Minecraft 26.x) into the Gradle cache.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // One mod source tree for several Minecraft versions: mod/versions/<version>/ are the builds.
    id("dev.kikugie.stonecutter") version "0.10"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "Ely4Everyone"

include(":mod")

stonecutter {
    create(":mod") {
        versions("1.21.11", "26.1.2", "26.2", "26.3")
        vcsVersion = "1.21.11"
    }
}
