plugins {
    kotlin("jvm")
}

val paperApiVersion: String by project

base {
    archivesName.set("ely4everyone-paper-bridge")
}

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
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) },
    )
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
