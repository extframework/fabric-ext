package net.yakclient.integrations.fabric.util

import net.yakclient.archive.mapper.*
import net.yakclient.common.util.LazyMap

fun ArchiveMapping.mapNamespaces(vararg transform: Pair<String, String>): ArchiveMapping {
    val to = { original: String ->
        transform.find {
            original == it.first
        }?.second ?: original
    }
    val from = { mapped: String ->
        transform.find {
            mapped == it.second
        }?.first ?: mapped
    }

    val toNamespaces = namespaces.mapTo(HashSet(), to)
    return ArchiveMapping(
        toNamespaces,
        MappingValueContainerImpl(LazyMap {
            val original = identifiers[from(it)]

            original?.copy(
                namespace = it
            )
        }),
        MappingNodeContainerImpl(
            classes.values.mapTo(HashSet()) { cls ->
                ClassMapping(
                    toNamespaces,
                    MappingValueContainerImpl(LazyMap {
                        val original = cls.identifiers[from(it)]

                        original?.copy(
                            namespace = it
                        )
                    }),
                    MappingNodeContainerImpl(
                        cls.methods.values.mapTo(HashSet()) { method ->
                            MethodMapping(
                                toNamespaces,
                                MappingValueContainerImpl(LazyMap {
                                    val original = method.identifiers[from(it)]

                                    original?.copy(
                                        namespace = it
                                    )
                                }),
                                MappingValueContainerImpl(LazyMap {
                                    method.lnStart?.get(from(it))
                                }),
                                MappingValueContainerImpl(LazyMap {
                                    method.lnEnd?.get(from(it))
                                }),
                                MappingValueContainerImpl(LazyMap {
                                    method.returnType[from(it)]
                                }),
                            )
                        }
                    ),
                    MappingNodeContainerImpl(
                        cls.fields.values.mapTo(HashSet()) { field ->
                            FieldMapping(
                                toNamespaces,
                                MappingValueContainerImpl(LazyMap {
                                    val original = field.identifiers[from(it)]

                                    original?.copy(
                                        namespace = it
                                    )
                                }),
                                MappingValueContainerImpl(LazyMap {
                                    field.type[from(it)]
                                }),
                            )
                        }
                    )
                )
            }
        )
    )
}