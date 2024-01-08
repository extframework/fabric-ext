package net.yakclient.integrations.fabric

import com.durganmcbroom.artifact.resolver.simple.maven.HashType
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import com.durganmcbroom.artifact.resolver.simple.maven.layout.SimpleMavenDefaultLayout
import net.yakclient.archives.ArchiveHandle
import net.yakclient.archives.mixin.BeforeInsnNodeInjector
import net.yakclient.archives.mixin.MethodSourceInjector
import net.yakclient.archives.mixin.SourceInjectionContext
import net.yakclient.archives.mixin.SourceInjectionPoint
import net.yakclient.boot.container.ContainerHandle
import net.yakclient.boot.loader.IntegratedLoader
import net.yakclient.boot.loader.MutableClassProvider
import net.yakclient.boot.security.PrivilegeManager
import net.yakclient.common.util.resolve
import net.yakclient.components.extloader.api.environment.*
import net.yakclient.components.extloader.api.extension.ExtensionClassLoaderProvider
import net.yakclient.components.extloader.api.extension.archive.ExtensionArchiveReference
import net.yakclient.components.extloader.api.target.ApplicationParentClProvider
import net.yakclient.components.extloader.api.target.ApplicationTarget
import net.yakclient.components.extloader.api.target.ExtraClassProviderAttribute
import net.yakclient.components.extloader.api.tweaker.EnvironmentTweaker
import net.yakclient.components.extloader.extension.ExtensionClassLoader
import net.yakclient.components.extloader.extension.ExtensionProcess
import net.yakclient.components.extloader.target.TargetLinker
import net.yakclient.integrations.fabric.loader.FabricLoaderDependencyResolverProvider
import net.yakclient.minecraft.bootstrapper.ExtraClassProvider
import org.objectweb.asm.tree.LdcInsnNode
import java.lang.IllegalArgumentException
import java.net.URL
import java.nio.file.Files
import java.util.*
import kotlin.collections.ArrayList

val fabricRepository = SimpleMavenRepositorySettings(
    SimpleMavenDefaultLayout(
        "https://maven.fabricmc.net",
        HashType.SHA1,
        true, false
    ),
    HashType.SHA1
)

class FabricIntegrationTweaker : EnvironmentTweaker {
    override fun tweak(environment: ExtLoaderEnvironment) {
        tweakerEnv = environment

        tweakerEnv += object : ExtraClassProviderAttribute {
            override fun getByteArray(name: String): ByteArray? {
                return extrasProvider?.getByteArray(name)
            }
        }

        val oldLinker = environment[TargetLinker]!!
        environment += TargetLinker(
            oldLinker.targetClassProvider,
            oldLinker.targetSourceProvider,
            object : MutableClassProvider(ArrayList()) {
                override fun findClass(name: String): Class<*>? {
                    return delegateClasses.firstNotNullOfOrNull { it.findClass(name) }
                }
            }
        )



        val dependencyTypes = environment[dependencyTypesAttrKey]!!.container
        dependencyTypes.register(
            "fabric-loader",
            FabricLoaderDependencyResolverProvider()
        )

        System.setProperty("fabric.skipMcProvider", "true")
        System.setProperty(
            "fabric.gameJarPath.client",
            environment[ApplicationTarget]!!.reference.reference.location.path
        )

        minecraftVersion = environment[ApplicationTarget]!!.reference.descriptor.version

        environment[injectionPointsAttrKey]!!.container.register(
            "fabric-client-entrypoint-point",
        ) { context ->
            val node = (context.insn.find {
                it is LdcInsnNode && it.cst == "Backend library: {}"
            } ?: throw IllegalArgumentException("Failed to find appropiate instruction for client entrypoint. "))
            listOf(BeforeInsnNodeInjector(
                context.insn,
                node.previous.previous
            ))
        }

        environment += object : ExtensionClassLoaderProvider {
            override fun createFor(
                archive: ExtensionArchiveReference,
                dependencies: List<ArchiveHandle>,
                manager: PrivilegeManager,
                handle: ContainerHandle<ExtensionProcess>,
                linker: TargetLinker,
                parent: ClassLoader
            ): ClassLoader = object : ExtensionClassLoader(archive, dependencies, manager, parent, handle, linker) {
                override fun getResources(name: String?): Enumeration<URL> {
                    val enum = Vector<URL>()
                    getResource(name)?.let {
                        enum.add(it)
                    }
                    return enum.elements()
                }
            }
        }
        environment += object : ApplicationParentClProvider {
            override fun getParent(linker: TargetLinker, environment: ExtLoaderEnvironment): ClassLoader {
                return object : IntegratedLoader(
                    cp = linker.miscClassProvider,
                    sp = linker.miscSourceProvider,
                    parent = environment[ParentClassloaderAttribute]!!.cl
                ) {
                    override fun loadClass(name: String, resolve: Boolean): Class<*> {
                        val it = runCatching { knotClassloader?.loadClass(name) }.getOrNull()
                            ?: super.loadClass(name, resolve)

                        if (resolve) resolveClass(it)
                        return it
                    }
                }
            }
        }
    }

    companion object {
        // ONLY QUERY
        lateinit var tweakerEnv: ExtLoaderEnvironment
            private set

        lateinit var fabricClassloader : ClassLoader
            internal set
        lateinit var minecraftVersion: String
            private set

        var knotClassloader: ClassLoader? = null

        val fabricMappingsPath
            get() = tweakerEnv[WorkingDirectoryAttribute]!!.path resolve "mapping" resolve "tiny" resolve "$minecraftVersion.tiny"

        var turnOffResources: Boolean = false

        var extrasProvider : ExtraClassProvider? = null
    }
}



