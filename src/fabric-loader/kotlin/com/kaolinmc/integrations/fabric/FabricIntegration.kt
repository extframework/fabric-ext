package com.kaolinmc.integrations.fabric

import com.kaolinmc.archive.mapper.ArchiveMapping
import com.kaolinmc.archive.mapper.findShortest
import com.kaolinmc.archive.mapper.newMappingsGraph
import com.kaolinmc.archive.mapper.transform.transformArchive
import com.kaolinmc.archives.ArchiveTree
import com.kaolinmc.archives.Archives
import com.kaolinmc.archives.transform.AwareClassWriter
import com.kaolinmc.archives.zip.classLoaderToArchive
import com.kaolinmc.boot.archive.ArchiveRelationship
import com.kaolinmc.boot.archive.ArchiveTarget
import com.kaolinmc.boot.archive.ClassLoadedArchiveNode
import com.kaolinmc.boot.loader.ClassProvider
import com.kaolinmc.common.util.make
import com.kaolinmc.common.util.readInputStream
import com.kaolinmc.common.util.resolve
import com.kaolinmc.core.app.TargetLinker
import com.kaolinmc.core.entrypoint.Entrypoint
import com.kaolinmc.core.minecraft.api.MappingNamespace
import com.kaolinmc.core.minecraft.environment.mappingProvidersAttrKey
import com.kaolinmc.core.minecraft.environment.mappingTargetAttrKey
import com.kaolinmc.core.minecraft.environment.minecraft
import com.kaolinmc.integrations.fabric.dependency.FabricModNode
import com.kaolinmc.integrations.fabric.loader.FLLibNode
import com.kaolinmc.integrations.fabric.loader.FLNode
import com.kaolinmc.integrations.fabric.mapping.FabricMappingProvider
import com.kaolinmc.integrations.fabric.mapping.mapNamespaces
import com.kaolinmc.integrations.fabric.util.write
import com.kaolinmc.tooling.api.ExtensionLoader
import com.kaolinmc.tooling.api.environment.ExtensionEnvironment
import com.kaolinmc.tooling.api.environment.wrkDirAttrKey
import com.kaolinmc.tooling.api.extension.artifact.ExtensionDescriptor
import net.fabricmc.api.EnvType
import net.fabricmc.loader.impl.FormattedException
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatch
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnot
import net.fabricmc.loader.impl.transformer.FabricTransformer
import net.fabricmc.loader.impl.util.FileSystemUtil
import net.fabricmc.mappingio.format.tiny.Tiny1FileWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.util.asm.ASM
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText

