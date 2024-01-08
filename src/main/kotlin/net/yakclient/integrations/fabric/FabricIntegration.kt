package net.yakclient.integrations.fabric

import net.fabricmc.api.EnvType
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.fabricmc.loader.impl.FormattedException
import net.fabricmc.loader.impl.game.GameProviderHelper
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatch
import net.fabricmc.loader.impl.launch.FabricLauncher
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnot
import net.fabricmc.loader.impl.lib.accesswidener.AccessWidenerClassVisitor
import net.fabricmc.loader.impl.lib.mappingio.MappingVisitor
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree
import net.fabricmc.loader.impl.lib.mappingio.tree.VisitOrder
import net.fabricmc.loader.impl.lib.tinyremapper.TinyRemapper
import net.fabricmc.loader.impl.transformer.FabricTransformer
import net.fabricmc.loader.impl.util.SystemProperties
import net.fabricmc.loader.impl.util.mappings.TinyRemapperMappingsHelper
import net.fabricmc.mappingio.format.tiny.Tiny1FileWriter
import net.minecraft.launchwrapper.Launch
import net.minecraft.launchwrapper.LaunchClassLoader
import net.yakclient.archive.mapper.ArchiveMapping
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
import net.yakclient.common.util.readInputStream
import net.yakclient.common.util.resource.ProvidedResource
import net.yakclient.common.util.toBytes
import net.yakclient.components.extloader.api.environment.ExtLoaderEnvironment
import net.yakclient.components.extloader.api.environment.WorkingDirectoryAttribute
import net.yakclient.components.extloader.api.environment.mappingProvidersAttrKey
import net.yakclient.components.extloader.api.target.ApplicationTarget
import net.yakclient.components.extloader.api.target.ExtraClassProviderAttribute
import net.yakclient.components.extloader.extension.mapping.MojangExtensionMappingProvider.Companion.REAL_TYPE
import net.yakclient.components.extloader.mapping.findShortest
import net.yakclient.components.extloader.mapping.newMappingsGraph
import net.yakclient.components.extloader.target.TargetLinker
import net.yakclient.integrations.fabric.util.emptyMappings
import net.yakclient.integrations.fabric.util.mapNamespaces
import net.yakclient.integrations.fabric.util.write
import net.yakclient.minecraft.bootstrapper.MinecraftClassTransformer
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.*
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.util.asm.ASM
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.copyTo
import kotlin.io.path.toPath
import kotlin.io.path.writeText

class FabricIntegration : Extension() {
    override fun cleanup() {}

