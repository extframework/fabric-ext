package dev.extframework.integrations.fabric.dependency

import com.durganmcbroom.artifact.resolver.ArtifactMetadata
import com.durganmcbroom.artifact.resolver.ArtifactMetadata.Descriptor
import com.durganmcbroom.artifact.resolver.ArtifactRepository
import com.durganmcbroom.artifact.resolver.ArtifactRequest
import com.durganmcbroom.artifact.resolver.MetadataRequestException
import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.artifact.resolver.RepositorySettings
import com.durganmcbroom.artifact.resolver.ResolutionContext
import com.durganmcbroom.artifact.resolver.createContext
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.job
import com.durganmcbroom.jobs.mapException
import com.durganmcbroom.jobs.result
import com.durganmcbroom.resources.Resource
import com.durganmcbroom.resources.ResourceAlgorithm
import com.durganmcbroom.resources.ResourceNotFoundException
import com.durganmcbroom.resources.VerifiedResource
import com.durganmcbroom.resources.openStream
import com.durganmcbroom.resources.toResource
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.extframework.archives.ArchiveHandle
import dev.extframework.boot.archive.ArchiveAccessTree
import dev.extframework.boot.archive.ArchiveData
import dev.extframework.boot.archive.ArchiveTrace
import dev.extframework.boot.archive.CachedArchiveResource
import dev.extframework.boot.archive.ResolutionHelper
import dev.extframework.boot.dependency.DependencyNode
import dev.extframework.boot.dependency.DependencyResolver
import dev.extframework.boot.dependency.DependencyResolverProvider
import dev.extframework.boot.util.requireKeyInDescriptor
import dev.extframework.common.util.Hex
import dev.extframework.integrations.fabric.FabricIntegrationTweaker
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Path
import kotlin.io.path.Path

data class ModrinthProjectVersion(
    val gameVersions: List<String>,
    val loaders: List<String>,
    val id: String,
    val projectId: String,
    val authorId: String,
    val featured: Boolean,
    val name: String,
    val versionNumber: String,
    val changelog: String?,
    val changelogUrl: String?,
    val datePublished: String,
    val downloads: Int,
    val versionType: String,
    val status: String,
    val requestedStatus: String?,
    val files: List<ModrinthProjectVersionFile>,
    val dependencies: List<ModrinthProjectVersionDependency>
)

data class ModrinthProjectVersionFile(
    val hashes: ModrinthProjectVersionHashes,
    val url: String,
    val filename: String,
    val primary: Boolean,
    val size: Int,
    val fileType: String?
)

data class ModrinthProjectVersionHashes(
    val sha1: String,
    val sha512: String
)

data class ModrinthProjectVersionDependency(
    val versionId: String?,
    val projectId: String,
    val fileName: String?,
    val dependencyType: String
)

data class ModrinthProjectVersionListing(
    val id: String
)

object ModrinthRepositorySettings : RepositorySettings

data class ModrinthModDescriptor(
    val projectId: String,
    val versionId: String,
) : Descriptor {
    override val name: String = "$projectId:$versionId"
}

data class ModrinthModArtifactRequest(
    override val descriptor: ModrinthModDescriptor
) : ArtifactRequest<ModrinthModDescriptor>

typealias ModrinthModParentInfo = ArtifactMetadata.ParentInfo<ModrinthModArtifactRequest, ModrinthRepositorySettings>

class ModrinthModArtifactMetadata(
    descriptor: ModrinthModDescriptor,
    val resource: Resource,
    parents: List<ModrinthModParentInfo>
) : ArtifactMetadata<ModrinthModDescriptor, ModrinthModParentInfo>(
    descriptor,
    parents
)

const val MODRINTH_VERSION_ENDPOINT = "https://api.modrinth.com/v2/version/"

