plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.19.4" apply false
    id("net.fabricmc.fabric-loom") version "1.19.4" apply false
}

group = "dev.ely4everyone"
version = "0.2.1-alpha"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
