package net.yakclient.integrations.fabric.dependency

import com.durganmcbroom.artifact.resolver.ArtifactMetadata
import com.durganmcbroom.artifact.resolver.ArtifactReference
import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.jobs.JobResult
import com.durganmcbroom.jobs.jobScope
import net.yakclient.archives.ArchiveHandle
import net.yakclient.archives.zip.classLoaderToArchive
import net.yakclient.boot.archive.*
import net.yakclient.boot.dependency.DependencyNode
import net.yakclient.boot.dependency.DependencyResolver
import net.yakclient.boot.dependency.DependencyResolverProvider
import net.yakclient.boot.loader.ArchiveResourceProvider
import net.yakclient.boot.loader.ArchiveSourceProvider
import net.yakclient.boot.loader.DelegatingClassProvider
import net.yakclient.boot.loader.IntegratedLoader
import net.yakclient.boot.maven.MavenLikeResolver
import net.yakclient.boot.util.requireKeyInDescriptor
import net.yakclient.common.util.resolve
import net.yakclient.integrations.fabric.FabricIntegrationTweaker
import java.nio.file.Path
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate

internal class FabricModDependencyResolverProvider(
    basePath: Path,
    mavenProvider: DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings>
) : DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings> by mavenProvider {
    override val name: String = "fabric-mod"
    override val resolver: DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, out DependencyNode<*>, SimpleMavenRepositorySettings, *> =
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
}

public class FabricModNode(
    val path: Path,
    override val descriptor: ArtifactMetadata.Descriptor,
    override val parents: Set<FabricModNode>,
    override val access: ArchiveAccessTree,
    override val resolver: ArchiveNodeResolver<*, *, FabricModNode, *, *>
) : DependencyNode<FabricModNode>() {
    override val archive: ArchiveHandle by lazy { classLoaderToArchive(FabricIntegrationTweaker.fabricClassloader) }
}

internal class FabricModDependencyResolver(
    private val basePath: Path,
    parentClassLoader: ClassLoader,
) : DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, FabricModNode, SimpleMavenRepositorySettings, SimpleMavenArtifactMetadata>(
    parentClassLoader
),
    MavenLikeResolver<SimpleMavenArtifactRequest, FabricModNode, SimpleMavenRepositorySettings, SimpleMavenArtifactMetadata> {
    override val factory: RepositoryFactory<SimpleMavenRepositorySettings, SimpleMavenArtifactRequest, *, ArtifactReference<SimpleMavenArtifactMetadata, *>, *> =
        SimpleMaven
    override val metadataType: Class<SimpleMavenArtifactMetadata> = SimpleMavenArtifactMetadata::class.java
    override val name: String = "fabric-mod"

//    override f

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

    override suspend fun load(
        data: ArchiveData<SimpleMavenDescriptor, CachedArchiveResource>,
        helper: ResolutionHelper
    ): JobResult<FabricModNode, ArchiveException> = jobScope {
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