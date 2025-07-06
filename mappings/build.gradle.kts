import com.kaolinmc.gradle.common.archiveMapper
import com.kaolinmc.gradle.common.archiveMapperTiny
import com.kaolinmc.kiln.publish.ExtensionPublication


group = "com.kaolinmc.integrations"
version = "1.0.4-BETA"

extension {
    partitions {
        tweaker {
            dependencies {
                implementation(archiveMapper())
                implementation(archiveMapperTiny())
            }
            tweakerClass = "com.kaolinmc.integrations.fabric.mapping.FabricMappingsTweaker"
        }
    }
    metadata {
        name = "Fabric Mappings"
        description = "An extension that brings Fabric Intermediary mappings to Kaolin"
        developers.add("kaolin")
        app = "minecraft"
    }
}

publishing {
    publications {
        create("prod", ExtensionPublication::class.java)
    }
    repositories {
        maven {
            url = uri("https://repo.kaolinmc.com")
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