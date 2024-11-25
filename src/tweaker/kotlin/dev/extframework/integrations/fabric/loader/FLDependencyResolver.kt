package dev.extframework.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.Artifact
import com.durganmcbroom.artifact.resolver.ResolutionContext
import com.durganmcbroom.artifact.resolver.createContext
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.SuccessfulJob
import com.durganmcbroom.jobs.async.AsyncJob
import com.durganmcbroom.jobs.async.asyncJob
import com.durganmcbroom.jobs.async.mapAsync
import com.durganmcbroom.jobs.result
import com.durganmcbroom.resources.Resource
import dev.extframework.archives.ArchiveHandle
import dev.extframework.archives.ArchiveReference
import dev.extframework.archives.Archives
import dev.extframework.archives.ClassLoaderProvider
import dev.extframework.archives.zip.ZipResolutionResult
import dev.extframework.boot.archive.*
import dev.extframework.boot.dependency.DependencyNode
import dev.extframework.boot.dependency.DependencyResolver
import dev.extframework.boot.loader.*
import dev.extframework.boot.monad.Tagged
import dev.extframework.boot.monad.Tree
import dev.extframework.boot.util.mapOfNonNullValues
import dev.extframework.boot.util.requireKeyInDescriptor
import dev.extframework.common.util.resolve
import dev.extframework.common.util.toUrl
import dev.extframework.extension.core.target.TargetLinker
import dev.extframework.integrations.fabric.FabricIntegrationTweaker
import dev.extframework.tooling.api.environment.getOrNull
import kotlinx.coroutines.awaitAll
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.security.AllPermission
import java.security.CodeSigner
import java.security.CodeSource
import java.security.ProtectionDomain
import kotlin.io.path.Path

data class FLNode(
    override val access: ArchiveAccessTree,
    override val descriptor: FLDescriptor,
    override val handle: ArchiveHandle?,
    val packages: Set<String>
) : DependencyNode<FLDescriptor>()

data class FLLibNode(
    override val access: ArchiveAccessTree,
    override val descriptor: FLLibDescriptor,
    override val handle: ArchiveHandle?,
    val packages: Set<String>
) : DependencyNode<SimpleMavenDescriptor>()

class FLDependencyResolver private constructor(
    resolutionProvider: ArchiveResolutionProvider<*>
) : DependencyResolver<FLDescriptor, FLArtifactRequest, FLNode, SimpleMavenRepositorySettings, FLArtifactMetadata>(
    parentClassLoader = FLDependencyResolver::class.java.classLoader,
    resolutionProvider = resolutionProvider
) {
    override val metadataType: Class<FLArtifactMetadata> = FLArtifactMetadata::class.java
    override val name: String = "fl"
    internal val libResolver = FLLibDependencyResolver(resolutionProvider)
    override val apiVersion: Int = 2

    override fun deserializeDescriptor(descriptor: Map<String, String>, trace: ArchiveTrace): Result<FLDescriptor> =
        result {
            FLDescriptor(
                descriptor.requireKeyInDescriptor("version") { trace }
            )
        }

    override fun FLArtifactMetadata.resource(): Resource? {
        return jar
    }

    override fun serializeDescriptor(descriptor: FLDescriptor): Map<String, String> {
        return mapOf("version" to descriptor.version)
    }

    override fun pathForDescriptor(descriptor: FLDescriptor, classifier: String, type: String): Path {
        return Path("fabric-loader") resolve descriptor.version resolve "fabric-loader-$classifier.$type"
    }

    constructor() : this(FabricResolutionProvider())

    override fun createContext(settings: SimpleMavenRepositorySettings): ResolutionContext<SimpleMavenRepositorySettings, FLArtifactRequest, FLArtifactMetadata> {
        return FabricRepositoryFactory.createContext(settings)
    }

    override fun cache(
        artifact: Artifact<FLArtifactMetadata>,
        helper: CacheHelper<FLDescriptor>
    ): AsyncJob<Tree<Tagged<IArchive<*>, ArchiveNodeResolver<*, *, *, *, *>>>> = asyncJob {
        helper.withResource("jar.jar", artifact.metadata.resource())

        val libs = artifact.metadata.metadata.libraries
        val dependencies = (libs.common + libs.client + libs.development)
            .mapAsync { lib ->
                val request = FLLibArtifactRequest(
                    FLLibDescriptor.parseDescription(lib.name)!!,
                    lib
                )

                helper.cache(
                    request,
                    FLLibRepositorySettings,
                    libResolver
                )().merge()
            }
            .awaitAll()

        helper.newData(
            artifact.metadata.descriptor,
            dependencies
        )
    }

    override fun constructNode(
        descriptor: FLDescriptor,
        handle: ArchiveHandle?,
        parents: Set<FLNode>,
        accessTree: ArchiveAccessTree
    ): FLNode {
        return FLNode(accessTree, descriptor, handle, handle?.packages ?: setOf())
    }
}

