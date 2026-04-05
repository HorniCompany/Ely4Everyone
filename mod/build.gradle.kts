plugins {
    id("fabric-loom")
    kotlin("jvm")
}

import java.util.Properties

val minecraftVersion: String by project
val yarnMappings: String by project
val fabricLoaderVersion: String by project
val fabricApiVersion: String by project
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val localModsDir = localProperties.getProperty("ely4everyone.modsDir")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

base {
    archivesName.set("ely4everyone-mod")
}

val localModJarPattern = "${base.archivesName.get()}-*.jar"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

sourceSets {
    named("main") {
        java.srcDir("../shared-auth/src/main/kotlin")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(
        configurations.runtimeClasspath.get()
            .filter { file ->
                file.name.endsWith(".jar") && (
                    file.name.startsWith("kotlin-stdlib") ||
                        file.name.startsWith("annotations-")
                    )
            }
            .map { zipTree(it) },
    )
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val syncToLocalMods by tasks.registering(Copy::class) {
    group = "deployment"
    description = "Builds the Ely4Everyone mod jar and copies it into the configured local mods folder."

    dependsOn(tasks.named("remapJar"))

    from(layout.buildDirectory.dir("libs")) {
        include(localModJarPattern)
        exclude("*-sources.jar", "*-dev.jar")
    }

    onlyIf {
        if (localModsDir == null) {
            logger.lifecycle(
                "Skipping syncToLocalMods: set ely4everyone.modsDir in ${rootProject.file("local.properties")}.",
            )
            false
        } else {
            true
        }
    }

    into(
        providers.provider {
            file(localModsDir ?: error("ely4everyone.modsDir is not configured"))
        },
    )

    doFirst {
        val sourceJars = fileTree(layout.buildDirectory.dir("libs").get().asFile) {
            include(localModJarPattern)
            exclude("*-sources.jar", "*-dev.jar")
        }
        check(sourceJars.files.isNotEmpty()) {
            "No built Ely4Everyone mod jar was found in ${layout.buildDirectory.dir("libs").get().asFile}."
        }

        val targetDir = file(localModsDir ?: error("ely4everyone.modsDir is not configured"))
        targetDir.mkdirs()
        delete(
            fileTree(targetDir) {
                include(localModJarPattern)
            },
        )
    }

    doLast {
        val targetDir = file(localModsDir ?: error("ely4everyone.modsDir is not configured"))
        val deployedJars = fileTree(targetDir) {
            include(localModJarPattern)
        }.files
            .sortedBy { it.name }
            .joinToString { it.name }
        logger.lifecycle("Synced $deployedJars to $targetDir")
    }
}

val buildAndSyncToLocalMods by tasks.registering {
    group = "deployment"
    description = "Runs the mod build and syncs the resulting jar into the configured local mods folder."
    dependsOn(tasks.named("build"))
}

tasks.named("build") {
    finalizedBy(syncToLocalMods)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
