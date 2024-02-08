package net.yakclient.integrations.fabric

import bootFactories
import com.durganmcbroom.artifact.resolver.simple.maven.HashType
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import com.durganmcbroom.artifact.resolver.simple.maven.layout.SimpleMavenDefaultLayout
import kotlinx.coroutines.runBlocking
import net.yakclient.boot.archive.ArchiveGraph
import net.yakclient.boot.dependency.DependencyResolverProvider
import net.yakclient.boot.loader.IntegratedLoader
import net.yakclient.boot.loader.MutableClassLoader
import net.yakclient.common.util.resolve
import net.yakclient.components.extloader.api.environment.*
import net.yakclient.components.extloader.api.target.ApplicationParentClProvider
import net.yakclient.components.extloader.api.target.ApplicationTarget
import net.yakclient.components.extloader.api.target.ExtraClassProviderAttribute
import net.yakclient.components.extloader.api.tweaker.EnvironmentTweaker
import net.yakclient.components.extloader.target.TargetLinker
import net.yakclient.integrations.fabric.dependency.FabricModDependencyResolver
import net.yakclient.integrations.fabric.dependency.FabricModDependencyResolverProvider
import net.yakclient.integrations.fabric.loader.FabricLoaderDependencyResolverProvider
import net.yakclient.minecraft.bootstrapper.ExtraClassProvider
import orThrow

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
        // While capturing the environment of a tweaker is not good practice, we do it here.
        // TODO replace with just capturing environmental variables we need
        tweakerEnv = environment

        // Extra class's for the minecraft bootstrapper, it delegates to the lateinit
        // property extrasProvider which is in practice just runtime generated mixin
        // classes/proxies.
        tweakerEnv += object : ExtraClassProviderAttribute {
            override fun getByteArray(name: String): ByteArray? {
                return extrasProvider?.getByteArray(name)
            }
        }

        // Register the fabric loader dependency type (ONLY FOR THE FABRIC-INTEGRATION EXTENSION)
        val dependencyTypes = environment[dependencyTypesAttrKey]!!.container
        dependencyTypes.register(
            "fabric-loader",
            FabricLoaderDependencyResolverProvider()
        )

        val fabricModResolver = FabricModDependencyResolverProvider(
            environment.archiveGraph.path,
            dependencyTypes.get("simple-maven")!! as DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings>,
        )
        dependencyTypes.register(
            "fabric-mod:curse-maven",
            fabricModResolver
        )

        // Set the minecraft version
        minecraftVersion = environment[ApplicationTarget]!!.reference.descriptor.version


        environment += object : ApplicationParentClProvider {
            override fun getParent(linker: TargetLinker, environment: ExtLoaderEnvironment): ClassLoader {
                return object : IntegratedLoader(
                    name = "FabricExt provided minecraft parent classloader",
                    classProvider = linker.miscTarget.relationship.classes,
                    resourceProvider = linker.miscTarget.relationship.resources,
                    parent = environment[ParentClassloaderAttribute]!!.cl
                ) {
                    override fun loadClass(name: String): Class<*> {
                        return runCatching { knotClassloader?.loadClass(name) }.getOrNull()
                            ?: super.loadClass(name)
                    }
                }
            }
        }
    }

    companion object {
        // ONLY QUERY
        // TODO remove
        @Deprecated("Poor design, replace with needed environment values")
        lateinit var tweakerEnv: ExtLoaderEnvironment
            private set

        // The class loader that loads fabric and all of its dependencies
        lateinit var fabricClassloader: MutableClassLoader
            internal set

        // The minecraft version, easy access
        lateinit var minecraftVersion: String
            private set

        // The knot class loader, contains all fabric mods.
        var knotClassloader: ClassLoader? = null

        // The path to where fabrics tiny mappings are. Will be mappings from
        // intermediary to whatever yakclient is running in.
        val fabricMappingsPath
            get() = tweakerEnv[WorkingDirectoryAttribute]!!.path resolve "mapping" resolve "tiny" resolve "$minecraftVersion.tiny"

        // Whether to turn off access to Minecraft's resource from fabric, this forces
        // the fabric-loader to get them through yakclient instead.
        var turnOffResources: Boolean = false

        // Extra classes provider for minecraft-bootstrapper, usually just
        // runtime generated mixin classes
        var extrasProvider: ExtraClassProvider? = null
    }
}



