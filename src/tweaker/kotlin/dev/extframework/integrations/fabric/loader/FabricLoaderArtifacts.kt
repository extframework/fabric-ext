package dev.extframework.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.*
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.job
import com.durganmcbroom.resources.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.extframework.common.util.Hex
import java.net.URI
import java.net.URL

@JsonIgnoreProperties(ignoreUnknown = true)
data class FabricInstallerMetadata(
    val version: Int,
    @JsonProperty("min_java_version")
    val minJavaVersion: Int,
    val libraries: FabricInstallerLibraryConfigurationMetadata
)

data class FabricInstallerLibraryConfigurationMetadata(
    val client: List<FabricInstallerLibraryMetadata>,
    val common: List<FabricInstallerLibraryMetadata>,
    val server: List<FabricInstallerLibraryMetadata>,
    val development: List<FabricInstallerLibraryMetadata>,
)

data class FabricInstallerLibraryMetadata(
    val name: String,
    val url: String,
    val md5: String,
    val sha1: String,
    val sha256: String,
    val sha512: String,
    val size: Int
)

data class FLDescriptor(
    val version: String
) : ArtifactMetadata.Descriptor {
    override val name: String = "fabric-loader v$version"
}

data class FLArtifactRequest(
    override val descriptor: FLDescriptor
) : ArtifactRequest<FLDescriptor>

class FLArtifactMetadata(
    descriptor: FLDescriptor,
    val jar: Resource,
    val metadata: FabricInstallerMetadata,
) : ArtifactMetadata<FLDescriptor, ArtifactMetadata.ParentInfo<FLArtifactRequest, SimpleMavenRepositorySettings>>(
    descriptor,
    listOf()
)

class FLArtifactRepository(override val settings: SimpleMavenRepositorySettings) :
    ArtifactRepository<SimpleMavenRepositorySettings, FLArtifactRequest, FLArtifactMetadata> {
    override val factory: RepositoryFactory<SimpleMavenRepositorySettings, ArtifactRepository<SimpleMavenRepositorySettings, FLArtifactRequest, FLArtifactMetadata>>
        get() = FabricRepositoryFactory
    override val name: String = "fl"


    private val mapper = ObjectMapper().registerModule(
        KotlinModule.Builder().build()
    )

    override fun get(request: FLArtifactRequest): Job<FLArtifactMetadata> {
        val desc by request::descriptor

        return job {
            val installerMetadataResource =
                settings.layout.resourceOf("net.fabricmc", "fabric-loader", desc.version, null, "json")().merge()
            val installerMetadata = mapper.readValue<FabricInstallerMetadata>(installerMetadataResource.openStream())

            FLArtifactMetadata(
                desc,
                settings.layout.resourceOf("net.fabricmc", "fabric-loader", desc.version, null, "jar")().merge(),
                installerMetadata
            )
        }
    }
}

object FabricRepositoryFactory :
    RepositoryFactory<SimpleMavenRepositorySettings, FLArtifactRepository> {
    override fun createNew(settings: SimpleMavenRepositorySettings): FLArtifactRepository {
        return FLArtifactRepository(settings)
    }
}


// LIBS

typealias FLLibDescriptor = SimpleMavenDescriptor

data class FLLibArtifactRequest(
    override val descriptor: FLLibDescriptor,
    val lib: FabricInstallerLibraryMetadata
) : ArtifactRequest<FLLibDescriptor>


class FLLibArtifactMetadata(
    descriptor: FLLibDescriptor,
    val jar: Resource,
) : ArtifactMetadata<FLLibDescriptor, ArtifactMetadata.ParentInfo<FLLibArtifactRequest, FLLibRepositorySettings>>(
    descriptor,
    listOf()
)

class FLLibArtifactRepository :
    ArtifactRepository<FLLibRepositorySettings, FLLibArtifactRequest, FLLibArtifactMetadata> {
    override val factory: RepositoryFactory<FLLibRepositorySettings, ArtifactRepository<FLLibRepositorySettings, FLLibArtifactRequest, FLLibArtifactMetadata>>
        get() = FLLibRepositoryFactory
    override val name: String = "fllib"
    override val settings: FLLibRepositorySettings = FLLibRepositorySettings


    override fun get(request: FLLibArtifactRequest): Job<FLLibArtifactMetadata> = job {
        val repository = SimpleMavenRepositorySettings.default(url = request.lib.url)

        val descriptor = SimpleMavenDescriptor.parseDescription(request.lib.name)!!

        val resource = repository.layout.resourceOf(
            descriptor.group,
            descriptor.artifact,
            descriptor.version,
            null,
            "jar"
        )().merge()

        FLLibArtifactMetadata(
            request.descriptor,
            resource
        )
    }

}

object FLLibRepositorySettings : RepositorySettings

object FLLibRepositoryFactory : RepositoryFactory<FLLibRepositorySettings, FLLibArtifactRepository> {
    override fun createNew(settings: FLLibRepositorySettings): FLLibArtifactRepository =
        FLLibArtifactRepository()
}