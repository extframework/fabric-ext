package com.kaolinmc.integrations.fabric

import com.kaolinmc.kiln.api.EvaluatingDependency
import com.kaolinmc.minecraft.MinecraftPartitionDependencyHandler
import com.kaolinmc.minecraft.MinecraftPartitionHandler
import com.kaolinmc.tooling.api.extension.ExtensionRepository

fun MinecraftPartitionDependencyHandler.fabric(
    projectId: String,
    versionId: String
) {
    addDependency(EvaluatingDependency.Raw(
        mapOf(
            "projectId" to projectId,
            "versionId" to versionId
        )
    ))
}

fun MinecraftPartitionHandler.useModrinth() {
    model {
        it.repositories.add(ExtensionRepository(
            "fabric-mod:modrinth",
            mapOf()
        ))
    }
}