import dev.extframework.gradle.common.archiveMapper
import dev.extframework.gradle.publish.ExtensionPublication


group = "dev.extframework.integrations"
version = "1.0.2-BETA"

extension {
    partitions {
        tweaker {
            dependencies {
                archiveMapper(tiny = true)
            }
            tweakerClass = "dev.extframework.integrations.fabric.mapping.FabricMappingsTweaker"
        }
    }
    metadata {
        name = "Fabric Mappings"
        description = "An extension that brings Fabric Intermediary mappings to extframework"
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

kotlin {
    jvmToolchain(8)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}