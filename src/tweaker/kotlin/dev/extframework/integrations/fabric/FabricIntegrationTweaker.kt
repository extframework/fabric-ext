package dev.extframework.integrations.fabric

import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.JobName
import com.durganmcbroom.jobs.job
import dev.extframework.boot.dependency.DependencyResolverProvider
import dev.extframework.boot.loader.MutableClassLoader
import dev.extframework.common.util.resolve
import dev.extframework.extension.core.environment.mixinAgentsAttrKey
import dev.extframework.integrations.fabric.dependency.CurseMavenFabricModProvider
import dev.extframework.integrations.fabric.dependency.ModrinthFabricModProvider
import dev.extframework.integrations.fabric.loader.FabricLoaderDependencyResolverProvider
import dev.extframework.integrations.fabric.mixin.EntrypointMixinAgent
import dev.extframework.integrations.fabric.mixin.SpongeMixinAgent
import dev.extframework.tooling.api.environment.*
import dev.extframework.tooling.api.target.ApplicationTarget
import dev.extframework.tooling.api.tweaker.EnvironmentTweaker
import java.nio.file.Path

val fabricRepository = SimpleMavenRepositorySettings.default(
    "https://maven.fabricmc.net",
    releasesEnabled = true, snapshotsEnabled = false
)

class FabricIntegrationTweaker : EnvironmentTweaker {
    override fun tweak(environment: ExtensionEnvironment): Job<Unit> = job(JobName("Tweak the fabric environment")) {
        // While capturing the environment of a tweaker is not good practice, we do it here.
        // TODO replace with just capturing environmental variables we need
        tweakerEnv = environment

        // Extra class's for the minecraft bootstrapper, it delegates to the lateinit
        // property extrasProvider which is in practice just runtime generated mixin
        // classes/proxies.
//        tweakerEnv

//        = object : ExtraClassProviderAttribute {
//            override fun getByteArray(name: String): ByteArray? {
//                return extrasProvider?.getByteArray(name)
//            }
//        }

        // Register the fabric loader dependency type (ONLY FOR THE FABRIC-INTEGRATION EXTENSION)
        environment.update(dependencyTypesAttrKey) { dependencyTypesContainer ->
            val dependencyTypes = dependencyTypesContainer.container

            val flDepProvider = FabricLoaderDependencyResolverProvider()
            dependencyTypes.register(
                "fl",
                flDepProvider
            )
            environment.archiveGraph.registerResolver(flDepProvider.resolver.libResolver)

            dependencyTypes.register(
                "fabric-mod:curse-maven",
                CurseMavenFabricModProvider(
                    dependencyTypes.get("simple-maven")!! as DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings>,
                )
            )

            dependencyTypes.register(
                "fabric-mod:modrinth",
                ModrinthFabricModProvider()
            )

            dependencyTypesContainer
        }

        // Set the minecraft version
        minecraftVersion = environment[ApplicationTarget].map { it.node.descriptor.version }.extract()

        // TODO Not a good solution right now, but we just need something basic.
        minecraftPath = environment[wrkDirAttrKey].extract().value resolve environment[ApplicationTarget].extract().path //"minecraft/$minecraftVersion/minecraft-$minecraftVersion-minecraft.jar"

        environment[mixinAgentsAttrKey].extract().add(
            EntrypointMixinAgent().also {
                entrypointAgent = it
            }
        )
        environment[mixinAgentsAttrKey].extract().add(
            SpongeMixinAgent().also {
                spongeMixinAgent = it
            }
        )
//        environment[TargetLinker].extract().addExtensionClasses(
//            object : ClassProvider {
//                override val packages: Set<String>
//
//                override fun findClass(name: String): Class<*>? {
//                    TODO("Not yet implemented")
//                }
//            }
//        )

//        environment += object : ApplicationParentClProvider {
//            override fun getParent(linker: TargetLinker, environment: ExtLoaderEnvironment): ClassLoader {
//                return FabricEnabledTargetParentLoader(linker, environment)
//            }
//        }
    }


    companion object {
        // ONLY QUERY
        // TODO remove
        @Deprecated("Poor design, replace with needed environment values")
        lateinit var tweakerEnv: ExtensionEnvironment
            private set

        // The class loader that loads fabric and all of its dependencies
        lateinit var fabricClassloader: MutableClassLoader
            internal set

        // The minecraft version, easy access
        lateinit var minecraftVersion: String
            private set

        // The knot class loader, contains all fabric mods.
        lateinit var knotClassloader: DeferredValue<ClassLoader>

        // The path to where fabrics tiny mappings are. Will be mappings from
        // intermediary to whatever extframework is running in.
        val fabricMappingsPath
            get() = tweakerEnv[wrkDirAttrKey].extract().value resolve "mappings" resolve "tiny" resolve "$minecraftVersion.tiny"

        // Whether to turn off access to Minecraft's resource from fabric, this forces
        // the fabric-loader to get them through extframework instead.
        var turnOffResources: Boolean = false

        lateinit var minecraftPath : Path
            private set

        lateinit var entrypointAgent: EntrypointMixinAgent
            private set

        lateinit var spongeMixinAgent: SpongeMixinAgent
            private set

        // Extra classes provider for minecraft-bootstrapper, usually just
        // runtime generated mixin classes
//        var extrasProvider: ExtraClassProvider? = null
    }
}



