import dev.extframework.gradle.common.archiveMapper
import dev.extframework.gradle.common.archiveMapperTiny
import dev.extframework.gradle.publish.ExtensionPublication


group = "dev.extframework.integrations"
version = "1.0.4-BETA"

extension {
    partitions {
        tweaker {
            dependencies {
                implementation(archiveMapper())
                implementation(archiveMapperTiny())
            }
            tweakerClass = "dev.extframework.integrations.fabric.mapping.FabricMappingsTweaker"
        }
    }
    metadata {
        name = "Fabric Mappings"
        description = "An extension that brings Fabric Intermediary mappings to extframework"
        developers.add("extframework")
        app = "minecraft"
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

kotlin {
    jvmToolchain(8)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}