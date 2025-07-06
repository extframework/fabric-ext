package com.kaolinmc.integrations.fabric.dependency

import com.durganmcbroom.artifact.resolver.ArtifactRepository
import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.resources.Resource
import com.durganmcbroom.resources.ResourceAlgorithm
import com.kaolinmc.archives.ArchiveHandle
import com.kaolinmc.boot.archive.ArchiveAccessTree
import com.kaolinmc.boot.archive.ArchiveData
import com.kaolinmc.boot.archive.CachedArchiveResource
import com.kaolinmc.boot.archive.ResolutionHelper
import com.kaolinmc.boot.dependency.DependencyResolver
import com.kaolinmc.boot.dependency.DependencyResolverProvider
import com.kaolinmc.boot.maven.MavenLikeResolver
import com.kaolinmc.boot.util.typeOf

internal class CurseMavenFabricModProvider(
    mavenProvider: DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings>
) : DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings> by mavenProvider {
    override val id: String = "fabric-mod:curse-maven"
    override val resolver: DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, FabricModNode<SimpleMavenDescriptor>, SimpleMavenRepositorySettings, *> =
        CurseMavenFabricModDependencyResolver(
            this::class.java.classLoader // Unused, so it doesnt matter
        )

    override fun parseRequest(request: Map<String, String>): SimpleMavenArtifactRequest? {
        val name = request["name"] ?: return null
        val projectId = request["projectId"] ?: return null
        val fileId = request["fileId"] ?: return null

        return SimpleMavenArtifactRequest(
            SimpleMavenDescriptor(
                "curse.maven",
                "$name-$projectId",
                fileId,
                null,
            ),
            includeScopes = setOf("compile", "runtime", "import")
        )
    }

    override fun parseSettings(settings: Map<String, String>): SimpleMavenRepositorySettings? {
        val releasesEnabled = settings["releasesEnabled"] ?: "true"
        val snapshotsEnabled = settings["snapshotsEnabled"] ?: "true"
        val location = settings["location"] ?: return null
        val preferredHash = settings["preferredHash"] ?: "SHA1"
        val type = settings["type"] ?: "default"

        val hashType = ResourceAlgorithm.valueOf(preferredHash)

        return when (type) {
            "default" -> SimpleMavenRepositorySettings.default(
                location,
                releasesEnabled.toBoolean(),
                snapshotsEnabled.toBoolean(),
                hashType,
                requireResourceVerification = false
            )

            "local" -> SimpleMavenRepositorySettings.local(location, hashType, requireResourceVerification = false)
            else -> return null
        }
    }
}

internal class CurseMavenFabricModDependencyResolver(
    parentClassLoader: ClassLoader,
) : DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, FabricModNode<SimpleMavenDescriptor>, SimpleMavenRepositorySettings, SimpleMavenArtifactMetadata>(
    parentClassLoader
), MavenLikeResolver<FabricModNode<SimpleMavenDescriptor>, SimpleMavenArtifactMetadata> {
    override val nodeType = typeOf<FabricModNode<SimpleMavenDescriptor>>()

    override suspend fun SimpleMavenArtifactMetadata.resource(): Resource? {
        return resource()
    }

    override val metadataType: Class<SimpleMavenArtifactMetadata> = SimpleMavenArtifactMetadata::class.java
    override val factory: RepositoryFactory<SimpleMavenRepositorySettings, ArtifactRepository<SimpleMavenRepositorySettings, SimpleMavenArtifactRequest, SimpleMavenArtifactMetadata>>
        get() = SimpleMaven
    override val id: String = "curse-fabric-mod"

    override fun constructNode(
        descriptor: SimpleMavenDescriptor,
        handle: ArchiveHandle?,
        parents: Set<FabricModNode<SimpleMavenDescriptor>>,
        accessTree: ArchiveAccessTree
    ): FabricModNode<SimpleMavenDescriptor> {
        throw UnsupportedOperationException()
    }

    override fun load(
        data: ArchiveData<SimpleMavenDescriptor, CachedArchiveResource>,
        accessTree: ArchiveAccessTree,
        helper: ResolutionHelper
    ): FabricModNode<SimpleMavenDescriptor> = FabricModNode(
        data.resources["jar.jar"]?.path,
        data.descriptor,
        accessTree
    )
}