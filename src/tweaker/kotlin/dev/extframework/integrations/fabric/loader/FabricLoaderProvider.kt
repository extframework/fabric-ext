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
    }

    override fun parseSettings(settings: Map<String, String>): SimpleMavenRepositorySettings? {
        if (settings.isNotEmpty()) return null
        return fabricRepository
    }
}
