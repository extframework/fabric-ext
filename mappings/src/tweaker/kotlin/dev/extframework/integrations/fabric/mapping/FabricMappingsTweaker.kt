package dev.extframework.integrations.fabric.mapping

import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.job
import dev.extframework.common.util.resolve
import dev.extframework.core.minecraft.environment.mappingProvidersAttrKey
import dev.extframework.tooling.api.environment.ExtensionEnvironment
import dev.extframework.tooling.api.environment.wrkDirAttrKey
import dev.extframework.tooling.api.tweaker.EnvironmentTweaker

class FabricMappingsTweaker : EnvironmentTweaker {
    override fun tweak(environment: ExtensionEnvironment): Job<Unit> = job {
        environment[mappingProvidersAttrKey].add(
            FabricMappingProvider(
                RawFabricMappingProvider(
                    environment[wrkDirAttrKey].value resolve "mappings" resolve "raw-intermediary"
                )
            )
        )
    }
}