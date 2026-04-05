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

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    compileOnly(files("libs/FastLoginVelocity.jar"))
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.0.3")
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
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