    // Btw, this system is really fucking cool
    override fun init() {
        println("initting")

        val mappingsProviders = FabricIntegrationTweaker.tweakerEnv[mappingProvidersAttrKey]!!
        val intermediaryProvider = FabricMappingProvider()
        mappingsProviders.add(intermediaryProvider)

        // Lil note for myself. THe current issue is in MixinINtermediaryDevRemapper. If you check this out (i put breakpoints) stuff is fucked up in the mappings
        // If you go look at the textEdit file thats open rn and the highlighted portions. Method names r just not correct. THis is happening somewhere in the mapping
        // parse/translation stage. THe first error though is getting namepsaces to match up, fabric uses namespaces like "named", "official", "intermediary", and im doing
        // "mojang:deobfuscated", "mojang:obfuscated", "fabric:intermediary", etc. Two things need to occur. I think the best solution to this is to write a archive mapper proxy
        // that sits in from of mappings, takes a map of how to translate namespaces, and just does that. It wont slow it down too much (if you do lazy (like you should)), and should
        // be quick to write. good luck, im realy tired, gonna go to be dnow...

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
//                "official",
                "intermediary",
            )
        }

        ASM::class.java.getDeclaredField("minorVersion").apply {
            trySetAccessible()
        }.set(null, 6)
        ASM::class.java.getDeclaredField("implMinorVersion").apply {
            trySetAccessible()
        }.set(null, 6)

        System.setProperty("mixin.service", "")
        System.setProperty("fabric.debug.disableClassPathIsolation", "true")
        val path = Files.createTempFile("remap-classpath", "txt")

        val appRef = FabricIntegrationTweaker.tweakerEnv[ApplicationTarget]!!.reference
        val targetLocation = appRef.reference.location
        val newTargetRef = Archives.find(Path.of(targetLocation), Archives.Finders.ZIP_FINDER)
        val mappings = rawTinyMappings(appRef.descriptor.version)
        transformArchive(
            newTargetRef,
            appRef.dependencyReferences,
            mappings,
            "official",
            "intermediary"
        )
        val tmpMappedTarget = Files.createTempFile("mc-target", ".jar")
        newTargetRef.write(tmpMappedTarget)

        path.writeText(tmpMappedTarget.toString())
        System.setProperty(SystemProperties.REMAP_CLASSPATH_FILE, path.toString())

        Launch.classLoader = LaunchClassLoader(arrayOf())
        Launch.blackboard = HashMap()

        Thread.currentThread().contextClassLoader = this::class.java.classLoader
        FabricLauncherBase::class.java.getDeclaredMethod("setupUncaughtExceptionHandler")
            .apply { trySetAccessible() }
            .invoke(null)

        try {
            val launcher = YakclientLauncher(EnvType.CLIENT)

            loader = launcher.init(arrayOf())

//            val knotClassloader = (FabricLauncherBase.getLauncher() as YakclientLauncher).classPath
            val knotTransformer: IMixinTransformer = MixinServiceKnot::class.java.getDeclaredMethod(
                "getTransformer"
            ).apply { trySetAccessible() }.invoke(null) as IMixinTransformer
//            val knotTransformer = knotClassloader::class.java.getDeclaredMethod("getMixinTransformer")
//                .apply { trySetAccessible() }
//                .invoke(knotClassloader) as IMixinTransformer

            // More hacks, we dont want fabric to map refmaps back into intermediary so we do this..
            // TODO figure out why fabric does that
//            val config = launcher.mappingConfiguration
//            config::class.java.getDeclaredField("mappings").apply { trySetAccessible() }
//                .set(config, null)

            val target = FabricIntegrationTweaker.tweakerEnv[ApplicationTarget]!!

            val allTargetRefs = appRef.dependencyReferences + appRef.reference
            val allClasses = DelegatingSourceProvider(
                allTargetRefs.map {
                    ArchiveSourceProvider(it)
                }
            )

            val minecraftGameProvider = MinecraftGameProvider()
            minecraftGameProvider::class.java.getDeclaredField("versionData").apply {
                trySetAccessible()
            }.set(minecraftGameProvider, launcher.provider.versionData)

            EntrypointPatch(minecraftGameProvider).process(
                launcher,
                { name ->
                    val buffer = allClasses.getSource(name)
                    buffer?.toBytes()?.let(::ClassReader)?.let { r ->
                        ClassNode().also { r.accept(it, 0) }
                    }
                }
            ) { node ->
                val name = node.name.plus(".class")
                val original = target.reference.reference.reader[name]!!
                target.reference.reference.writer.put(
                    ArchiveReference.Entry(
                        name,
                        ProvidedResource(
                            original.resource.uri,
                        ) {
                            val writer = AwareClassWriter(
                                allTargetRefs,
                                Archives.WRITER_FLAGS
                            )
                            node.accept(writer)

                            writer.toByteArray()
                        },
                        original.isDirectory,
                        original.handle
                    )
                )
            }

            val trees = listOf(
                object : ArchiveTree {
                    private val delegate = classLoaderToArchive(launcher.targetClassLoader)
                    private val protectedPackages = (target.reference.dependencyReferences + target.reference.reference)
                        .map { ArchiveSourceProvider(it) }
                        .let { DelegatingSourceProvider(it) }.packages
                        .mapTo(HashSet()) { it.replace('.', '/') }

                    override fun getResource(name: String): InputStream? {
                        if (!protectedPackages.contains(name.substring(0 until name.lastIndexOf("/")))) return delegate.getResource(
                            name
                        )
                        return null
                    }
                })

            FabricIntegrationTweaker.extrasProvider = object : ExtraClassProviderAttribute {
                override fun getByteArray(name: String): ByteArray? {
                    return knotTransformer.transformClassBytes(
                        name,
                        name,
                        null as ByteArray?
                    )
                }
            }

//            val tinyRemapper = TinyRemapper.newRemapper()
//                .withMappings(TinyRemapperMappingsHelper.create(emptyMappings("src", "dst"), "dst", "src"))
//                .rebuildSourceFilenames(true)
//                .build()
//
//            val temp = Files.createTempFile("minecraft-hold", ".jar")
//
//            target.reference.reference.write(temp)
//
//            val input = tinyRemapper.createInputTag()
//            tinyRemapper.readInputsAsync(
//                 input,
//                *((target.reference.dependencyReferences.map { it.location.toPath() } + listOf(temp)).toTypedArray())
//            )
//
//            tinyRemapper.apply({ name, bytes ->
//                println(name)
//                if (name.contains("GameRenderer")) {
//                    println("OK")
//                }
//                val old = target.reference.reference.reader["$name.class"] ?: return@apply
//                target.reference.reference.writer.put(
//                    ArchiveReference.Entry(
//                        old.name,
//                        ProvidedResource(old.resource.uri) {
//                            bytes
//                        },
//                        old.isDirectory,
//                        target.reference.reference,
//                    )
//                )
//            }, input)
//
//            tinyRemapper.finish()

//            val tempGameIn = Files.createTempFile("minecraft-hold", ".jar")
//            target.reference.reference.write(
//                tempGameIn
//            )
//
//            val remappedGameOut = Files.createTempFile("minecraft-hold-out", ".jar")
//            Files.delete(remappedGameOut)

//            GameProviderHelper::class.java.getDeclaredMethod(
//                "deobfuscate0",
//                List::class.java,
//                List::class.java,
//                List::class.java,
//                MappingTree::class.java,
//                String::class.java,
//                FabricLauncher::class.java
//            ).apply { trySetAccessible() }.invoke(
//                null,
//                listOf(tempGameIn),
//                listOf(remappedGameOut),
//                listOf(Files.createTempFile("minecraft-hold-tmp", ".jar").also {
//                    tempGameIn.copyTo(it, overwrite = true)
//                }),
////                launcher.mappingConfiguration.mappings,
//                emptyMappings("named", "intermediary"),
//                "intermediary",
//                launcher
//            )
//
//            println(remappedGameOut)
//
////            val remappedGameOut = GameProviderHelper.deobfuscate(
////                mapOf("client" to tempGameIn),
////                "minecraft", FabricIntegrationTweaker.minecraftVersion,
////                FabricIntegrationTweaker.tweakerEnv[WorkingDirectoryAttribute]!!.path,
////                launcher,
////            )["client"]!!
//
//            ZipFile(remappedGameOut.toFile()).use { mappedGameRef ->
//                for (oldEntry in target.reference.reference.reader.entries()) {
//                    val mappedEntry = mappedGameRef.getEntry(oldEntry.name)!!
//                    if (!oldEntry.name.endsWith(".class")) continue
//
//                    val resource = mappedGameRef.getInputStream(mappedEntry).readInputStream()
//                    target.reference.reference.writer.put(
//                        ArchiveReference.Entry(
//                            oldEntry.name,
//                            ProvidedResource(oldEntry.resource.uri) {
//                                resource
//                            },
//                            oldEntry.isDirectory,
//                            oldEntry.handle
//                        )
//                    )
//                }
//            }

            (target.reference.dependencyReferences + target.reference.reference)
                .flatMap { it.reader.entries() }
                .forEach { entry ->
                    val jvmName = entry.name.replace("/", ".").removeSuffix(".class")

                    target.mixin(jvmName, object : MinecraftClassTransformer {
                        override val trees: List<ArchiveTree> = trees

                        override fun transform(node: ClassNode): ClassNode {
                            val writer =  AwareClassWriter(
                                target.reference.dependencyReferences + target.reference.reference + trees,
                                0
                            )
                            node.accept(writer)

                            val newBytes = FabricTransformer.transform(
                                launcher.isDevelopment,
                                EnvType.CLIENT,
                                node.name.replace('/', '.'),
                                writer.toByteArray()
                            )

                            val transformedBytes = knotTransformer.transformClassBytes(
                                jvmName,
                                jvmName,
                                newBytes
                            )

                            val transformedNode = ClassNode()
                            ClassReader(transformedBytes).accept(transformedNode, ClassReader.EXPAND_FRAMES)

                            val newNode = ClassNode()
                            val accessWidener = AccessWidenerClassVisitor.createClassVisitor(
                                FabricLoaderImpl.ASM_VERSION,
                                newNode,
                                FabricLoaderImpl.INSTANCE.accessWidener
                            )

                            transformedNode.accept(accessWidener)

                            return newNode
                        }
                    })
                }
        } catch (e: FormattedException) {
            FabricLauncherBase::class.java.getDeclaredMethod("handleFormattedException", FormattedException::class.java)
                .apply { trySetAccessible() }
                .invoke(null, e)
        }

        FabricIntegrationTweaker.tweakerEnv[TargetLinker]!!.addMiscClasses(object : ClassProvider {
            override val packages: Set<String> = setOf()

            override fun findClass(name: String): Class<*>? {
                return runCatching { FabricLauncherBase.getLauncher().targetClassLoader.loadClass(name) }.getOrNull()
            }

            override fun findClass(name: String, module: String): Class<*>? {
                return findClass(name)
            }
        })
        FabricIntegrationTweaker.knotClassloader = FabricLauncherBase.getLauncher().targetClassLoader

        println("fabric setup")
        FabricIntegrationTweaker.turnOffResources = true
        Thread.currentThread().contextClassLoader = FabricIntegration::class.java.classLoader
    }

    companion object {
        public lateinit var loader: FabricLoader
            private set
    }
}

