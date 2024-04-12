rootProject.name = "fabric-ext"


pluginManagement {
    repositories {
        mavenLocal()
        maven {
            isAllowInsecureProtocol = true
            url = uri("http://maven.yakclient.net/snapshots")
        }
        gradlePluginPortal()
    }
}
include("artifact")