class FabricIntegration : Entrypoint() {
    override fun init() {
        val startTime = System.currentTimeMillis()

        println("Fabric integration")
        val mappingsProviders = FabricIntegrationTweaker.tweakerEnv[mappingProvidersAttrKey]

        val mappingTarget = FabricIntegrationTweaker.tweakerEnv[mappingTargetAttrKey].value
        if (FabricIntegrationTweaker.fabricMappingsPath.make()) {
            val intermediaryToOfficialMappings = createIntermediaryToOfficialMappings(
                FabricIntegrationTweaker.tweakerEnv, FabricIntegrationTweaker.minecraftVersion,
            ).mapNamespaces(
                mappingTarget.identifier to "named",
                FabricMappingProvider.INTERMEDIARY_NAMESPACE to "intermediary"
            )

            com.kaolinmc.archive.mapper.parsers.tiny.write(
                Tiny1FileWriter(FileWriter(FabricIntegrationTweaker.fabricMappingsPath.toFile())),
                intermediaryToOfficialMappings,
                // Yes, this is reverse. We do this to trick fabric into mapping all references from intermediary
                // into official (not actually official, just whatever we are currently running in)
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

        val target = FabricIntegrationTweaker.tweakerEnv.minecraft

        val archiveGraph = FabricIntegrationTweaker.tweakerEnv[ExtensionLoader].graph

        System.setProperty(
            net.fabricmc.loader.impl.util.SystemProperties.ADD_MODS,
            archiveGraph.nodes
                .map { it.value.value }
                .filterIsInstance<FabricModNode<*>>()
                .joinToString(separator = File.pathSeparator) { it.path.toString() }
        )

        val targetLocation = FabricIntegrationTweaker.minecraftPath

        // There's going to be a lot loaded by the fabric system that isnt part of
        // whats its regular classpath will look like. Since they prevent all class loading
        // from not declared classpaths, its easiest just to turn off that feature
        System.setProperty("fabric.debug.disableClassPathIsolation", "true")

        // Tell fabric to skip the default Mc Provider and instead use ours
        System.setProperty("fabric.skipMcProvider", "true")
        // Tell fabric where the game jar is
        System.setProperty(
            "fabric.gameJarPath.client",
            targetLocation.toString()
        )

        val mappedTarget = FabricIntegrationTweaker.tweakerEnv[wrkDirAttrKey]
            .value resolve "remapped" resolve "minecraft" resolve "intermediary" resolve mappingTarget.path resolve "minecraft-${FabricIntegrationTweaker.minecraftVersion}.jar" //Files.createTempFile("mc-target", ".jar")

        if (!mappedTarget.exists()) {
            // Loading the target (minecraft) and then transforming it from official to intermediary mappings
            // For fabric to use as the remap-classpath.
            val newTargetRef = Archives.find(targetLocation, Archives.Finders.ZIP_FINDER)

            // At this point the target archive should already be in the correctly mapped namespace
            val mappings = newMappingsGraph(mappingsProviders.toList()).findShortest(
                FabricMappingProvider.INTERMEDIARY_NAMESPACE,
                mappingTarget.identifier,
            ).forIdentifier(target.version)

            transformArchive(
                newTargetRef,
                target.node.access.targets
                    .asSequence()
                    .map(ArchiveTarget::relationship)
                    .map(ArchiveRelationship::node)
                    .filterIsInstance<ClassLoadedArchiveNode<*>>()
                    .mapNotNullTo(ArrayList(), ClassLoadedArchiveNode<*>::handle),
                mappings,
                mappingTarget.identifier,
                FabricMappingProvider.INTERMEDIARY_NAMESPACE
            )

            newTargetRef.write(mappedTarget)
        }

        val remapClasspath = Files.createTempFile("remap-classpath", "txt")

        remapClasspath.writeText(mappedTarget.toString())
        // Fabric wants a file pointing to the remap-classpath, so thats what we do above.
        System.setProperty(
            net.fabricmc.loader.impl.util.SystemProperties.REMAP_CLASSPATH_FILE,
            remapClasspath.toString()
        )

        // Set the context classloader to this thread for service-loading of our game provider
        Thread.currentThread().contextClassLoader = this::class.java.classLoader
        // Force fabric to setup its exception handler.
        FabricLauncherBase::class.java.getDeclaredMethod("setupUncaughtExceptionHandler")
            .apply { trySetAccessible() }
            .invoke(null)

        try {
            // Create the Kaolin launcher
            val launcher =
                KaolinLauncher(EnvType.CLIENT, mappingTarget != MappingNamespace("mojang", "obfuscated"))

            // Start the launcher
            launcher.init(arrayOf())

            // Get a mixin transformer for later use
            val knotTransformer: IMixinTransformer = MixinServiceKnot::class.java.getDeclaredMethod(
                "getTransformer"
            ).apply { trySetAccessible() }.invoke(null) as IMixinTransformer

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
                { name: String ->
                    val buffer = target.node.handle?.classloader?.getResourceAsStream(
                        name.replace('.', '/') + ".class"
                    )
                    buffer?.readInputStream()?.let(::ClassReader)?.let { r ->
                        ClassNode().also { r.accept(it, ClassReader.EXPAND_FRAMES) }
                    }
                }
            ) { node: ClassNode ->
                FabricIntegrationTweaker.entrypointAgent.registerPatches(
                    listOf(node)
                )
            }

            // A tree of all the classes in the knot target class loader (extension classes)
            val trees = listOf(
                object : ArchiveTree {
                    private val delegate = classLoaderToArchive(launcher.targetClassLoader)

                    // Makes sure the resource is in a package, only allow it to be loaded if its not from minecraft
                    override fun getResource(name: String): InputStream? {
                        if (target.node.handle?.getResource(name) == null) return delegate.getResource(
                            name
                        )
                        return null
                    }
                })

            FabricIntegrationTweaker.spongeMixinAgent.delegateAgent = agent@{ name, node ->
                // -----------------------------
                // ++++++++++ STAGE 1 ++++++++++
                // -----------------------------

                // Read bytes from node, other target/library/fabric mod classes could
                // be a super type, so we use an aware class writer.

                val stage1Bytes = node?.let {
                    val writer = AwareClassWriter(
                        listOfNotNull(target.node.handle) + trees,
                        0
                    )
                    it.accept(writer)

                    // Give the bytes to the fabric transformer for stage 1 transformations
                    FabricTransformer.transform(
                        launcher.isDevelopment,
                        EnvType.CLIENT,
                        name,
                        writer.toByteArray()
                    )
                }

                // -----------------------------
                // ++++++++++ STAGE 2 ++++++++++
                // -----------------------------

                // Let knot transform the bytes, want to do this so it can read
                // it into node form with whatever flags it prefers.

                val stage2Bytes = runCatching {
                    knotTransformer.transformClassBytes(
                        name,
                        name,
                        stage1Bytes
                    )
                }.recoverCatching {
                    System.err.println("Encountered error while loading mixin. Continuing.")
                    it.printStackTrace()

                    stage1Bytes
                }.getOrNull() ?: return@agent null

                // Read the bytes back into a node
                val stage2Node = ClassNode()
                ClassReader(stage2Bytes).accept(stage2Node, ClassReader.EXPAND_FRAMES)

                // Return the final node
                stage2Node
            }
            // Iterate through every library and minecraft class
        } catch (e: FormattedException) {
            FabricLauncherBase::class.java.getDeclaredMethod("handleFormattedException", FormattedException::class.java)
                .apply { trySetAccessible() }
                .invoke(null, e)
        }

        val descriptor = FabricIntegrationTweaker.tweakerEnv[ExtensionLoader].graph.nodes.keys
            .filterIsInstance<ExtensionDescriptor>()
            .first { it.artifact == "fabric-ext" }

        FabricIntegrationTweaker.tweakerEnv[TargetLinker].extensionClasses[descriptor] = (object : ClassProvider {
            override val packages: Set<String> =
                archiveGraph.nodes
                    .map { it.value.value }
                    .filterIsInstance<FLNode>()
                    .flatMapTo(HashSet()) {
                        it.packages
                    } + archiveGraph.nodes
                    .map { it.value.value }
                    .filterIsInstance<FLLibNode>()
                    .flatMapTo(HashSet()) {
                        it.packages
                    } + archiveGraph.nodes
                    .map { it.value.value }
                    .filterIsInstance<FabricModNode<*>>()
                    .flatMapTo(HashSet()) {
                        it.packages
                    }

            override fun findClass(name: String): Class<*>? {
                return runCatching { FabricLauncherBase.getLauncher().targetClassLoader.loadClass(name) }.getOrNull()
            }
        })

        FabricIntegrationTweaker.knotClassloader = FabricLauncherBase.getLauncher().targetClassLoader

        // Turn off resources so mixin generation is forced to go through Kaolin
        FabricIntegrationTweaker.turnOffResources = true
//   TODO rewrite a new context loader that truly has context     Thread.currentThread().contextClassLoader = appRef.handle.classloader

        FileSystemUtil.closeAll()
        println("Fabric initialization complete. Total phase took: '${(System.currentTimeMillis() - startTime) / 1000f}' seconds")
    }
}

private fun createIntermediaryToOfficialMappings(
    env: ExtensionEnvironment,
    version: String,
): ArchiveMapping {
    val graph = newMappingsGraph(
        env[mappingProvidersAttrKey].toList()
    )

    val provider = graph.findShortest(
        "fabric:intermediary",
        env[mappingTargetAttrKey].value.identifier
    )

    return provider.forIdentifier(version)
}