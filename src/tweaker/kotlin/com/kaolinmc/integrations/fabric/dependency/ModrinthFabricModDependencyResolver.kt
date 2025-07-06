package com.kaolinmc.integrations.fabric.dependency

import com.durganmcbroom.artifact.resolver.*
import com.durganmcbroom.artifact.resolver.ArtifactMetadata.Descriptor
import com.durganmcbroom.resources.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.kaolinmc.archives.ArchiveHandle
import com.kaolinmc.boot.archive.*
import com.kaolinmc.boot.dependency.DependencyNode
import com.kaolinmc.boot.dependency.DependencyResolver
import com.kaolinmc.boot.dependency.DependencyResolverProvider
import com.kaolinmc.boot.monad.Either
import com.kaolinmc.boot.monad.Tree
import com.kaolinmc.boot.util.mapAsync
import com.kaolinmc.boot.util.requireKeyInDescriptor
import com.kaolinmc.common.util.Hex
import com.kaolinmc.common.util.copyTo
import kotlinx.coroutines.awaitAll
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
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

class ModrinthArtifactRepository(
    val mcVersion: String,
    override val factory: RepositoryFactory<ModrinthRepositorySettings, ArtifactRepository<ModrinthRepositorySettings, ModrinthModArtifactRequest, ModrinthModArtifactMetadata>>

) :
    ArtifactRepository<ModrinthRepositorySettings, ModrinthModArtifactRequest, ModrinthModArtifactMetadata> {
    override val name: String = "modrinth"
    override val settings: ModrinthRepositorySettings = ModrinthRepositorySettings
    private val mapper = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .addModule(KotlinModule.Builder().build())
        .build()

    private fun encoded(str: String): String {
        return URLEncoder.encode(str, "UTF-8")
    }

    override suspend fun get(request: ModrinthModArtifactRequest): ModrinthModArtifactMetadata {
        val version = mapper.readValue<ModrinthProjectVersion>(
            try {
                URI.create(
                    MODRINTH_VERSION_ENDPOINT + request.descriptor.versionId
                ).toURL().toResource().open().toByteArray()
            } catch (_: ResourceNotFoundException) {
                throw MetadataRequestException.MetadataNotFound(
                    request.descriptor,
                    MODRINTH_VERSION_ENDPOINT + request.descriptor.versionId
                )
            }
        )

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
                    val uri = URI.create(
                        "https://api.modrinth.com/v2/project/${it.projectId}/version?" +
                                "loaders=${encoded("[\"fabric\"]")}" +
                                "&game_versions=${encoded("[\"${this.mcVersion}\"]")}",
                    )

                    val versionsResponse =
                        uri.toURL().toResource().open().toByteArray()

                    val response = mapper.readValue<List<ModrinthProjectVersionListing>>(versionsResponse)

                    response.firstOrNull()?.id
                }
                ?: throw MetadataRequestException("Failed to resolve dependency version of Modrinth project: '${it.projectId}' for project: '${request.descriptor.projectId}'")

                ModrinthModDescriptor(
                    it.projectId,
                    versionId
                )
            }
            .map(::ModrinthModArtifactRequest)
            .map { ModrinthModParentInfo(it, listOf(ModrinthRepositorySettings)) }

        return ModrinthModArtifactMetadata(
            request.descriptor,
            resource,
            parents
        )
    }
}

class Modrinth(
    val mcVersion: String,
) : RepositoryFactory<ModrinthRepositorySettings, ModrinthArtifactRepository> {
    override fun createNew(settings: ModrinthRepositorySettings): ModrinthArtifactRepository {
        return ModrinthArtifactRepository(mcVersion, this)
    }
}

class ModrinthFabricModDependencyResolver(
    classLoader: ClassLoader,
    mcVersion: String
) : DependencyResolver<ModrinthModDescriptor, ModrinthModArtifactRequest, FabricModNode<ModrinthModDescriptor>, ModrinthRepositorySettings, ModrinthModArtifactMetadata>(
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

    override suspend fun cache(
        metadata: ModrinthModArtifactMetadata,
        parents: List<Tree<Either<ModrinthModArtifactMetadata, TaggedIArchive>>>,
        helper: CacheHelper<ModrinthModDescriptor>
    ): Tree<TaggedIArchive> {
        val jar = Files.createTempFile("fabric-mod", ".jar")
        metadata.resource() copyTo jar

        helper.withResource("jar.jar", metadata.resource())

        return helper.newData(
            metadata.descriptor,
            parents.mapAsync {
                helper.cache(
                    it, this,
                )
            }.awaitAll()
        )
    }

    override suspend fun ModrinthModArtifactMetadata.resource(): Resource {
        return resource
    }

    override val apiVersion: Int = 1
    override val metadataType: Class<ModrinthModArtifactMetadata> = ModrinthModArtifactMetadata::class.java
    override val factory: RepositoryFactory<ModrinthRepositorySettings, ArtifactRepository<ModrinthRepositorySettings, ModrinthModArtifactRequest, ModrinthModArtifactMetadata>> = Modrinth(
        mcVersion
    )
    override val id: String = "modrinth-fabric-mod"

    override fun load(
        data: ArchiveData<ModrinthModDescriptor, CachedArchiveResource>,
        accessTree: ArchiveAccessTree,
        helper: ResolutionHelper
    ): FabricModNode<ModrinthModDescriptor> {
        return FabricModNode(
            data.resources["jar.jar"]?.path,
            data.descriptor,
            accessTree
        )
    }

    override fun deserializeDescriptor(
        descriptor: Map<String, String>,
        trace: ArchiveTrace
    ): ModrinthModDescriptor {
        val project = descriptor.requireKeyInDescriptor("projectId") { trace }
        val version = descriptor.requireKeyInDescriptor("versionId") { trace }

        return ModrinthModDescriptor(project, version)
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

class ModrinthFabricModProvider(
    override val resolver: DependencyResolver<ModrinthModDescriptor, ModrinthModArtifactRequest, out DependencyNode<ModrinthModDescriptor>, ModrinthRepositorySettings, *>
) :
    DependencyResolverProvider<ModrinthModDescriptor, ModrinthModArtifactRequest, ModrinthRepositorySettings> {
    override val id: String = "fabric-mod:modrinth"


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