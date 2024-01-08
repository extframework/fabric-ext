package net.yakclient.integrations.fabric

import net.yakclient.archive.mapper.ArchiveMapping
import net.yakclient.archive.mapper.parsers.tiny.TinyV1MappingsParser
import net.yakclient.components.extloader.api.mapping.MappingsProvider
import net.yakclient.components.extloader.extension.mapping.MojangExtensionMappingProvider
import net.yakclient.integrations.fabric.util.mapNamespaces
import java.net.URL

class FabricMappingProvider: MappingsProvider {
    override val namespaces: Set<String> = setOf(MojangExtensionMappingProvider.FAKE_TYPE, "fabric:intermediary")

    override fun forIdentifier(identifier: String): ArchiveMapping {
        val parse = rawTinyMappings(identifier)
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

internal fun rawTinyMappings(identifier: String) = TinyV1MappingsParser.parse(
    URL("https://raw.githubusercontent.com/FabricMC/intermediary/master/mappings/$identifier.tiny").openStream()
)