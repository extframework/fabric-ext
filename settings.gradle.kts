rootProject.name = "fabric-ext"

pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.kaolinmc.com/releases")
        }
        maven {
            url = uri("https://maven.kaolinmc.com/snapshots")
        }
        gradlePluginPortal()
        mavenLocal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
include("mappings")
