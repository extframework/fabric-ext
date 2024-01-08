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
import net.yakclient.boot.archive.ArchiveException
import net.yakclient.boot.archive.ArchiveResolutionProvider
import net.yakclient.boot.dependency.DependencyNode
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
import kotlin.collections.HashSet
import kotlin.reflect.KClass

class FabricLoaderDependencyGraph :
    DependencyResolver<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings, SimpleMavenRepositoryStub, SimpleMavenArtifactMetadata>(
        object : ArchiveResolutionProvider<ZipResolutionResult> {
            // Fabric is not used being loaded hierarchically - which is a being thing that yakclient enforces - so
            // we this resolution provider will load all classes needed to run fabric into a single classloader.

            private val nameToUrl = HashMap<String, URL>()
            val delegateSources = ArrayList<SourceProvider>()
            private val sources = MutableSourceProvider(delegateSources)
            private val classes = MutableClassProvider(ArrayList())
            private val classLoader = object : IntegratedLoader(
                sp = sources,
                cp = classes,
                sd = { name, bb, cl, definer ->
                    definer.invoke(
                        name, bb, ProtectionDomain(
                            CodeSource(nameToUrl[name], arrayOf<CodeSigner>()),
                            AllPermission().newPermissionCollection()
                        )
                    )
                },
                parent = IntegratedLoader(
                    sp = FabricIntegrationTweaker.tweakerEnv[TargetLinker]!!.targetSourceProvider,
                    cp = FabricIntegrationTweaker.tweakerEnv[TargetLinker]!!.targetClassProvider,
                    parent = FabricLoaderDependencyGraph::class.java.classLoader
                )
            ) {
                override fun findClass(moduleName: String?, name: String): Class<*>? {
                    return findLoadedClass(name)
                }

                override fun getResource(name: String): URL? {
                    if (name == "mappings/mappings.tiny") return FabricIntegrationTweaker.fabricMappingsPath.toUrl()

//                   if (FabricIntegrationTweaker.turnOffResources && !name.startsWith("org/spongepowered")) return null
                    if (FabricIntegrationTweaker.turnOffResources && name.startsWith("net/minecraft")) return null
                    return super.getResource(name)
                }

                override fun findResources(name: String): Enumeration<URL> {
                    val enum = Vector<URL>()

                    delegateSources.mapNotNull {
                        it.getResource(name)
                    }.forEach {
                        enum.add(it)
                    }

                    return enum.elements()
                }
            }.also { FabricIntegrationTweaker.fabricClassloader = it }

            private val alreadyHave : MutableSet<String> = HashSet()

            override suspend fun resolve(
                resource: Path,
                classLoader: ClassLoaderProvider<ArchiveReference>,
                parents: Set<ArchiveHandle>
            ): JobResult<ZipResolutionResult, ArchiveException> {
                val ref = Archives.find(resource, Archives.Finders.ZIP_FINDER)
                alreadyHave.add(resource.toString())

                sources.add(DelegatingSourceProvider(listOf(ArchiveSourceProvider(ref))))
                classes.add(DelegatingClassProvider(parents
                    .filterNot { alreadyHave.add(it.toString()) }
                    .map(::ArchiveClassProvider)))

                val url = resource.toUrl()
                ref.reader.entries()
                    .filter { it.name.endsWith(".class") }
                    .forEach {
                        nameToUrl[it.name.replace('/', '.').removeSuffix(".class")] = url
                    }

                val result = Archives.resolve(
                    ref,
                    this.classLoader,
                    Archives.Resolvers.ZIP_RESOLVER,
                    parents
                )

                return JobResult.Success(result)
            }
        },
    ),
    MavenLikeResolver<SimpleMavenArtifactRequest, DependencyNode, SimpleMavenRepositorySettings, SimpleMavenRepositoryStub, SimpleMavenArtifactMetadata> {
    override val factory: RepositoryFactory<SimpleMavenRepositorySettings, SimpleMavenArtifactRequest, *, ArtifactReference<SimpleMavenArtifactMetadata, *>, *> =
        FabricRepositoryFactory
    override val metadataType: KClass<SimpleMavenArtifactMetadata> = SimpleMavenArtifactMetadata::class
    override val name: String = "fabric-loader"
}