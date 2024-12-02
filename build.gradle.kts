import dev.extframework.gradle.*
import dev.extframework.gradle.common.*
import dev.extframework.gradle.common.dm.artifactResolver
import dev.extframework.gradle.common.dm.jobs
import dev.extframework.gradle.deobf.MinecraftMappings
import dev.extframework.gradle.publish.ExtensionPublication
import dev.extframework.internal.api.extension.ExtensionRepository
import kotlin.jvm.java

plugins {
    kotlin("jvm") version "1.9.21"

    id("maven-publish")
    id("dev.extframework.mc") version "1.2.24"
    id("dev.extframework.common") version "1.0.37"
}

tasks.wrapper {
    gradleVersion = "8.6-rc-1"
}

group = "dev.extframework.integrations"
version = "1.0.1-BETA"

val fabricLoaderVersion = "0.16.9"

tasks.launch {
    targetNamespace.set("mojang:deobfuscated")
    mcVersion.set("1.21.3")
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

repositories {
    mavenLocal()
    mavenCentral()
    extframework()
    maven {
        url = uri("https://maven.fabricmc.net/")
    }
    maven {
        url = uri("https://maven.neoforged.net/releases")
    }
    maven {
        url = uri("https://libraries.minecraft.net")
    }
    maven {
        url = uri("https://repo.extframework.dev/registry")
    }
}

dependencies {
    implementation("io.github.llamalad7:mixinextras-fabric:0.4.1")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    archives()
    commonUtil()
    boot()
    artifactResolver(maven = true)
    archiveMapper(transform = true, tiny = true)
    toolingApi()
    extLoader(version = "2.1.10-SNAPSHOT")

    implementation("net.fabricmc:tiny-remapper:0.8.2")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("cpw.mods:modlauncher:10.1.9")
    implementation("net.fabricmc:sponge-mixin:0.12.5+mixin.0.8.5") {
        exclude(group = "org.ow2.asm")
    }
    implementation("net.fabricmc:mapping-io:0.5.0") {
        isTransitive = false
    }

    implementation("org.ow2.asm:asm-commons:9.6")

    testImplementation(kotlin("test"))
}

extension {
//    extensions {
//        require("dev.extframework.extension:core-mc:1.0.15-BETA")
//    }
    partitions {
        tweaker {
            tweakerClass = "dev.extframework.integrations.fabric.FabricIntegrationTweaker"
            dependencies {
                commonUtil()
                objectContainer()
                boot()
                archives(mixin = true)
                jobs()
                artifactResolver(maven = true)
                archiveMapper()
                toolingApi()
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
            }
        }

        main {
            model {
                dependencies.addAll(
                    mutableMapOf(
                        "fl-version" to fabricLoaderVersion,
                    ),
                )

                repositories.addAll(
                    ExtensionRepository(
                        "fl",
                        mutableMapOf()
                    )
                )
            }
            extensionClass = "dev.extframework.integrations.fabric.FabricIntegration"
            dependencies {
                coreApi()
                archiveMapper(tiny = true, proguard = true)
                implementation("net.minecraft:launchwrapper:1.12")
            }
        }
    }

    metadata {
        name = "Fabric Integration"
        description = "An extension that brings the fabric ecosystem to extframework"
        developers.add("extframework")
    }
}

publishing {
    publications {
        create("prod", ExtensionPublication::class.java)
    }
    repositories {
        maven {
            url = uri("https://repo.extframework.dev")
            credentials {
                password = properties["creds.ext.key"] as? String
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}