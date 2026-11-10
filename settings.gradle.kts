pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
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
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "Ely4Everyone"

include(":mod")
include(":shared-auth")
include(":velocity-plugin")
include(":paper-plugin")

stonecutter {
    create(":mod") {
        versions("1.21.11")
        vcsVersion = "1.21.11"
    }
}
