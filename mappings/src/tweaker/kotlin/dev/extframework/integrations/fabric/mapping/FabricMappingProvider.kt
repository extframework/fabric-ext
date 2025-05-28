package dev.extframework.integrations.fabric.mapping

import com.durganmcbroom.resources.Resource
import com.durganmcbroom.resources.toByteArray
import com.durganmcbroom.resources.toResource
import dev.extframework.archive.mapper.ArchiveMapping
import dev.extframework.archive.mapper.MappingsProvider
import dev.extframework.archive.mapper.parsers.tiny.TinyV1MappingsParser
import dev.extframework.boot.store.DataAccess
import dev.extframework.boot.store.DataStore
import dev.extframework.boot.store.DelegatingDataStore
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import dev.extframework.common.util.resolve
import dev.extframework.common.util.copyTo
import java.net.URL

class FabricMappingProvider(
    private val rawMappings: MappingsProvider
) : MappingsProvider {
    override val namespaces: Set<String> = setOf("mojang:obfuscated", "fabric:intermediary")

    override fun forIdentifier(identifier: String): ArchiveMapping {
        val parse = rawMappings.forIdentifier(identifier)
        val mapped = parse.mapNamespaces(
            "official" to "mojang:obfuscated",
            "intermediary" to INTERMEDIARY_NAMESPACE
        )
        return mapped
    }

    companion object {
        const val INTERMEDIARY_NAMESPACE = "fabric:intermediary"
    }
}

internal class RawFabricMappingProvider (
    val store: DataStore<String, Resource>
) : MappingsProvider {
    constructor(path: Path) : this(DelegatingDataStore(IntermediaryMappingAccess(path)))

    override val namespaces: Set<String> = setOf("named", "intermediary")

    override fun forIdentifier(identifier: String): ArchiveMapping = runBlocking {
        val mappingData = store[identifier] ?: run {
            val url = URL("https://raw.githubusercontent.com/FabricMC/intermediary/master/mappings/$identifier.tiny")

            url.toResource().also {
                store.put(identifier, it)
            }
        }

        TinyV1MappingsParser.parse(ByteArrayInputStream(mappingData.open().toByteArray()))
    }
}

internal class IntermediaryMappingAccess(
    private val path: Path
) : DataAccess<String, Resource> {
    override fun read(key: String): Resource? {
        val versionPath = path resolve "client-mappings-$key.json"

        if (!versionPath.exists()) return null

        return versionPath.toResource()
    }

    override fun write(key: String, value: Resource) = runBlocking() {
        val versionPath = path resolve "client-mappings-$key.json"
        versionPath.deleteIfExists()

        value copyTo versionPath

        Unit
    }
}