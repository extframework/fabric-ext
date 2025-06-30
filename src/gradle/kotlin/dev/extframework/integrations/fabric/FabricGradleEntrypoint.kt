package dev.extframework.integrations.fabric

import dev.extframework.gradle.api.ExtframeworkExtension
import dev.extframework.gradle.api.GradleEntrypoint
import dev.extframework.integrations.fabric.dependency.ModrinthFabricModProvider
import dev.extframework.minecraft.MinecraftGradleEntrypoint
import dev.extframework.tooling.api.ExtensionLoader
import dev.extframework.tooling.api.environment.dependencyTypesAttrKey

class FabricGradleEntrypoint : GradleEntrypoint {
    override suspend fun configure(
        extension: ExtframeworkExtension,
        helper: GradleEntrypoint.Helper
    ) {
        for (environment in extension.environments) {
            if (!environment.contains(MinecraftGradleEntrypoint.minecraftAwareAttrKey)) continue

            helper.tweak(environment)

            // Overwriting non-source producing mod resolvers.
            val resolver = ModrinthSourcesFabricModDependencyResolver(
                environment,
                extension.worker.dataDir
            )
            environment[dependencyTypesAttrKey].container.register(
                ModrinthFabricModProvider(
                    resolver
                )
            )
            environment[ExtensionLoader].graph.resolvers.register(resolver)
        }
    }
}