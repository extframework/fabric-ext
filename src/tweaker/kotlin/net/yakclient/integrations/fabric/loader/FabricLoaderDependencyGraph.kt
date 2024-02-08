package net.yakclient.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.ArtifactReference
import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.artifact.resolver.simple.maven.*
import com.durganmcbroom.jobs.JobResult
import net.yakclient.archives.ArchiveHandle
import net.yakclient.archives.ArchiveReference
import net.yakclient.archives.Archives
import net.yakclient.archives.ClassLoaderProvider
import net.yakclient.archives.zip.ZipResolutionResult
import net.yakclient.boot.archive.ArchiveAccessTree
import net.yakclient.boot.archive.ArchiveException
import net.yakclient.boot.archive.ArchiveResolutionProvider
import net.yakclient.boot.dependency.BasicDependencyNode
import net.yakclient.boot.dependency.DependencyResolver
import net.yakclient.boot.loader.*
import net.yakclient.boot.maven.MavenLikeResolver
import net.yakclient.common.util.toUrl
import net.yakclient.components.extloader.target.TargetLinker
import net.yakclient.integrations.fabric.FabricIntegrationTweaker
import java.net.URL
import java.nio.file.Path
import java.security.AllPermission
import java.security.CodeSigner
import java.security.CodeSource
import java.security.ProtectionDomain
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

class FabricLoaderDependencyGraph :
    DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, BasicDependencyNode, SimpleMavenRepositorySettings, SimpleMavenArtifactMetadata>(
        parentClassLoader = FabricLoaderDependencyGraph::class.java.classLoader,
        resolutionProvider = object : ArchiveResolutionProvider<ZipResolutionResult> {
            private val nameToUrl = HashMap<String, URL>()

            init {
                // Fabric is not used being loaded hierarchically - which is a being thing that yakclient enforces - so
                // we this resolution provider will load all classes needed to run fabric into a single classloader.
                val relationship = FabricIntegrationTweaker.tweakerEnv[TargetLinker]!!.targetTarget.relationship
                FabricIntegrationTweaker.fabricClassloader = object : MutableClassLoader(
                    name = "Fabric Class loader",
                    MutableSourceProvider(ArrayList()),
                    MutableClassProvider(mutableListOf(relationship.classes)),
                    MutableResourceProvider(mutableListOf(relationship.resources)),
                    sd = { name, bb, _, definer ->
                        definer.invoke(
                            name, bb, ProtectionDomain(
                                CodeSource(nameToUrl[name], arrayOf<CodeSigner>()),
                                AllPermission().newPermissionCollection()
                            )
                        )
                    },
                    // Parent class loader to access minecraft through the target linker.
                    parent = FabricLoaderDependencyGraph::class.java.classLoader

//                    IntegratedLoader(
//                        name = "Fabric Class loader parent",
//                        resourceProvider = FabricIntegrationTweaker.tweakerEnv[TargetLinker]!!.targetTarget.relationship.resources,
//                        classProvider = FabricIntegrationTweaker.tweakerEnv[TargetLinker]!!.targetTarget.relationship.classes,
//                        parent = FabricLoaderDependencyGraph::class.java.classLoader
//                    )
                ) {
                    override fun getResource(name: String): URL? {
                        if (name == "mappings/mappings.tiny") return FabricIntegrationTweaker.fabricMappingsPath.toUrl()

                        if (FabricIntegrationTweaker.turnOffResources && name.startsWith("net/minecraft")) return null
                        return super.getResource(name)
                    }
                }
            }

            private val alreadyHave: MutableSet<String> = HashSet()

            override suspend fun resolve(
                resource: Path,
                classLoader: ClassLoaderProvider<ArchiveReference>,
                parents: Set<ArchiveHandle>
            ): JobResult<ZipResolutionResult, ArchiveException> {
                // Load the archive
                val ref = Archives.find(resource, Archives.Finders.ZIP_FINDER)
                // Mark it as already being loaded so we don't do that twice
                alreadyHave.add(resource.toString())

                // Add sources, classes, and resources
                FabricIntegrationTweaker.fabricClassloader.addSources(ArchiveSourceProvider(ref))
                FabricIntegrationTweaker.fabricClassloader.addClasses(DelegatingClassProvider(parents
                    .filterNot { alreadyHave.add(it.toString()) }
                    .map(::ArchiveClassProvider)))
                FabricIntegrationTweaker.fabricClassloader.addResources(ArchiveResourceProvider(ref))

                // Filter all classes and add it to the nameToUrl Map
                val url = resource.toUrl()
                ref.reader.entries()
                    .filter { it.name.endsWith(".class") }
                    .forEach {
                        nameToUrl[it.name.replace('/', '.').removeSuffix(".class")] = url
                    }

                val result = Archives.resolve(
                    ref,
                    FabricIntegrationTweaker.fabricClassloader,
                    Archives.Resolvers.ZIP_RESOLVER,
                    parents
                )

                return JobResult.Success(result)
            }
        },
    ),
    MavenLikeResolver<SimpleMavenArtifactRequest, BasicDependencyNode, SimpleMavenRepositorySettings, SimpleMavenArtifactMetadata> {
    override val factory: RepositoryFactory<SimpleMavenRepositorySettings, SimpleMavenArtifactRequest, *, ArtifactReference<SimpleMavenArtifactMetadata, *>, *> =
        FabricRepositoryFactory
    override val metadataType: Class<SimpleMavenArtifactMetadata> = SimpleMavenArtifactMetadata::class.java
    override val name: String = "fabric-loader"
    override fun constructNode(
        descriptor: SimpleMavenDescriptor,
        handle: ArchiveHandle?,
        parents: Set<BasicDependencyNode>,
        accessTree: ArchiveAccessTree
    ): BasicDependencyNode {
        return BasicDependencyNode(
            descriptor, handle, parents, accessTree, this
        )
    }
}