private fun createIntermediaryToOfficialMappings(
    env: ExtLoaderEnvironment,
    version: String,
//    real: String, // overrides for real and fake namespaces when creating tiny mappings
//    fake: String
): ArchiveMapping {
    val graph = newMappingsGraph(
        env[mappingProvidersAttrKey]!!.toList()
    )

    val provider = graph.findShortest(
        "fabric:intermediary",
        REAL_TYPE
    )

    val mappings = provider.forIdentifier(version)
    return mappings
}

//
//private fun getAsmIndex(lvIndex: Int, isStatic: Boolean, argTypes: Array<Type>): Int {
//    var lvIndex = lvIndex
//    if (!isStatic) {
//        --lvIndex
//    }
//    for (i in argTypes.indices) {
//        if (lvIndex == 0) {
//            return i
//        }
//        lvIndex -= argTypes[i].size
//    }
//    return -1
//}
//
//private fun getLvIndex(asmIndex: Int, isStatic: Boolean, argTypes: Array<Type>): Int {
//    var ret = 0
//    if (!isStatic) {
//        ++ret
//    }
//    for (i in 0 until asmIndex) {
//        ret += argTypes[i].size
//    }
//    return ret
//}
//
//private fun isValidJavaIdentifier(s: String?): Boolean {
//    return s != null && !s.isEmpty() && SourceVersion.isIdentifier(s) && !s.codePoints().anyMatch { codePoint: Int ->
//        Character.isIdentifierIgnorable(
//            codePoint
//        )
//    }
//}
//
//fun asdf(methodNode: MethodNode, skipLocalMapping: Boolean) {
//    val isStatic = methodNode.access and 8 != 0
//    val argTypes: Array<Type> = Type.getArgumentTypes(methodNode.desc)
//    val argLvSize = getLvIndex(argTypes.size, isStatic, argTypes)
//    val args = arrayOfNulls<String>(argTypes.size)
//    var i: Int
//    if (methodNode.parameters != null && methodNode.parameters.size == args.size) {
//        i = 0
//        while (i < args.size) {
//            args[i] = (methodNode.parameters.get(i) as ParameterNode).name
//            ++i
//        }
//    } else {
//        assert(methodNode.parameters == null)
//    }
//
//    var i: Int
//    if (methodNode.localVariables != null) {
//        i = 0
//        while (i < methodNode.localVariables.size) {
//            val lv = methodNode.localVariables.get(i)
//            if (!isStatic && lv.index == 0) {
//                lv.name = "this"
//            } else if (lv.index < argLvSize) {
//                i = getAsmIndex(lv.index, isStatic, argTypes)
//                val existingName = args[i]
//                if (existingName == null || !isValidJavaIdentifier(existingName) && isValidJavaIdentifier(
//                        lv.name
//                    )
//                ) {
//                    args[i] = lv.name
//                }
//            } else if (!skipLocalMapping) {
//                i = 0
//                var start: AbstractInsnNode = lv.start
//                while ((start as AbstractInsnNode).previous.also { start = it } != null) {
//                    if (start.opcode >= 0) {
//                        ++i
//                    }
//                }
//                lv.name = (remapper as AsmRemapper).mapMethodVar(
//                    owner,
//                    methodNode.name,
//                    methodNode.desc,
//                    lv.index,
//                    i,
//                    i,
//                    lv.name
//                )
//                if (renameInvalidLocals && isValidLvName(lv.name)) {
//                    nameCounts.putIfAbsent(lv.name, 1)
//                }
//            }
//            ++i
//        }
//    }
//
//    if (!skipLocalMapping) {
//        i = 0
//        while (i < args.size) {
//            args[i] = (remapper as AsmRemapper).mapMethodArg(
//                owner,
//                methodNode.name,
//                methodNode.desc,
//                AsmClassRemapper.AsmMethodRemapper.getLvIndex(i, isStatic, argTypes),
//                args[i]
//            )
//            if (renameInvalidLocals && isValidLvName(args[i])) {
//                nameCounts.putIfAbsent(args[i], 1)
//            }
//            ++i
//        }
//    }
//
//    if (renameInvalidLocals) {
//        i = 0
//        while (i < args.size) {
//            if (!isValidLvName(args[i])) {
//                args[i] = getNameFromType(remapper.mapDesc(argTypes[i].descriptor), true)
//            }
//            ++i
//        }
//    }
//
//    var hasAnyArgs = false
//    var hasAllArgs = true
//    var i = args.size
//
//    for (var9 in 0 until i) {
//        val arg = args[var9]
//        if (arg != null) {
//            hasAnyArgs = true
//        } else {
//            hasAllArgs = false
//        }
//    }
//
//    if (methodNode.localVariables != null || hasAnyArgs && methodNode.access and 1024 == 0) {
//        if (methodNode.localVariables == null) {
//            methodNode.localVariables = ArrayList<Any?>()
//        }
//        val argsWritten = BooleanArray(args.size)
//        var j: Int
//        i = 0
//        label219@ while (i < methodNode.localVariables.size) {
//            val lv = methodNode.localVariables.get(i)
//            if (isStatic || lv.index != 0) {
//                if (lv.index < argLvSize) {
//                    j = AsmClassRemapper.AsmMethodRemapper.getAsmIndex(lv.index, isStatic, argTypes)
//                    lv.name = args[j]
//                    argsWritten[j] = true
//                } else if (renameInvalidLocals && !isValidLvName(lv.name)) {
//                    if (inferNameFromSameLvIndex) {
//                        j = 0
//                        while (j < methodNode.localVariables.size) {
//                            if (j != i) {
//                                val otherLv = methodNode.localVariables.get(j)
//                                if (otherLv.index == lv.index && otherLv.name != null && otherLv.desc == lv.desc && (j < i || isValidLvName(
//                                        otherLv.name
//                                    ))
//                                ) {
//                                    lv.name = otherLv.name
//                                    ++i
//                                    continue@label219
//                                }
//                            }
//                            ++j
//                        }
//                    }
//                    lv.name = getNameFromType(lv.desc, false)
//                }
//            }
//            ++i
//        }
//        var start: LabelNode? = null
//        var end: LabelNode? = null
//        j = 0
//        while (j < args.size) {
//            if (!argsWritten[j] && args[j] != null) {
//                if (start == null) {
//                    var pastStart = false
//                    val it: Iterator<AbstractInsnNode> = methodNode.instructions.iterator()
//                    while (it.hasNext()) {
//                        val ain = it.next()
//                        if (ain.type == 8) {
//                            val label = ain as LabelNode
//                            if (start == null && !pastStart) {
//                                start = label
//                            }
//                            end = label
//                        } else if (ain.opcode >= 0) {
//                            pastStart = true
//                            end = null
//                        }
//                    }
//                    if (start == null) {
//                        start = LabelNode()
//                        methodNode.instructions.insert(start)
//                    }
//                    if (end == null) {
//                        if (!pastStart) {
//                            end = start
//                        } else {
//                            end = LabelNode()
//                            methodNode.instructions.add(end)
//                        }
//                    }
//                }
//                methodNode.localVariables.add(
//                    LocalVariableNode(
//                        args[j],
//                        remapper.mapDesc(
//                            argTypes[j].descriptor
//                        ),
//                        null as String?,
//                        start,
//                        end,
//                        AsmClassRemapper.AsmMethodRemapper.getLvIndex(j, isStatic, argTypes)
//                    )
//                )
//            }
//            ++j
//        }
//    }
//
//    if (methodNode.parameters != null || hasAllArgs && args.size > 0 || hasAnyArgs && methodNode.access and 1024 != 0) {
//        if (methodNode.parameters == null) {
//            methodNode.parameters = ArrayList<Any?>(args.size)
//        }
//        while (methodNode.parameters.size < args.size) {
//            methodNode.parameters.add(ParameterNode(null as String?, 0))
//        }
//        i = 0
//        while (i < args.size) {
//            (methodNode.parameters.get(i) as ParameterNode).name = args[i]
//            ++i
//        }
//    }
//
//}