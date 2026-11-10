pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
        mavenCentral()
    }
}

plugins {
    // Downloads the JDKs the build asks for (Java 25 for Loom 1.15+ and Minecraft 26.x) into the Gradle cache.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "Ely4Everyone"

include(":mod")
include(":shared-auth")
include(":velocity-plugin")
include(":paper-plugin")
