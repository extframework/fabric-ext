package com.kaolinmc.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.ArtifactRepository
import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.resources.Resource
import com.kaolinmc.archives.ArchiveHandle
import com.kaolinmc.archives.ArchiveReference
import com.kaolinmc.archives.Archives
import com.kaolinmc.archives.ClassLoaderProvider
import com.kaolinmc.archives.zip.ZipResolutionResult
import com.kaolinmc.boot.archive.*
import com.kaolinmc.boot.dependency.DependencyNode
import com.kaolinmc.boot.dependency.DependencyResolver
import com.kaolinmc.boot.loader.*
import com.kaolinmc.boot.monad.Either
import com.kaolinmc.boot.monad.Tree
import com.kaolinmc.boot.util.mapAsync
import com.kaolinmc.boot.util.mapOfNonNullValues
import com.kaolinmc.boot.util.requireKeyInDescriptor
import com.kaolinmc.common.util.resolve
import com.kaolinmc.common.util.toUrl
import com.kaolinmc.core.app.TargetLinker
import com.kaolinmc.integrations.fabric.FabricIntegrationTweaker
import com.kaolinmc.common.util.runCatching
import kotlinx.coroutines.awaitAll
import net.fabricmc.loader.impl.util.FileSystemUtil
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
    override val id: String = "fl"
    internal val libResolver = FLLibDependencyResolver(resolutionProvider)
    override val apiVersion: Int = 2
    override val factory: RepositoryFactory<SimpleMavenRepositorySettings, ArtifactRepository<SimpleMavenRepositorySettings, FLArtifactRequest, FLArtifactMetadata>>
        get() = FabricRepositoryFactory

    override fun deserializeDescriptor(descriptor: Map<String, String>, trace: ArchiveTrace): FLDescriptor =
        FLDescriptor(
            descriptor.requireKeyInDescriptor("version") { trace }
        )

    override suspend fun FLArtifactMetadata.resource(): Resource? {
        return jar
    }

    override fun serializeDescriptor(descriptor: FLDescriptor): Map<String, String> {
        return mapOf("version" to descriptor.version)
    }

    override fun pathForDescriptor(descriptor: FLDescriptor, classifier: String, type: String): Path {
        return Path("fabric-loader") resolve descriptor.version resolve "fabric-loader-$classifier.$type"
    }

    constructor() : this(FabricResolutionProvider())

    override suspend fun cache(
        metadata: FLArtifactMetadata,
        parents: List<Tree<Either<FLArtifactMetadata, TaggedIArchive>>>,
        helper: CacheHelper<FLDescriptor>
    ): Tree<TaggedIArchive> {
        helper.withResource(
            "jar.jar",
            metadata.resource()
        )

        val libs = metadata.metadata.libraries
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
                )
            }
            .awaitAll()

        return helper.newData(
            metadata.descriptor,
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
    override val id: String = "fllib"
    override val apiVersion: Int = 2
    override val factory: RepositoryFactory<FLLibRepositorySettings, ArtifactRepository<FLLibRepositorySettings, FLLibArtifactRequest, FLLibArtifactMetadata>>
        get() = FLLibRepositoryFactory


    override suspend fun FLLibArtifactMetadata.resource(): Resource {
        return jar
    }

    override fun deserializeDescriptor(
        descriptor: Map<String, String>,
        trace: ArchiveTrace
    ): FLLibDescriptor =
        FLLibDescriptor(
            descriptor.requireKeyInDescriptor("group") { trace },
            descriptor.requireKeyInDescriptor("artifact") { trace },
            descriptor.requireKeyInDescriptor("version") { trace },
            descriptor["classifier"]
        )

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
        // Fabric is not used being loaded hierarchically - which is a being thing Kaolin enforces - so
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
    ): ZipResolutionResult {
        // Load the archive
        val ref = Archives.find(resource, Archives.Finders.ZIP_FINDER)


        /*
        START SECTION COMMENT


        Ok im going to try to explain why we do the following. For whatever reason, the jar
        file system in java is not natively thread safe. This means that when opening and closing
        the same jar file system repeatedly across multiple threads there will be instances
        in which already closed file system will be returned when they are meant to be open,
        or open file systems will be closed without the consumers' knowledge. I think this is defined
        and expected behavior inside of java, but fabric-loader does not account for this properly
        and ONLY during remapping (in which mods are asynchronously remapped) this occurs and creates
        a race condition in which a ClosedFileSystemException is thrown. This was actually documented
        and fixed in loom, but not the fml itself (see issue #633 in FabricMC/fabric-loom on GH).

        At the end (after adding all file systems to a list) we run through and close them all.
        */

        val fsUtilName = FileSystemUtil::class.java.name.replace('.', '/') + ".class"
        if (ref.reader.contains(fsUtilName)) {
            ref.writer.put(
                ArchiveReference.Entry(
                    fsUtilName,
                    false,
                    ref
                ) {
                    FileSystemUtil::class.java.getResourceAsStream("/$fsUtilName")
                }
            )
        }

        val fsUtilDelegateName = FileSystemUtil.FileSystemDelegate::class.java.name.replace('.', '/') + ".class"
        if (ref.reader.contains(fsUtilDelegateName)) {
            ref.writer.put(
                ArchiveReference.Entry(
                    fsUtilDelegateName,
                    false,
                    ref
                ) {
                    FileSystemUtil::class.java.getResourceAsStream("/$fsUtilDelegateName")
                }
            )
        }

        /*
        END SECTION COMMENT
         */

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

        return Archives.resolve(
            ref,
            FabricIntegrationTweaker.fabricClassloader,
            Archives.Resolvers.ZIP_RESOLVER,
            parents
        )
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
                    get() = FabricIntegrationTweaker.tweakerEnv[TargetLinker].targetLoader
                override val packages: Set<String>
                    get() = setOf("*")

                override fun findClass(name: String): Class<*>? =
                    runCatching(ClassNotFoundException::class) {
                        delegate.loadClass(name)
                    }
            })
    ) {
        override fun findClass(name: String): Class<*>? {
            return packageMap["*"]?.firstNotNullOfOrNull { it.findClass(name) } ?: super.findClass(
                name
            )
        }
    },
    MutableResourceProvider(
        mutableListOf(
            object : ResourceProvider {
                override fun findResources(name: String): Sequence<URL> =
                    FabricIntegrationTweaker.tweakerEnv[TargetLinker].targetLoader.getResources(
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