class ModrinthArtifactRepository :
    ArtifactRepository<ModrinthRepositorySettings, ModrinthModArtifactRequest, ModrinthModArtifactMetadata> {
    override val factory: RepositoryFactory<ModrinthRepositorySettings, ArtifactRepository<ModrinthRepositorySettings, ModrinthModArtifactRequest, ModrinthModArtifactMetadata>>
        get() = Modrinth
    override val name: String = "modrinth"
    override val settings: ModrinthRepositorySettings = ModrinthRepositorySettings
    private val mapper = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .addModule(KotlinModule.Builder().build())
        .build()

    private fun encoded(str: String) : String {
        return URLEncoder.encode(str, "UTF-8")
    }

    override fun get(request: ModrinthModArtifactRequest): Job<ModrinthModArtifactMetadata> = job {
        val version = mapper.readValue<ModrinthProjectVersion>(result {
            URI.create(
                MODRINTH_VERSION_ENDPOINT + request.descriptor.versionId
            ).toURL().toResource().openStream()
        }.mapException {
            if (it is ResourceNotFoundException) {
                MetadataRequestException.MetadataNotFound(
                    request.descriptor,
                    MODRINTH_VERSION_ENDPOINT + request.descriptor.versionId
                )
            } else it
        }.merge())

        val primaryFile = version.files.find {
            it.primary
        } ?: version.files.firstOrNull() ?: throw MetadataRequestException.MetadataNotFound(
            request.descriptor,
            "primary modrinth file"
        )

        val rawResource = URI.create(primaryFile.url).toURL().toResource()

        val resource = VerifiedResource(
            rawResource,
            ResourceAlgorithm.SHA1,
            Hex.parseHex(primaryFile.hashes.sha1)
        )

        val parents = version.dependencies
            .filter { it.dependencyType == "required" }
            .map {
                val versionId = it.versionId ?: run {
                    val versionsResponse =
                        URI.create(
                            "https://api.modrinth.com/v2/project/${it.projectId}/version?" +
                                    "loaders=${encoded("[\"fabric\"]")}" +
                                    "&game_versions=${encoded("[\"${FabricIntegrationTweaker.minecraftVersion}\"]")}",
                        ).toURL().toResource().openStream()

                    val response = mapper.readValue<List<ModrinthProjectVersionListing>>(versionsResponse)

                    response.firstOrNull()?.id
                } ?: throw MetadataRequestException("Failed to resolve dependency version of Modrinth project: '${it.projectId}' for project: '${request.descriptor.projectId}'")

                ModrinthModDescriptor(
                    it.projectId,
                    versionId
                )
            }
            .map(::ModrinthModArtifactRequest)
            .map { ModrinthModParentInfo(it, listOf(ModrinthRepositorySettings)) }

        ModrinthModArtifactMetadata(
            request.descriptor,
            resource,
            parents
        )
    }

}

object Modrinth : RepositoryFactory<ModrinthRepositorySettings, ModrinthArtifactRepository> {
    override fun createNew(settings: ModrinthRepositorySettings): ModrinthArtifactRepository {
        return ModrinthArtifactRepository()
    }
}

class ModrinthFabricModDependencyResolver(
    classLoader: ClassLoader
) :
    DependencyResolver<ModrinthModDescriptor, ModrinthModArtifactRequest, FabricModNode<ModrinthModDescriptor>, ModrinthRepositorySettings, ModrinthModArtifactMetadata>(
        classLoader
    ) {
    override fun constructNode(
        descriptor: ModrinthModDescriptor,
        handle: ArchiveHandle?,
        parents: Set<FabricModNode<ModrinthModDescriptor>>,
        accessTree: ArchiveAccessTree
    ): FabricModNode<ModrinthModDescriptor> {
        throw UnsupportedOperationException()
    }

    override fun ModrinthModArtifactMetadata.resource(): Resource? {
        return resource
    }

    override val metadataType: Class<ModrinthModArtifactMetadata> = ModrinthModArtifactMetadata::class.java
    override val name: String = "modrinth-fabric-mod"

    override fun createContext(settings: ModrinthRepositorySettings): ResolutionContext<ModrinthRepositorySettings, ModrinthModArtifactRequest, ModrinthModArtifactMetadata> {
        return Modrinth.createContext(settings)
    }

    override fun load(
        data: ArchiveData<ModrinthModDescriptor, CachedArchiveResource>,
        accessTree: ArchiveAccessTree,
        helper: ResolutionHelper
    ): Job<FabricModNode<ModrinthModDescriptor>> = job {
        FabricModNode(
            data.resources["jar.jar"]?.path,
            data.descriptor,
            accessTree
        )
    }

    override fun deserializeDescriptor(
        descriptor: Map<String, String>,
        trace: ArchiveTrace
    ): Result<ModrinthModDescriptor> = result {
        val project = descriptor.requireKeyInDescriptor("projectId") { trace }
        val version = descriptor.requireKeyInDescriptor("versionId") { trace }

        ModrinthModDescriptor(project, version)
    }

    override fun pathForDescriptor(
        descriptor: ModrinthModDescriptor,
        classifier: String,
        type: String
    ): Path {
        return Path(
            "modrinth",
            descriptor.projectId,
            descriptor.versionId,
            "${descriptor.projectId}-${descriptor.versionId}-$classifier.$type"
        )
    }

    override fun serializeDescriptor(descriptor: ModrinthModDescriptor): Map<String, String> {
        return mapOf(
            "projectId" to descriptor.projectId,
            "versionId" to descriptor.versionId,
        )
    }
}

internal class ModrinthFabricModProvider :
    DependencyResolverProvider<ModrinthModDescriptor, ModrinthModArtifactRequest, ModrinthRepositorySettings> {
    override val name: String = "fabric-mod:modrinth"
    override val resolver: DependencyResolver<ModrinthModDescriptor, ModrinthModArtifactRequest, out DependencyNode<ModrinthModDescriptor>, ModrinthRepositorySettings, *> =
        ModrinthFabricModDependencyResolver(this::class.java.classLoader)

    override fun parseRequest(request: Map<String, String>): ModrinthModArtifactRequest? {
        val projectId = request["projectId"] ?: return null
        val versionId = request["versionId"] ?: return null

        return ModrinthModArtifactRequest(
            ModrinthModDescriptor(
                projectId, versionId
            ),
        )
    }

    override fun parseSettings(settings: Map<String, String>): ModrinthRepositorySettings? {
        if (settings.isNotEmpty()) return null
        return ModrinthRepositorySettings
    }
}