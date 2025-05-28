package dev.extframework.integrations.fabric

import com.durganmcbroom.jobs.Job
import com.durganmcbroom.jobs.job
import dev.extframework.gradle.api.BuildEnvironment
import dev.extframework.gradle.api.GradleEntrypoint
import dev.extframework.tooling.api.environment.ExtensionEnvironment
import org.gradle.api.Project

class FabricGradleEntrypoint : GradleEntrypoint {
    override fun apply(project: Project) {
    }

    override fun tweak(root: BuildEnvironment): Job<Unit> = job {
    }
}