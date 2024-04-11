import net.yakclient.gradle.*

plugins {
    kotlin("jvm") version "1.9.21"

    id("maven-publish")
    id("net.yakclient") version "1.1"
}

tasks.wrapper {
    gradleVersion = "8.6-rc-1"
}

group = "net.yakclient.integrations"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        isAllowInsecureProtocol = true
        url = uri("http://maven.yakclient.net/snapshots")
    }
    maven {
        url = uri("https://maven.fabricmc.net/")
    }
    maven {
        url = uri("https://maven.neoforged.net/releases")
    }
    maven {
        url = uri("https://libraries.minecraft.net")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("net.fabricmc:tiny-remapper:0.8.2")
    implementation("net.yakclient.components:minecraft-bootstrapper:1.0-SNAPSHOT")
    implementation("net.fabricmc:fabric-loader:0.15.3")
    implementation("net.yakclient:client-api:1.0-SNAPSHOT")

    implementation("cpw.mods:modlauncher:10.1.9")
    implementation("net.fabricmc:sponge-mixin:0.12.5+mixin.0.8.5") {
        exclude(group = "org.ow2.asm")
    }
    implementation("net.fabricmc:mapping-io:0.5.0") {
        isTransitive = false
    }
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
    implementation("net.minecraft:launchwrapper:1.12")

    implementation("net.fabricmc:sponge-mixin:0.12.5+mixin.0.8.5")
    implementation("net.yakclient.components:ext-loader:1.0-SNAPSHOT")


    implementation("net.yakclient:archives:1.2-SNAPSHOT")

    implementation("net.fabricmc:sponge-mixin:0.12.5+mixin.0.8.5")
    implementation("net.yakclient:common-util:1.1-SNAPSHOT")
    implementation("net.yakclient:boot:2.1-SNAPSHOT")
    implementation("com.durganmcbroom:artifact-resolver:1.1-SNAPSHOT")
    implementation("com.durganmcbroom:artifact-resolver-simple-maven:1.1-SNAPSHOT")
    implementation("org.ow2.asm:asm-commons:9.6")

    implementation("net.yakclient:archive-mapper:1.2.1-SNAPSHOT")
    implementation("net.yakclient:archive-mapper-transform:1.2.1-SNAPSHOT")


    testImplementation("net.yakclient.components:ext-loader:1.0-SNAPSHOT")
    testImplementation("net.yakclient:common-util:1.1-SNAPSHOT")
    testImplementation(kotlin("test"))
}

tasks.launch {
    targetNamespace.set("mojang:deobfuscated")
    jvmArgs(
        "-XstartOnFirstThread",
        "-Xmx3G",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseG1GC",
        "-XX:G1NewSizePercent=20",
        "-XX:G1ReservePercent=20",
        "-XX:MaxGCPauseMillis=50",
        "-XX:G1HeapRegionSize=32M"
    )
}

yakclient {
    model {
        groupId = "net.yakclient.integrations"
        name = "fabric-ext"
        version = "1.0-SNAPSHOT"

        extensionRepositories {
            yakclient()
        }

        partitions {
            repositories {
                yakclient()
                mavenCentral()
            }
            if (type == "main") {
                dependencies.add(
                    mutableMapOf(
                        "fl-version" to "0.15.3",
                    )
                )
                repositories {
                    add(
                        MutableExtensionRepository(
                            "fabric-loader",
                            mutableMapOf()
                        )
                    )

                }
            }
        }
    }

    partitions {
        tweaker {
            tweakerClass = "net.yakclient.integrations.fabric.FabricIntegrationTweaker"
            dependencies {
                implementation("net.yakclient.components:minecraft-bootstrapper:1.0-SNAPSHOT")
                implementation("net.yakclient.components:ext-loader:1.0-SNAPSHOT")
                implementation("net.yakclient:common-util:1.1-SNAPSHOT")
                implementation("net.yakclient:object-container:1.0-SNAPSHOT")
                implementation("net.yakclient:boot:2.1-SNAPSHOT")
                implementation("net.yakclient:archives:1.2-SNAPSHOT")
                implementation("com.durganmcbroom:jobs:1.2-SNAPSHOT")
                implementation("com.durganmcbroom:artifact-resolver-simple-maven:1.1-SNAPSHOT")
                implementation("com.durganmcbroom:artifact-resolver:1.1-SNAPSHOT")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
                implementation("net.yakclient:archive-mapper:1.2.1-SNAPSHOT")
                implementation("net.yakclient:archives-mixin:1.2-SNAPSHOT")
            }
        }

        main {
            extensionClass = "net.yakclient.integrations.fabric.FabricIntegration"
            dependencies {
                implementation("net.yakclient:archive-mapper-tiny:1.2.1-SNAPSHOT")
                implementation("net.yakclient:archive-mapper-proguard:1.2.1-SNAPSHOT")
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("prod") {
            artifact(tasks.jar)
            artifact(tasks.generateErm) {
                classifier = "erm"
            }

            groupId = "net.yakclient.integrations"
            artifactId = "fabric-ext"
        }
    }
    repositories {
        maven {
            val repo = if (project.findProperty("isSnapshot") == "true") "snapshots" else "releases"

            isAllowInsecureProtocol = true

            url = uri("http://maven.yakclient.net/$repo")

            credentials {
                val user = project.findProperty("maven.user") as String?
                if (user == null) System.err.println("Couldnt find maven user")
                else println("Found maven user: '$user'")
                username = user

                val userKey = project.findProperty("maven.key") as String?
                if (user == null) System.err.println("Couldnt find maven key")
                else println("Found maven key")
                password = userKey
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.test {
    useJUnitPlatform()
}