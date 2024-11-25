package dev.extframework.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import dev.extframework.boot.dependency.DependencyResolverProvider
import dev.extframework.integrations.fabric.fabricRepository

internal class FabricLoaderDependencyResolverProvider :
    DependencyResolverProvider<FLDescriptor, FLArtifactRequest, SimpleMavenRepositorySettings> {
    override val resolver = FLDependencyResolver()
    override val name: String = "fl"
    override fun parseRequest(request: Map<String, String>): FLArtifactRequest? {
        if (request.keys != setOf("fl-version")) return null

        return FLArtifactRequest(
            FLDescriptor(
                request["fl-version"]!!
            )
        )
//
//        return SimpleMavenArtifactRequest(
//            SimpleMavenDescriptor(
//                "net.fabricmc",
//                "fabric-loader",
//                request["fl-version"] ?: return null,
//                null
//            ),
//            includeScopes = setOf("compile", "runtime", "import"),
//            excludeArtifacts = setOf(
//                "jackson-databind",
//                "jackson-core",
//                "jackson-dataformat-xml",
//                "log4j-api",
//                "jackson-dataformat-yaml",
//                "asm",
//                "asm-debug-all",
//                "asm-analysis",
//                "asm-tree",
//                "asm-util",
//                "gson",
//                "lwjgl",
//            ) + FabricIntegrationTweaker.tweakerEnv[ApplicationTarget].extract().node.access.targets.mapNotNull {
//                (it.descriptor as? SimpleMavenDescriptor)?.name
//            }
//        )
    }

    override fun parseSettings(settings: Map<String, String>): SimpleMavenRepositorySettings? {
        if (settings.isNotEmpty()) return null
        return fabricRepository
    }
}
