package dev.extframework.integrations.fabric.dependency

import com.durganmcbroom.artifact.resolver.Artifact
import com.durganmcbroom.artifact.resolver.ResolutionContext
import com.durganmcbroom.artifact.resolver.createContext
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.async.AsyncJob
import com.durganmcbroom.jobs.job
import com.durganmcbroom.resources.Resource
import com.durganmcbroom.resources.ResourceAlgorithm
import dev.extframework.archives.ArchiveHandle
import dev.extframework.boot.archive.*
import dev.extframework.boot.dependency.DependencyResolver
import dev.extframework.boot.dependency.DependencyResolverProvider
import dev.extframework.boot.maven.MavenLikeResolver
import dev.extframework.boot.monad.Tagged
import dev.extframework.boot.monad.Tree
import dev.extframework.boot.util.typeOf
import java.nio.file.Path

internal class CurseMavenFabricModProvider(
    mavenProvider: DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings>
) : DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings> by mavenProvider {
    override val name: String = "fabric-mod:curse-maven"
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
    override fun SimpleMavenArtifactMetadata.resource(): Resource? {
        return resource
    }

    override val metadataType: Class<SimpleMavenArtifactMetadata> = SimpleMavenArtifactMetadata::class.java
    override val name: String = "curse-fabric-mod"
    override fun createContext(settings: SimpleMavenRepositorySettings): ResolutionContext<SimpleMavenRepositorySettings, SimpleMavenArtifactRequest, SimpleMavenArtifactMetadata> {
        return SimpleMaven.createContext(settings)
    }

    override fun cache(
        artifact: Artifact<SimpleMavenArtifactMetadata>,
        helper: CacheHelper<SimpleMavenDescriptor>
    ): AsyncJob<Tree<Tagged<IArchive<*>, ArchiveNodeResolver<*, *, *, *, *>>>> {
        return super.cache(artifact, helper)
    }

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
    ): Job<FabricModNode<SimpleMavenDescriptor>> = job {
        FabricModNode(
            data.resources["jar.jar"]?.path,
            data.descriptor,
            accessTree
        )
    }
}