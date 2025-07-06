package com.kaolinmc.integrations.fabric

import com.kaolinmc.kiln.api.KaolinExtension
import com.kaolinmc.kiln.api.GradleEntrypoint
import com.kaolinmc.integrations.fabric.dependency.ModrinthFabricModProvider
import com.kaolinmc.minecraft.MinecraftGradleEntrypoint
import com.kaolinmc.tooling.api.ExtensionLoader
import com.kaolinmc.tooling.api.environment.dependencyTypesAttrKey

class FabricGradleEntrypoint : GradleEntrypoint {
    override suspend fun configure(
        extension: KaolinExtension,
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