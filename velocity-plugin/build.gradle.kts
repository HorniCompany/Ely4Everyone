plugins {
    kotlin("jvm")
}

val velocityApiVersion: String by project

base {
    archivesName.set("ely4everyone-velocity-plugin")
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
    named("test") {
        java.srcDir("../shared-auth/src/test/kotlin")
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
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
    filesMatching("velocity-plugin.json") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
