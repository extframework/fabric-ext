package net.yakclient.integrations.fabric.loader

import com.durganmcbroom.artifact.resolver.ResolutionContext
import com.durganmcbroom.artifact.resolver.createContext
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactMetadata
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.SuccessfulJob
import net.yakclient.archives.ArchiveHandle
import net.yakclient.archives.ArchiveReference
import net.yakclient.archives.Archives
import net.yakclient.archives.ClassLoaderProvider
import net.yakclient.archives.zip.ZipResolutionResult
import net.yakclient.boot.archive.ArchiveAccessTree
import net.yakclient.boot.archive.ArchiveResolutionProvider
import net.yakclient.boot.dependency.BasicDependencyNode
import net.yakclient.boot.dependency.DependencyResolver
import net.yakclient.boot.loader.*
import net.yakclient.boot.maven.MavenLikeResolver
import net.yakclient.common.util.toUrl
import net.yakclient.components.extloader.api.environment.defer
import net.yakclient.components.extloader.api.environment.extract
import net.yakclient.components.extloader.api.environment.getOrNull
import net.yakclient.components.extloader.target.TargetLinker
import net.yakclient.components.extloader.util.defer
import net.yakclient.integrations.fabric.FabricIntegrationTweaker
import java.net.URL
import java.nio.file.Path
import java.security.AllPermission
import java.security.CodeSigner
import java.security.CodeSource
import java.security.ProtectionDomain

class FabricLoaderDependencyGraph :
    DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, BasicDependencyNode, SimpleMavenRepositorySettings, SimpleMavenArtifactMetadata>(
        parentClassLoader = FabricLoaderDependencyGraph::class.java.classLoader,
        resolutionProvider = object : ArchiveResolutionProvider<ZipResolutionResult> {
            private val nameToUrl = HashMap<String, URL>()

            init {
                // Fabric is not used being loaded hierarchically - which is a being thing that yakclient enforces - so
                // we this resolution provider will load all classes needed to run fabric into a single classloader.
//                FabricIntegrationTweaker.tweakerEnv.update(TargetLinker) {
//                val relationship = it.targetTarget.relationship

                FabricIntegrationTweaker.fabricClassloader =
                    object : MutableClassLoader(
                        name = "Fabric Class loader",
                        MutableSourceProvider(ArrayList()),
                        MutableClassProvider(
                            mutableListOf(
                                object : ClassProvider {
                                    private val delegate
                                        get() = FabricIntegrationTweaker.tweakerEnv[TargetLinker].getOrNull()?.targetTarget?.relationship?.classes
                                    override val packages: Set<String>
                                        get() = delegate?.packages ?: hashSetOf()

                                    override fun findClass(name: String): Class<*>? = delegate?.findClass(name)
                                })
                        ),
                        MutableResourceProvider(mutableListOf(
                            object : ResourceProvider {
                                override fun findResources(name: String): Sequence<URL> =
                                    FabricIntegrationTweaker.tweakerEnv[TargetLinker].getOrNull()?.targetTarget?.relationship?.resources?.findResources(
                                        name
                                    ) ?: sequenceOf()
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
                        parent = FabricLoaderDependencyGraph::class.java.classLoader
                    ) {
                        override fun getResource(name: String): URL? {
                            if (name == "mappings/mappings.tiny") return FabricIntegrationTweaker.fabricMappingsPath.toUrl()

                            if (FabricIntegrationTweaker.turnOffResources && name.startsWith("net/minecraft")) return null
                            return super.getResource(name)
                        }
                    }
            }

            private val alreadyHave: MutableSet<String> = HashSet()

            override fun resolve(
                resource: Path,
                classLoader: ClassLoaderProvider<ArchiveReference>,
                parents: Set<ArchiveHandle>
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
        },
    ), MavenLikeResolver<BasicDependencyNode, SimpleMavenArtifactMetadata> {
    override val metadataType: Class<SimpleMavenArtifactMetadata> = SimpleMavenArtifactMetadata::class.java
    override val name: String = "fabric-loader"
    override fun createContext(settings: SimpleMavenRepositorySettings): ResolutionContext<SimpleMavenArtifactRequest, *, SimpleMavenArtifactMetadata, *> {
        return FabricRepositoryFactory.createContext(settings)
    }

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