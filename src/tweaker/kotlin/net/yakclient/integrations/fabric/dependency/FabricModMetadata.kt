package net.yakclient.integrations.fabric.dependency

import com.fasterxml.jackson.annotation.JsonIgnoreProperties


@JsonIgnoreProperties(ignoreUnknown = true)
data class FabricModMetadata(
    val id: String,
    val version: String,
    val name: String,
    val description: String?,
    //TODO val authors
    //TODO contributors
    val contact: Map<String, String> = mapOf(),
    //TODO Icon
    val license: String?,
    val environment: String = "*",
    val entrypoints: Map<String, List<String>> = mapOf(),

    val depends: Map<String, String> = mapOf(),
    val recommends: Map<String, String> = mapOf(),
    val suggests: Map<String, String> = mapOf(),
    val conflicts: Map<String, String> = mapOf(),
    val breaks: Map<String, String> = mapOf(),

)