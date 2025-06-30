package dev.extframework.integrations.fabric

import com.durganmcbroom.artifact.resolver.ArtifactRepository
import com.durganmcbroom.artifact.resolver.RepositoryFactory
import com.durganmcbroom.resources.Resource
import dev.extframework.archive.mapper.ArchiveMapping
import dev.extframework.archive.mapper.findShortest
import dev.extframework.archive.mapper.newMappingsGraph
import dev.extframework.archive.mapper.transform.ClassInheritancePath
import dev.extframework.archive.mapper.transform.ClassInheritanceTree
import dev.extframework.archive.mapper.transform.mapClassName
import dev.extframework.archive.mapper.transform.mappingTransformConfigFor
import dev.extframework.archives.ArchiveHandle
import dev.extframework.archives.ArchiveReference
import dev.extframework.archives.ArchiveTree
import dev.extframework.archives.Archives
import dev.extframework.archives.Archives.WRITER_FLAGS
import dev.extframework.archives.transform.AwareClassWriter
import dev.extframework.archives.transform.TransformerConfig
import dev.extframework.archives.zip.ZipFinder
import dev.extframework.boot.archive.*
import dev.extframework.boot.dependency.DependencyResolver
import dev.extframework.boot.monad.Either
import dev.extframework.boot.monad.Tree
import dev.extframework.boot.util.mapAsync
import dev.extframework.boot.util.requireKeyInDescriptor
import dev.extframework.common.util.LazyMap
import dev.extframework.common.util.copyTo
import dev.extframework.common.util.resolve
import dev.extframework.core.app.api.ApplicationTarget
import dev.extframework.core.minecraft.environment.mappingProvidersAttrKey
import dev.extframework.core.minecraft.environment.mappingTargetAttrKey
import dev.extframework.core.minecraft.environment.minecraft
import dev.extframework.core.minecraft.remap.MappingContext
import dev.extframework.core.minecraft.util.emptyArchiveReference
import dev.extframework.core.minecraft.util.parseNode
import dev.extframework.core.minecraft.util.write
import dev.extframework.integrations.fabric.dependency.*
import dev.extframework.integrations.fabric.mapping.FabricMappingProvider
import dev.extframework.tooling.api.environment.ExtensionEnvironment
import kotlinx.coroutines.awaitAll
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

