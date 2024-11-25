rootProject.name = "fabric-ext"

pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.extframework.dev/releases")
        }
        maven {
            url = uri("https://maven.extframework.dev/snapshots")
        }
        mavenLocal()
        gradlePluginPortal()
    }
}