class FLLibDependencyResolver(
    resolutionProvider: ArchiveResolutionProvider<*>

) : DependencyResolver<FLLibDescriptor, FLLibArtifactRequest, FLLibNode, FLLibRepositorySettings, FLLibArtifactMetadata>(
    parentClassLoader = FLDependencyResolver::class.java.classLoader,
    resolutionProvider = resolutionProvider
) {
    override val metadataType: Class<FLLibArtifactMetadata> = FLLibArtifactMetadata::class.java
    override val name: String = "fllib"
    override val apiVersion: Int = 2

    override fun FLLibArtifactMetadata.resource(): Resource {
        return jar
    }

    override fun deserializeDescriptor(
        descriptor: Map<String, String>,
        trace: ArchiveTrace
    ): Result<FLLibDescriptor> = result {
        FLLibDescriptor(
            descriptor.requireKeyInDescriptor("group") { trace },
            descriptor.requireKeyInDescriptor("artifact") { trace },
            descriptor.requireKeyInDescriptor("version") { trace },
            descriptor["classifier"]
        )
    }

    override fun pathForDescriptor(descriptor: FLLibDescriptor, classifier: String, type: String): Path {
        return Path(
            "fabric-loader",
            "libs",
            descriptor.group.replace('.', File.separatorChar),
            descriptor.artifact,
            descriptor.version,
            descriptor.classifier ?: "",
            "${descriptor.artifact}-${descriptor.version}-$classifier.$type"
        )
    }

    override fun serializeDescriptor(descriptor: FLLibDescriptor): Map<String, String> {
        return mapOfNonNullValues(
            "group" to descriptor.group,
            "artifact" to descriptor.artifact,
            "version" to descriptor.version,
            "classifier" to descriptor.classifier
        )
    }

    override fun createContext(settings: FLLibRepositorySettings): ResolutionContext<FLLibRepositorySettings, FLLibArtifactRequest, FLLibArtifactMetadata> {
        return FLLibRepositoryFactory.createContext(settings)
    }

    override fun constructNode(
        descriptor: FLLibDescriptor,
        handle: ArchiveHandle?,
        parents: Set<FLLibNode>,
        accessTree: ArchiveAccessTree
    ): FLLibNode {
        return FLLibNode(
            accessTree,
            descriptor,
            handle,
            handle?.packages ?: setOf()
        )
    }
}

private class FabricResolutionProvider : ArchiveResolutionProvider<ZipResolutionResult> {
    private val nameToUrl = HashMap<String, URL>()

    init {
        // Fabric is not used being loaded hierarchically - which is a being thing extframework enforces - so
        // we this resolution provider will load all classes needed to run fabric into a single classloader.
        // FabricIntegrationTweaker.tweakerEnv.update(TargetLinker) {
        // val relationship = it.targetTarget.relationship

        FabricIntegrationTweaker.fabricClassloader = FabricClassLoader(nameToUrl)
    }

    private val alreadyHave: MutableSet<String> = HashSet()

    override fun resolve(
        resource: Path,
        classLoader: ClassLoaderProvider<ArchiveReference>,
        parents: Set<ArchiveHandle>,
        trace: ArchiveTrace
    ): Job<ZipResolutionResult> {
        // Load the archive
        val ref = Archives.find(resource, Archives.Finders.ZIP_FINDER)
        // Mark it as already being loaded so we don't do that twice
        alreadyHave.add(resource.toString())

        // Add sources, classes, and resources
        FabricIntegrationTweaker.fabricClassloader.addSources(ArchiveSourceProvider(ref))
        FabricIntegrationTweaker.fabricClassloader.addClasses(
            DelegatingClassProvider(
                parents
                    .map(::ArchiveClassProvider)
            )
        )
        FabricIntegrationTweaker.fabricClassloader.addResources(ArchiveResourceProvider(ref))

        // Filter all classes and add it to the nameToUrl Map
        val url = resource.toUrl()
        ref.reader.entries()
            .filter { it.name.endsWith(".class") }
            .forEach {
                nameToUrl[it.name.replace('/', '.').removeSuffix(".class")] = url
            }

        return SuccessfulJob {
            Archives.resolve(
                ref,
                FabricIntegrationTweaker.fabricClassloader,
                Archives.Resolvers.ZIP_RESOLVER,
                parents
            )
        }
    }
}

private class FabricClassLoader(
    private val nameToUrl: Map<String, URL>
) : MutableClassLoader(
    name = "Fabric Class loader",
    MutableSourceProvider(ArrayList()),
    object : MutableClassProvider(
        mutableListOf(
            object : ClassProvider {
                private val delegate
                    get() = FabricIntegrationTweaker.tweakerEnv[TargetLinker].getOrNull()?.targetLoader
                override val packages: Set<String>
                    get() = setOf("*")

                override fun findClass(name: String): Class<*>? =
                    dev.extframework.common.util.runCatching(ClassNotFoundException::class) {
                        delegate?.loadClass(name)
                    }
            })
    ) {
        override fun findClass(name: String): Class<*>? {
            return packageMap["*"]?.firstNotNullOfOrNull { it.findClass(name) } ?: super.findClass(
                name
            )
        }
    },
    MutableResourceProvider(mutableListOf(
        object : ResourceProvider {
            override fun findResources(name: String): Sequence<URL> =
                FabricIntegrationTweaker.tweakerEnv[TargetLinker].getOrNull()?.targetLoader?.getResources(
                    name
                )?.asSequence() ?: sequenceOf()
        }
    )),
    sd = { name, bb, _, definer ->
        definer.invoke(
            name, bb, ProtectionDomain(
                CodeSource(nameToUrl[name], arrayOf<CodeSigner>()),
                AllPermission().newPermissionCollection()
            )
        )
    },
    // Parent class loader to access minecraft through the target linker.
    parent = FLDependencyResolver::class.java.classLoader
) {
    companion object {
        init {
            registerAsParallelCapable()
        }
    }

    override fun getResource(name: String): URL? {
        if (name == "mappings/mappings.tiny") return FabricIntegrationTweaker.fabricMappingsPath.toUrl()

        if (FabricIntegrationTweaker.turnOffResources && name.startsWith("net/minecraft")) return null
        return super.getResource(name)
    }
}
