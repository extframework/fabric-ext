package dev.extframework.integrations.fabric.dependency

import com.durganmcbroom.artifact.resolver.ArtifactMetadata
import com.durganmcbroom.artifact.resolver.simple.maven.SimpleMavenDescriptor
import dev.extframework.archives.ArchiveHandle
import dev.extframework.archives.ArchiveReference
import dev.extframework.archives.Archives
import dev.extframework.boot.archive.ArchiveAccessTree
import dev.extframework.boot.dependency.DependencyNode
import dev.extframework.common.util.copyTo
import dev.extframework.integrations.fabric.FabricIntegrationTweaker
import dev.extframework.tooling.api.environment.getOrNull
import java.nio.file.Files
import java.nio.file.Path

//abstract class FabricModDescriptor : ArtifactMetadata.Descriptor

class FabricModNode<T: ArtifactMetadata.Descriptor>(
    val path: Path?,
    override val descriptor: T,
    override val access: ArchiveAccessTree,
) : DependencyNode<T>() {
    val packages = path?.let {
        Archives.find(it, Archives.Finders.ZIP_FINDER).use {
            fabricJarToPackages(
                it
            )
        }
    } ?: setOf()

    override val handle: ArchiveHandle = object : ArchiveHandle {
        // Does not need to be parallel capable.
        override val classloader: ClassLoader = object : ClassLoader() {
            override fun loadClass(name: String): Class<*> {
                return FabricIntegrationTweaker.knotClassloader.getOrNull()?.loadClass(name)
                    ?: throw ClassNotFoundException(name)
            }
        }
        override val name: String? = null
        override val packages: Set<String> = this@FabricModNode.packages
        override val parents: Set<ArchiveHandle> = setOf()
    }
}

private fun fabricJarToPackages(
    archive: ArchiveReference
): Set<String> {
    return archive.reader.entries()
        .filter { it.name.endsWith(".class") }
        .mapTo(HashSet()) { it.name.substringBeforeLast("/").replace('/', '.') } + archive.reader.entries()
        .filter { it.name.endsWith(".jar") && it.name.startsWith("META-INF/jars") }
        .flatMapTo(HashSet()) {
            val tmp = Files.createTempFile(
                it.name.removePrefix("META-INF/jars/").removeSuffix(".jar"),
                "jar"
            )
            it.resource copyTo tmp
            fabricJarToPackages(Archives.find(tmp, Archives.Finders.ZIP_FINDER))
        }
}