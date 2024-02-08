package net.yakclient.integrations.fabric

import kotlinx.coroutines.runBlocking
import net.yakclient.archive.mapper.ArchiveMapping
import net.yakclient.archive.mapper.parsers.proguard.ProGuardMappingParser
import net.yakclient.archive.mapper.parsers.tiny.TinyV1MappingsParser
import net.yakclient.boot.store.DataAccess
import net.yakclient.boot.store.DataStore
import net.yakclient.boot.store.DelegatingDataStore
import net.yakclient.common.util.copyTo
import net.yakclient.common.util.resolve
import net.yakclient.common.util.resource.SafeResource
import net.yakclient.common.util.toResource
import net.yakclient.components.extloader.api.mapping.MappingsProvider
import net.yakclient.components.extloader.extension.mapping.MojangExtensionMappingProvider
import net.yakclient.integrations.fabric.util.mapNamespaces
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class FabricMappingProvider(
    private val rawMappings: MappingsProvider
) : MappingsProvider {
    override val namespaces: Set<String> = setOf(MojangExtensionMappingProvider.FAKE_TYPE, "fabric:intermediary")

    override fun forIdentifier(identifier: String): ArchiveMapping {
        val parse = rawMappings.forIdentifier(identifier)
        val mapped = parse.mapNamespaces(
            "official" to MojangExtensionMappingProvider.FAKE_TYPE,
            "intermediary" to INTERMEDIARY_NAMESPACE
        )
        return mapped
    }

    companion object {
        const val INTERMEDIARY_NAMESPACE = "fabric:intermediary"
    }
}

internal class RawFabricMappingProvider private constructor(
    val store: DataStore<String, SafeResource>
) : MappingsProvider {

    public constructor(path: Path) : this(DelegatingDataStore(IntermediaryMappingAccess(path)))

    override val namespaces: Set<String> = setOf("named", "intermediary")

    override fun forIdentifier(identifier: String): ArchiveMapping {
        val mappingData = store[identifier] ?: run {
            val url = URL("https://raw.githubusercontent.com/FabricMC/intermediary/master/mappings/$identifier.tiny")

            object : SafeResource {
                override val uri: URI = url.toURI()

                override fun open(): InputStream {
                    return url.openStream()
                }
            }
        }

        return TinyV1MappingsParser.parse(mappingData.open())
    }
}

private class IntermediaryMappingAccess(
    private val path: Path
) : DataAccess<String, SafeResource> {
    override fun read(key: String): SafeResource? {
        val versionPath = path resolve "client-mappings-$key.json"

        if (!versionPath.exists()) return null

        return versionPath.toUri().toResource()
    }

    override fun write(key: String, value: SafeResource) {
        val versionPath = path resolve "client-mappings-$key.json"
        versionPath.deleteIfExists()

        runBlocking {
            value.copyTo(versionPath)
        }
    }
}