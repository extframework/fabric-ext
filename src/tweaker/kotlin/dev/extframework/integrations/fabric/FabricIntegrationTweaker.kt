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
import dev.extframework.core.app.api.ApplicationTarget
import dev.extframework.core.instrument.InstrumentedApplicationTarget
import dev.extframework.core.instrument.instrumentAgentsAttrKey
import dev.extframework.core.minecraft.api.MinecraftAppApi
import dev.extframework.core.minecraft.environment.mappingTargetAttrKey
import dev.extframework.integrations.fabric.dependency.CurseMavenFabricModProvider
import dev.extframework.integrations.fabric.dependency.ModrinthFabricModProvider
import dev.extframework.integrations.fabric.loader.FabricLoaderDependencyResolverProvider
import dev.extframework.integrations.fabric.mixin.EntrypointMixinAgent
import dev.extframework.integrations.fabric.mixin.SpongeMixinAgent
import dev.extframework.tooling.api.environment.*
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

        // Register the fabric loader dependency type (ONLY FOR THE FABRIC-INTEGRATION EXTENSION)
        val dependencyTypes = environment[dependencyTypesAttrKey].extract().container

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

        // Set the minecraft version
        minecraftVersion = environment[ApplicationTarget].map { it.node.descriptor.version }.extract()

        val mixinAgents by environment[instrumentAgentsAttrKey]

        mixinAgents.add(
            0,
            EntrypointMixinAgent().also {
                entrypointAgent = it
            }
        )
        mixinAgents.add(
            0,
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
        lateinit var knotClassloader: DeferredValue<ClassLoader>

        // The path to where fabrics tiny mappings are. Will be mappings from
        // intermediary to whatever extframework is running in.
        val fabricMappingsPath
            get() = tweakerEnv[wrkDirAttrKey].extract().value resolve "mappings" resolve "tiny" resolve tweakerEnv[mappingTargetAttrKey].extract().value.path resolve "$minecraftVersion.tiny"

        // Whether to turn off access to Minecraft's resource from fabric, this forces
        // the fabric-loader to get them through extframework instead.
        var turnOffResources: Boolean = false

        val minecraftPath: Path by lazy {
            (((tweakerEnv[ApplicationTarget].extract() as InstrumentedApplicationTarget).delegate) as MinecraftAppApi).gameJar
        }

        lateinit var entrypointAgent: EntrypointMixinAgent
            private set

        lateinit var spongeMixinAgent: SpongeMixinAgent
            private set
    }
}



