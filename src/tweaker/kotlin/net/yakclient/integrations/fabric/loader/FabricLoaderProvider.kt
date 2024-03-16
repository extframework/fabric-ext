package net.yakclient.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import net.yakclient.boot.dependency.DependencyResolverProvider
import net.yakclient.components.extloader.api.environment.extract
import net.yakclient.components.extloader.api.target.ApplicationTarget
import net.yakclient.integrations.fabric.FabricIntegrationTweaker
import net.yakclient.integrations.fabric.fabricRepository

internal class FabricLoaderDependencyResolverProvider :
    DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings> {
    override val resolver = FabricLoaderDependencyGraph()
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
            ) + FabricIntegrationTweaker.tweakerEnv[ApplicationTarget].extract().reference.dependencyReferences.map {
                val path = it.location.path

                val substring = path.substring(
                    path.lastIndexOf("/") + 1,
                    path.lastIndexOf(".jar")
                )
                substring.substring(0 until substring.lastIndexOf("-"))
            }
        )
    }

    override fun parseSettings(settings: Map<String, String>): SimpleMavenRepositorySettings {
        return fabricRepository
    }
}
