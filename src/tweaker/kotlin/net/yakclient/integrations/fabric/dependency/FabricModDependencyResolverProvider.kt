package net.yakclient.integrations.fabric.dependency

import com.durganmcbroom.artifact.resolver.ArtifactMetadata
import com.durganmcbroom.artifact.resolver.ResolutionContext
import com.durganmcbroom.artifact.resolver.createContext
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.job
import com.durganmcbroom.resources.ResourceAlgorithm
import net.yakclient.archives.ArchiveHandle
import net.yakclient.archives.zip.classLoaderToArchive
import net.yakclient.boot.archive.*
import net.yakclient.boot.dependency.DependencyNode
import net.yakclient.boot.dependency.DependencyResolver
import net.yakclient.boot.dependency.DependencyResolverProvider
import net.yakclient.boot.maven.MavenLikeResolver
import net.yakclient.common.util.resolve
import net.yakclient.integrations.fabric.FabricIntegrationTweaker
import java.nio.file.Path

internal class FabricModDependencyResolverProvider(
    basePath: Path,
    mavenProvider: DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings>
) : DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings> by mavenProvider {
    override val name: String = "fabric-mod"
    override val resolver: DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, FabricModNode, SimpleMavenRepositorySettings, *> =
        FabricModDependencyResolver(
            basePath,
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

class FabricModNode(
    val path: Path,
    override val descriptor: ArtifactMetadata.Descriptor,
    override val parents: Set<FabricModNode>,
    override val access: ArchiveAccessTree,
    override val resolver: ArchiveNodeResolver<*, *, FabricModNode, *, *>
) : DependencyNode<FabricModNode>() {
    override val archive: ArchiveHandle = classLoaderToArchive(FabricIntegrationTweaker.fabricClassloader)
}

internal class FabricModDependencyResolver(
    private val basePath: Path,
    parentClassLoader: ClassLoader,
) : DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, FabricModNode, SimpleMavenRepositorySettings, SimpleMavenArtifactMetadata>(
    parentClassLoader
), MavenLikeResolver<FabricModNode, SimpleMavenArtifactMetadata> {
    override val nodeType = FabricModNode::class.java
    override val metadataType: Class<SimpleMavenArtifactMetadata> = SimpleMavenArtifactMetadata::class.java
    override val name: String = "fabric-mod"
    override fun createContext(settings: SimpleMavenRepositorySettings): ResolutionContext<SimpleMavenArtifactRequest, *, SimpleMavenArtifactMetadata, *> {
        return SimpleMaven.createContext(settings)
    }

    override fun constructNode(
        descriptor: SimpleMavenDescriptor,
        handle: ArchiveHandle?,
        parents: Set<FabricModNode>,
        accessTree: ArchiveAccessTree
    ): FabricModNode {
        return FabricModNode(
            basePath resolve pathForDescriptor(descriptor, "jar", "jar"),
            descriptor, parents, accessTree, this
        )
    }

    override fun load(
        data: ArchiveData<SimpleMavenDescriptor, CachedArchiveResource>,
        helper: ResolutionHelper
    ): Job<FabricModNode> = job {
        val parents: Set<FabricModNode> = data.parents.mapTo(HashSet()) {
            helper.load(it.descriptor, this@FabricModDependencyResolver)
        }

        val access = helper.newAccessTree {
            allDirect(parents)
        }

        constructNode(
            data.descriptor,
            null,
            parents,
            access,
        )
    }
}