class ModrinthSourcesFabricModDependencyResolver(
    private val environment: ExtensionEnvironment,
    private val baseDir: Path
) : DependencyResolver<ModrinthModDescriptor, ModrinthModArtifactRequest, FabricModNode<ModrinthModDescriptor>, ModrinthRepositorySettings, ModrinthModArtifactMetadata>(
    ClassLoader.getSystemClassLoader()
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

        helper.withResources(unpackFabricJar(jar).mapValues {
            Resource(it.value.toString()) {
                it.value.inputStream()
            }
        })

        // TODO obviously PRIMARY.jar is not the real mods name...
        val archives = unpackFabricJar(jar, "PRIMARY.jar").mapValues { (_, path) ->
            ZipFinder.find(path)
        }

        val graph = newMappingsGraph(environment[mappingProvidersAttrKey].toList())
        val mappings = graph.findShortest(
            FabricMappingProvider.INTERMEDIARY_NAMESPACE,
            environment[mappingTargetAttrKey].value.identifier
        ).forIdentifier(environment.minecraft.version)

        archives.toList().mapAsync { (n, archive) ->
            val remapped = remap(
                archive,
                MappingContext(
                    mappings,
                    FabricMappingProvider.INTERMEDIARY_NAMESPACE,
                    environment[mappingTargetAttrKey].value.identifier
                ),
                appInheritanceTree(
                    environment[ApplicationTarget],
                ),
                archives.map { it.value } + environment[ApplicationTarget].node.handle!!
            )

            helper.withResource(n, Resource(n) {
                ByteArrayInputStream(remapped.write())
            })
        }.awaitAll()

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
    override val factory: RepositoryFactory<ModrinthRepositorySettings, ArtifactRepository<ModrinthRepositorySettings, ModrinthModArtifactRequest, ModrinthModArtifactMetadata>> =
        Modrinth(
            environment.minecraft.version
        )
    override val id: String = "modrinth-fabric-mod"

    override fun load(
        data: ArchiveData<ModrinthModDescriptor, CachedArchiveResource>,
        accessTree: ArchiveAccessTree,
        helper: ResolutionHelper
    ): FabricModNode<ModrinthModDescriptor> {
        throw UnsupportedOperationException()
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
    ): Path = baseDir resolve
            "fabric" resolve
            "mods" resolve
            environment.minecraft.version resolve
            environment[mappingTargetAttrKey].value.path resolve
            descriptor.projectId resolve
            descriptor.versionId resolve
            "$classifier.$type"

    override fun serializeDescriptor(descriptor: ModrinthModDescriptor): Map<String, String> {
        return mapOf(
            "projectId" to descriptor.projectId,
            "versionId" to descriptor.versionId,
        )
    }

    private var appInheritanceTree: ClassInheritanceTree? = null

    fun appInheritanceTree(
        app: ApplicationTarget,
    ): ClassInheritanceTree {
        fun createPath(
            name: String
        ): ClassInheritancePath? {
            val stream =
                app.node.handle!!.classloader.getResourceAsStream(name.replace('.', '/') + ".class") ?: return null
            val node = ClassNode()
            ClassReader(stream).accept(node, 0)

            return ClassInheritancePath(
                node.name,
                node.superName?.let(::createPath),
                node.interfaces.mapNotNull { n ->
                    createPath(n)
                }
            )
        }

        if (appInheritanceTree == null) {
            appInheritanceTree = LazyMap(HashMap()) {
                createPath(
                    it
                )
            }
        }

        return appInheritanceTree!!
    }

    private fun remapInheritancePath(
        path: ClassInheritancePath,
        mappings: ArchiveMapping,
        source: String,
        target: String,
    ): ClassInheritancePath {
        return ClassInheritancePath(
            mappings.mapClassName(
                path.name,
                source,
                target,
            ) ?: path.name,
            path.superClass?.let { remapInheritancePath(it, mappings, source, target) },
            path.interfaces.map { remapInheritancePath(it, mappings, source, target) }
        )
    }

    fun remap(
        reference: ArchiveReference,
        context: MappingContext,
        appTree: ClassInheritanceTree,
        dependencies: List<ArchiveTree>
    ): ArchiveReference {
        fun inheritancePathFor(
            node: ClassNode
        ): ClassInheritancePath {
            fun getParent(name: String?): ClassInheritancePath? {
                if (name == null) return null

                val treeFromApp = appTree[context.mappings.mapClassName(
                    name,
                    context.source,
                    context.target
                ) ?: name]?.let { remapInheritancePath(it, context.mappings, context.target, context.source) }

                val treeFromRef = reference.reader["$name.class"]?.let { e ->
                    inheritancePathFor(
                        e.open().parseNode()
                    )
                }

                val treeFromDependencies = dependencies.firstNotNullOfOrNull {
                    it.getResource("$name.class")?.parseNode()?.let(::inheritancePathFor)
                }

                return treeFromApp ?: treeFromRef ?: treeFromDependencies
            }

            return ClassInheritancePath(
                node.name,
                getParent(node.superName),
                node.interfaces.mapNotNull { getParent(it) }
            )
        }

        val treeInternal = (reference.reader.entries())
            .filterNot(ArchiveReference.Entry::isDirectory)
            .filter { it.name.endsWith(".class") }
            .associate { e ->
                val path = inheritancePathFor(e.open().parseNode())
                path.name to path
            }

        val tree = LazyMap { key: String ->
            treeInternal[key] ?: appTree[
                context.mappings.mapClassName(
                    key,
                    context.source,
                    context.target
                ) ?: key
            ]?.let {
                remapInheritancePath(it, context.mappings, context.target, context.source)
            }
        }

        val config: TransformerConfig = mappingTransformConfigFor(
            context.mappings,
            context.source,
            context.target,
            tree,
        )

        val output = emptyArchiveReference()

        val lazyDependencies = LazyMap<String, ClassInheritancePath> { name ->
            dependencies.firstNotNullOfOrNull {
                it.getResource("$name.class")?.parseNode()?.let(::inheritancePathFor)
                    ?.let { remapInheritancePath(it, context.mappings, context.source, context.target) }
            }
        }

        reference.reader.entries()
            .filter { it.name.endsWith(".class") }
            .forEach { entry ->
                val writer = object : AwareClassWriter(listOf(), WRITER_FLAGS) {
                    override fun loadType(name: String): HierarchyNode {
                        fun buildNode(path: ClassInheritancePath): HierarchyNode = object : HierarchyNode {
                            override val interfaceNodes: List<HierarchyNode> by lazy { path.interfaces.map(::buildNode) }
                            override val isInterface: Boolean = false
                            override val name: String = path.name
                            override val superNode: HierarchyNode? by lazy { path.superClass?.let(::buildNode) }
                        }

                        val path = treeInternal[name] ?: appTree[name] ?: lazyDependencies[name] ?: return super.loadType(name)
                        return buildNode(path)
                    }
                }

                output.writer.put(
                    ArchiveReference.Entry(
                        entry.name,
                        entry.isDirectory,
                        output
                    ) {
                        ByteArrayInputStream(
                            Archives.applyConfig(
                                ClassReader(entry.open()),
                                config,
                                writer
                            )
                        )
                    }
                )
            }

        return output
    }
}