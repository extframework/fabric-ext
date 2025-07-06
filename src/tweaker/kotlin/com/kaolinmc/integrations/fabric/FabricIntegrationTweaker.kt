package com.kaolinmc.integrations.fabric

import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenArtifactRequest
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenRepositorySettings
import com.kaolinmc.boot.dependency.DependencyResolverProvider
import com.kaolinmc.boot.loader.MutableClassLoader
import com.kaolinmc.common.util.resolve
import com.kaolinmc.core.app.api.ApplicationTarget
import com.kaolinmc.core.instrument.InstrumentedApplicationTarget
import com.kaolinmc.core.instrument.instrumentAgentsAttrKey
import com.kaolinmc.core.minecraft.api.MinecraftAppApi
import com.kaolinmc.core.minecraft.environment.mappingTargetAttrKey
import com.kaolinmc.core.minecraft.environment.minecraft
import com.kaolinmc.integrations.fabric.dependency.CurseMavenFabricModProvider
import com.kaolinmc.integrations.fabric.dependency.ModrinthFabricModDependencyResolver
import com.kaolinmc.integrations.fabric.dependency.ModrinthFabricModProvider
import com.kaolinmc.integrations.fabric.loader.FabricLoaderDependencyResolverProvider
import com.kaolinmc.integrations.fabric.mixin.EntrypointMixinAgent
import com.kaolinmc.integrations.fabric.mixin.SpongeMixinAgent
import com.kaolinmc.tooling.api.ExtensionLoader
import com.kaolinmc.tooling.api.environment.ExtensionEnvironment
import com.kaolinmc.tooling.api.environment.dependencyTypesAttrKey
import com.kaolinmc.tooling.api.environment.wrkDirAttrKey
import com.kaolinmc.tooling.api.tweaker.EnvironmentTweaker
import java.nio.file.Path

val fabricRepository = SimpleMavenRepositorySettings.default(
    "https://maven.fabricmc.net",
    releasesEnabled = true, snapshotsEnabled = false
)

class FabricIntegrationTweaker : EnvironmentTweaker {
    override fun tweak(environment: ExtensionEnvironment) {
        // While capturing the environment of a tweaker is not good practice, we do it here.
        // TODO replace with just capturing environmental variables we need
        tweakerEnv = environment

        // Register the fabric loader dependency type (ONLY FOR THE FABRIC-INTEGRATION EXTENSION)
        val dependencyTypes = environment[dependencyTypesAttrKey].container
        val resolvers = environment[ExtensionLoader].graph.resolvers

        val flDepProvider = FabricLoaderDependencyResolverProvider()
        dependencyTypes.register(
            flDepProvider
        )
        resolvers.register(flDepProvider.resolver.libResolver)
        resolvers.register(flDepProvider.resolver)

        val curseMavenProvider = CurseMavenFabricModProvider(
            dependencyTypes["simple-maven"]!! as DependencyResolverProvider<SimpleMavenDescriptor, SimpleMavenArtifactRequest, SimpleMavenRepositorySettings>,
        )
        dependencyTypes.register(
            curseMavenProvider
        )
        resolvers.register(curseMavenProvider.resolver)

        val modrinthProvider = ModrinthFabricModProvider(
            ModrinthFabricModDependencyResolver(
                ModrinthFabricModProvider::class.java.classLoader,
                environment.minecraft.version
            )

        )
        dependencyTypes.register(
            modrinthProvider
        )
        resolvers.register(modrinthProvider.resolver)

        // Set the minecraft version
        minecraftVersion = environment[ApplicationTarget].node.descriptor.version

        val mixinAgents = environment[instrumentAgentsAttrKey]

        mixinAgents.add(
            0,
            EntrypointMixinAgent().also {
                entrypointAgent = it
            }
        )
        mixinAgents.add(
            1,
            SpongeMixinAgent().also {
                spongeMixinAgent = it
            }
        )
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
        lateinit var knotClassloader: ClassLoader

        // The path to where fabrics tiny mappings are. Will be mappings from
        // intermediary to whatever Kaolin is running in.
        val fabricMappingsPath
            get() = tweakerEnv[wrkDirAttrKey].value resolve "mappings" resolve "tiny" resolve tweakerEnv[mappingTargetAttrKey].value.path resolve "$minecraftVersion.tiny"

        // Whether to turn off access to Minecraft's resource from fabric, this forces
        // the fabric-loader to get them through Kaolin instead.
        var turnOffResources: Boolean = false

        val minecraftPath: Path by lazy {
            (((tweakerEnv[ApplicationTarget] as InstrumentedApplicationTarget).delegate) as MinecraftAppApi).gameJar
        }

        lateinit var entrypointAgent: EntrypointMixinAgent
            private set

        lateinit var spongeMixinAgent: SpongeMixinAgent
            private set
    }
}



