package net.yakclient.integrations.fabric

import com.durganmcbroom.resources.streamToResource
import net.fabricmc.api.EnvType
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.fabricmc.loader.impl.FormattedException
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatch
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnot
import net.fabricmc.loader.impl.lib.accesswidener.AccessWidenerClassVisitor
import net.fabricmc.loader.impl.transformer.FabricTransformer
import net.fabricmc.loader.impl.util.SystemProperties
import net.fabricmc.mappingio.format.tiny.Tiny1FileWriter
import net.minecraft.launchwrapper.Launch
import net.minecraft.launchwrapper.LaunchClassLoader
import net.yakclient.archive.mapper.ArchiveMapping
import net.yakclient.archive.mapper.findShortest
import net.yakclient.archive.mapper.newMappingsGraph
import net.yakclient.archive.mapper.parsers.tiny.write
import net.yakclient.archive.mapper.transform.transformArchive
import net.yakclient.archives.ArchiveReference
import net.yakclient.archives.ArchiveTree
import net.yakclient.archives.Archives
import net.yakclient.archives.transform.AwareClassWriter
import net.yakclient.archives.zip.classLoaderToArchive
import net.yakclient.boot.loader.ArchiveSourceProvider
import net.yakclient.boot.loader.ClassProvider
import net.yakclient.boot.loader.DelegatingSourceProvider
import net.yakclient.client.api.Extension
import net.yakclient.common.util.make
import net.yakclient.common.util.resolve
import net.yakclient.common.util.toBytes
import net.yakclient.components.extloader.api.environment.*
import net.yakclient.components.extloader.api.target.ApplicationTarget
import net.yakclient.components.extloader.api.target.ExtraClassProviderAttribute
import net.yakclient.components.extloader.extension.mapping.MojangExtensionMappingProvider.Companion.REAL_TYPE
import net.yakclient.components.extloader.target.TargetLinker
import net.yakclient.integrations.fabric.dependency.FabricModNode
import net.yakclient.integrations.fabric.util.mapNamespaces
import net.yakclient.integrations.fabric.util.write
import net.yakclient.minecraft.bootstrapper.MinecraftClassTransformer
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.util.asm.ASM
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class FabricIntegration : Extension() {
    override fun cleanup() {}

    // Btw, this system is really fucking cool
    override fun init() {
        val startTime = System.currentTimeMillis()

        val mappingsProviders = FabricIntegrationTweaker.tweakerEnv[mappingProvidersAttrKey].extract()
        val rawIntermediaryProvider =
            RawFabricMappingProvider(FabricIntegrationTweaker.tweakerEnv[WorkingDirectoryAttribute].extract().path resolve "mappings" resolve "raw-intermediary")
        val intermediaryProvider = FabricMappingProvider(rawIntermediaryProvider)
        mappingsProviders.add(intermediaryProvider)

        if (FabricIntegrationTweaker.fabricMappingsPath.make()) {
            val intermediaryToOfficialMappings = createIntermediaryToOfficialMappings(
                FabricIntegrationTweaker.tweakerEnv, FabricIntegrationTweaker.minecraftVersion,
            ).mapNamespaces(
                REAL_TYPE to "named",
                FabricMappingProvider.INTERMEDIARY_NAMESPACE to "intermediary"
            )

            write(
                Tiny1FileWriter(FileWriter(FabricIntegrationTweaker.fabricMappingsPath.toFile())),
                intermediaryToOfficialMappings,
                // Yes, this is reverse. We do this to trick fabric into mapping all references from intermediary
                // into official (not actually official, just whatever we are currently running int)
                "named",
                "intermediary",
            )
        }

        // Sponge incorrectly detects minor versions of ASM in this version,
        // So we just patch it in here. Not a great solution but works for now
        ASM::class.java.getDeclaredField("minorVersion").apply {
            trySetAccessible()
        }.set(null, 6)
        ASM::class.java.getDeclaredField("implMinorVersion").apply {
            trySetAccessible()
        }.set(null, 6)

        val target = FabricIntegrationTweaker.tweakerEnv[ApplicationTarget].extract()
        val appRef = target.reference

        System.setProperty(
            SystemProperties.ADD_MODS,
            FabricIntegrationTweaker.tweakerEnv.archiveGraph.values.filterIsInstance<FabricModNode>()
                .joinToString(separator = File.pathSeparator) { it.path.toString() }
        )

        // There's going to be a lot loaded by the fabric system that isnt part of
        // whats its regular classpath will look like. Since they prevent all class loading
        // from not declared classpaths, its easiest just to turn off that feature
        System.setProperty("fabric.debug.disableClassPathIsolation", "true")

        // Tell fabric to skip the default Mc Provider and instead use ours
        System.setProperty("fabric.skipMcProvider", "true")
        // Tell fabric where the game jar is
        System.setProperty(
            "fabric.gameJarPath.client",
            appRef.reference.location.path
        )

        // Loading the target (minecraft) and then transforming it from official to intermediary mappings
        // For fabric to use as the remap-classpath.
        val targetLocation = appRef.reference.location
        val newTargetRef = Archives.find(Path.of(targetLocation), Archives.Finders.ZIP_FINDER)
        val mappings = rawIntermediaryProvider.forIdentifier(appRef.descriptor.version)
        transformArchive(
            newTargetRef,
            appRef.dependencyReferences,
            mappings,
            "official",
            "intermediary"
        )
        val tmpMappedTarget = Files.createTempFile("mc-target", ".jar")
        newTargetRef.write(tmpMappedTarget)

        val remapClasspath = Files.createTempFile("remap-classpath", "txt")

        remapClasspath.writeText(tmpMappedTarget.toString())
        // Fabric wants a file pointing to the remap-classpath, so thats what we do above.
        System.setProperty(SystemProperties.REMAP_CLASSPATH_FILE, remapClasspath.toString())

        // Setup a launch classloader and blackboard as temporary values, will error if they are not present
        Launch.classLoader = LaunchClassLoader(arrayOf())
        Launch.blackboard = HashMap()

        // Set the context classloader to this thread for service-loading of our game provider
        Thread.currentThread().contextClassLoader = this::class.java.classLoader
        // Force fabric to setup its exception handler.
        FabricLauncherBase::class.java.getDeclaredMethod("setupUncaughtExceptionHandler")
            .apply { trySetAccessible() }
            .invoke(null)

        try {
            // Create the yakclient launcher
            val launcher = YakclientLauncher(EnvType.CLIENT)

            // Start the launcher
            launcher.init(arrayOf())

            // Get a mixin transformer for later use
            val knotTransformer: IMixinTransformer = MixinServiceKnot::class.java.getDeclaredMethod(
                "getTransformer"
            ).apply { trySetAccessible() }.invoke(null) as IMixinTransformer

            val allTargetRefs = appRef.dependencyReferences + appRef.reference
            val allClasses = DelegatingSourceProvider(
                allTargetRefs.map {
                    ArchiveSourceProvider(it)
                }
            )

            // Create a minecraft Game provider and setup its version data, we need to do this
            // for use in the entrypoint patch which sets up fabric mods entrypoints. The only part
            // of the minecraft game provider it consumes is version data, so thats all we set.
            val minecraftGameProvider = MinecraftGameProvider()
            minecraftGameProvider::class.java.getDeclaredField("versionData").apply {
                trySetAccessible()
            }.set(minecraftGameProvider, launcher.provider.versionData)

            // Setup the entrypoint patch
            EntrypointPatch(minecraftGameProvider).process(
                launcher,
                // Class source function, just load source from the allClasses source provider
                { name ->
                    val buffer = allClasses.findSource(name)
                    buffer?.toBytes()?.let(::ClassReader)?.let { r ->
                        ClassNode().also { r.accept(it, 0) }
                    }
                }
            ) { node: ClassNode ->
                // And the source output, just input the sources back into our reference

                val name = node.name.plus(".class")
                val original = appRef.reference.reader[name]!!
                appRef.reference.writer.put(
                    ArchiveReference.Entry(
                        name,
                        streamToResource(
                            original.resource.location,
                        ) {
                            // Making sure to use an aware class writer (A Class writer that is able to
                            // load supertypes of a class given its parent archives)
                            val writer = AwareClassWriter(
                                allTargetRefs,
                                Archives.WRITER_FLAGS
                            )
                            node.accept(writer)

                            ByteArrayInputStream(writer.toByteArray())
                        },
                        original.isDirectory,
                        original.handle
                    )
                )
            }

            // A tree of all the classes in the knot target class loader (extension classes)
            val trees = listOf(
                object : ArchiveTree {
                    private val delegate = classLoaderToArchive(launcher.targetClassLoader)

                    // All packages from target but in jvm package format, nothing special here
                    private val protectedPackages = (appRef.dependencyReferences + appRef.reference)
                        .map { ArchiveSourceProvider(it) }
                        .let { DelegatingSourceProvider(it) }.packages
                        .mapTo(HashSet()) { it.replace('.', '/') }

                    // Makes sure the resource is in a package, only allow it to be loaded if its not from minecraft
                    override fun getResource(name: String): InputStream? {
                        if (!protectedPackages.contains(name.substring(0 until name.lastIndexOf("/")))) return delegate.getResource(
                            name
                        )
                        return null
                    }
                })

            // Setup our extras provider to return artificial classes from knot.
            FabricIntegrationTweaker.extrasProvider = object : ExtraClassProviderAttribute {
                override fun getByteArray(name: String): ByteArray? {
                    return knotTransformer.transformClassBytes(
                        name,
                        name,
                        // Giving knot null as input bytes forces it to create
                        // synthetic classes or proxies if it needs to, otherwise
                        // returns null which is totally fine as well.
                        null as ByteArray?
                    )
                }
            }

            // Iterate through every library and minecraft class
            (appRef.dependencyReferences + appRef.reference)
                .flatMap { it.reader.entries() }
                .forEach { entry ->
                    val jvmName = entry.name.replace("/", ".").removeSuffix(".class")

                    // Apply the following mixin to each class
                    // TODO figure out a way to only target classes that need a mixin, not literally every single one.
                    target.mixin(jvmName, 10, object : MinecraftClassTransformer {
                        // Extra trees provided by this transformer for minecraft-bootstrapper
                        override val trees: List<ArchiveTree> = trees

                        override fun transform(node: ClassNode): ClassNode {
                            // -----------------------------
                            // ++++++++++ STAGE 1 ++++++++++
                            // -----------------------------

                            // Read bytes from node, other target/library/fabric mod classes could
                            // be a super type, so we use an aware class writer.
                            val writer = AwareClassWriter(
                                appRef.dependencyReferences + appRef.reference + trees,
                                0
                            )
                            node.accept(writer)

                            // Give the bytes to the fabric transformer for stage 1 transformations
                            val stage1Bytes = FabricTransformer.transform(
                                launcher.isDevelopment,
                                EnvType.CLIENT,
                                node.name.replace('/', '.'),
                                writer.toByteArray()
                            )

                            // -----------------------------
                            // ++++++++++ STAGE 2 ++++++++++
                            // -----------------------------

                            // Let knot transform the bytes, want to do this so it can read
                            // it into node form with whatever flags it prefers.

                            val stage2Bytes = runCatching {
                                knotTransformer.transformClassBytes(
                                    jvmName,
                                    jvmName,
                                    stage1Bytes
                                )
                            }.getOrNull() ?: run {
                                // TODO better error logging please
                                System.err.println("Encountered error while loading mixin. Continuing.")
                                stage1Bytes
                            }

                            // Read the bytes back into a node
                            val stage2Node = ClassNode()
                            ClassReader(stage2Bytes).accept(stage2Node, ClassReader.EXPAND_FRAMES)

                            // -----------------------------
                            // ++++++++++ STAGE 3 ++++++++++
                            // -----------------------------

                            val stage3Node = ClassNode()
                            // Create a access widener and then apply it
                            val accessWidener = AccessWidenerClassVisitor.createClassVisitor(
                                FabricLoaderImpl.ASM_VERSION,
                                stage3Node,
                                FabricLoaderImpl.INSTANCE.accessWidener
                            )

                            stage2Node.accept(accessWidener)

                            // Return the final node
                            return stage3Node
                        }
                    })
                }
        } catch (e: FormattedException) {
            FabricLauncherBase::class.java.getDeclaredMethod("handleFormattedException", FormattedException::class.java)
                .apply { trySetAccessible() }
                .invoke(null, e)
        }

        // TODO figure out if we need this
        FabricIntegrationTweaker.tweakerEnv[TargetLinker].extract().addMiscClasses(object : ClassProvider {
            override val packages: Set<String> = setOf()

            override fun findClass(name: String): Class<*>? {
                return runCatching { FabricLauncherBase.getLauncher().targetClassLoader.loadClass(name) }.getOrNull()
            }
        })
        FabricIntegrationTweaker.knotClassloader = defer {  FabricLauncherBase.getLauncher().targetClassLoader }

        // Turn off resources so mixin generation is forced to go through yakclient
        FabricIntegrationTweaker.turnOffResources = true
        Thread.currentThread().contextClassLoader = appRef.handle.classloader

        println("Fabric initialization complete. Total phase took: '${(System.currentTimeMillis() - startTime) / 1000f}' seconds")
    }
}

private fun createIntermediaryToOfficialMappings(
    env: ExtLoaderEnvironment,
    version: String,
): ArchiveMapping {
    val graph = newMappingsGraph(
        env[mappingProvidersAttrKey].extract().toList()
    )

    val provider = graph.findShortest(
        "fabric:intermediary",
        REAL_TYPE
    )

    return provider.forIdentifier(version)
}