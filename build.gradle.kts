import dev.extframework.gradle.api.EvaluatingDependency
import dev.extframework.gradle.common.archiveMapper
import dev.extframework.gradle.common.extFramework
import dev.extframework.gradle.publish.ExtensionPublication
import dev.extframework.minecraft.MojangNamespaces
import dev.extframework.minecraft.minecraft
import dev.extframework.minecraft.task.LaunchMinecraft
import dev.extframework.tooling.api.extension.ExtensionRepository
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"

    id("maven-publish")
    id("dev.extframework") version "1.3.3"
    id("dev.extframework.common") version "1.0.53"
}

tasks.wrapper {
    gradleVersion = "8.6-rc-1"
}

group = "dev.extframework.integrations"
version = "1.0.5-BETA"

val fabricLoaderVersion = "0.16.10"

val publishAll by tasks.creating {
    listOf(
        ":",
        ":mappings"
    ).forEach {
        dependsOn(project(it).tasks.named("publishExtension"))
    }
}

val launch1_21_4 by tasks.registering(LaunchMinecraft::class) {
    dependsOn(tasks.named("publishToMavenLocal"))
    dependsOn(project("mappings").tasks.named("publishToMavenLocal"))
    targetNamespace = MojangNamespaces.obfuscated.identifier
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    mcVersion = "1.21.4"
}

extension {
    finalizedBy {
        model {
            partition("fabric-loader") {
                dependencies.addAll(
                    EvaluatingDependency.Raw(
                        mapOf(
                            "fl-version" to fabricLoaderVersion,
                        )
                    )
                )

                repositories.addAll(
                    ExtensionRepository(
                        "fl",
                        mutableMapOf()
                    )
                )
            }
        }
    }

    partitions {
        tweaker {
            tweakerClass = "dev.extframework.integrations.fabric.FabricIntegrationTweaker"
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
            }
        }
        gradle {
            entrypointClass = "dev.extframework.integrations.fabric.FabricGradleEntrypoint"
            dependencies {
                implementation("dev.extframework:gradle-api:1.0.1-BETA")
                implementation(gradleApi())
            }
        }
        minecraft("fabric-loader") {
            mappings = MojangNamespaces.obfuscated
            entrypoint = "dev.extframework.integrations.fabric.FabricIntegration"
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

                archiveMapper(transform = true, tiny = true)

                implementation("org.ow2.asm:asm-commons:9.6")
            }
            supportVersions("1.21.4")
        }
    }

    metadata {
        name = "Fabric Integration"
        description = "An extension that brings the fabric ecosystem to extframework"
        developers.add("extframework")
        app = "minecraft"
    }
}

dependencies {
    "fabric-loaderImplementation"("io.github.llamalad7:mixinextras-fabric:0.4.1")

    "fabric-loaderImplementation"("net.fabricmc:tiny-remapper:0.8.2")
    "fabric-loaderImplementation"("net.fabricmc:fabric-loader:$fabricLoaderVersion")
//    implementation("cpw.mods:modlauncher:10.1.9")
    "fabric-loaderImplementation"("net.fabricmc:sponge-mixin:0.12.5+mixin.0.8.5") {
        exclude(group = "org.ow2.asm")
    }
    "fabric-loaderImplementation"("net.fabricmc:mapping-io:0.5.0") {
        isTransitive = false
    }

    testImplementation(kotlin("test"))
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

tasks.test {
    useJUnitPlatform()
}

tasks.compileKotlin {
    kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.named<KotlinCompile>("compileFabric-loaderKotlin") {
    kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.named<JavaCompile>(sourceSets.named("fabric-loader").get().compileJavaTaskName) {
    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}

tasks.compileJava {
    javaCompiler.set(
        javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}

kotlin {
    jvmToolchain(8)
}

allprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "dev.extframework")
    apply(plugin = "dev.extframework.common")

    repositories {
        mavenCentral()
        extFramework()
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
}