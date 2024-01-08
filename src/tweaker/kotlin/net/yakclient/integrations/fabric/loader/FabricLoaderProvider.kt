package net.yakclient.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import net.yakclient.boot.dependency.DependencyResolverProvider
import net.yakclient.integrations.fabric.fabricRepository

internal class FabricLoaderDependencyResolverProvider :
    DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings> {
    override val resolver
        get() = FabricLoaderDependencyGraph()
    override val name: String = "fabric-loader"
    override fun parseRequest(request: Map<String, String>): SimpleMavenArtifactRequest? {
        return SimpleMavenArtifactRequest(
            SimpleMavenDescriptor(
                "net.fabricmc",
                "fabric-loader",
                request["fl-version"] ?: return null,
                null
            ),
            includeScopes = setOf("compile", "runtime", "import"),
            excludeArtifacts = setOf(
                "jackson-databind",
                "jackson-core",
                "jackson-dataformat-xml",
                "log4j-api",
                "jackson-dataformat-yaml",
                "asm",
                "asm-debug-all",
                "asm-analysis",
                "asm-tree",
                "asm-util",
                "gson",
                "lwjgl",
//                "logging"
            )
        )
    }

    override fun parseSettings(settings: Map<String, String>): SimpleMavenRepositorySettings {
        return fabricRepository
    }
}
