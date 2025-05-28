package dev.extframework.integrations.fabric

import dev.extframework.gradle.api.EvaluatingDependency
import dev.extframework.minecraft.MinecraftPartitionDependencyHandler
import dev.extframework.minecraft.MinecraftPartitionHandler
import dev.extframework.tooling.api.extension.ExtensionRepository

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