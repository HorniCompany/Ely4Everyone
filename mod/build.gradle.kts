import java.util.Properties
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    kotlin("jvm")
}

// Per node: mod/versions/<version>/gradle.properties
val minecraftVersion: String by project
val minecraftDependency: String by project
val javaVersion: String by project
val fabricLoaderVersion: String by project
val fabricApiVersion: String by project

// Up to 1.21.11 Minecraft ships obfuscated and Loom remaps it (fabric-loom-remap, modImplementation,
// remapJar). From 26.1 it is not obfuscated: plain fabric-loom, implementation, jar.
val minecraftObfuscated: Boolean = (findProperty("minecraftObfuscated") as String?).toBoolean()
apply(plugin = if (minecraftObfuscated) "net.fabricmc.fabric-loom-remap" else "net.fabricmc.fabric-loom")
val loom = extensions.getByType<LoomGradleExtensionAPI>()
val modDependency = if (minecraftObfuscated) "modImplementation" else "implementation"
val releaseJarTask = if (minecraftObfuscated) "remapJar" else "jar"

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
        languageVersion.set(JavaLanguageVersion.of(javaVersion.toInt()))
    }
    withSourcesJar()
}

sourceSets {
    named("main") {
        java.srcDir(rootProject.file("shared-auth/src/main/kotlin"))
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    if (minecraftObfuscated) {
        "mappings"(loom.officialMojangMappings())
    }
    modDependency("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modDependency("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Resolved lazily: Loom 1.15+ forbids touching runtimeClasspath while projects are configured.
    from(
        provider {
            configurations.runtimeClasspath.get()
                .filter { file ->
                    file.name.endsWith(".jar") && (
                        file.name.startsWith("kotlin-stdlib") ||
                            file.name.startsWith("annotations-")
                        )
                }
                .map { zipTree(it) }
        },
    )
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraftDependency", minecraftDependency)
    inputs.property("javaVersion", javaVersion)
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraftDependency" to minecraftDependency,
            "javaVersion" to javaVersion,
        )
    }
}

// Game folder for runClient, relative to the node: mod/run by default. Newer versions get their own
// (runDir in the node properties) so opening a world there never upgrades the 1.21.x worlds.
val gameRunDir: String = (findProperty("runDir") as String?) ?: "../../run"
loom.runConfigs.all { runDir = gameRunDir }

val syncToLocalMods by tasks.registering(Copy::class) {
    group = "deployment"
    description = "Builds the Ely4Everyone mod jar and copies it into the configured local mods folder."

    dependsOn(tasks.named(releaseJarTask))

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


tasks.test {
    useJUnitPlatform()
    // Manual live login check, see ElyDirectLoginLiveTest. Unset = the test skips itself.
    System.getProperty("ely4everyone.liveLogin")?.let { systemProperty("ely4everyone.liveLogin", it) }
}

// Kotlin compiles with the java toolchain above (javaVersion of the node).
