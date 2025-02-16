rootProject.name = "fabric-ext"

pluginManagement {
    repositories {
        mavenLocal()
        maven {
            url = uri("https://maven.extframework.dev/releases")
        }
        maven {
            url = uri("https://maven.extframework.dev/snapshots")
        }
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include("mappings")
findProject(":mappings")?.name = "fabric-mappings"

