plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
    id("fabric-loom") version "1.14.10" apply false
}

group = "dev.ely4everyone"
version = "0.1.0-alpha"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
