package net.yakclient.integrations.fabric.util

//import net.fabricmc.mapping.reader.v2.TinyMetadata
//import net.fabricmc.mapping.tree.*
//import net.yakclient.archive.mapper.ArchiveMapping
//import net.yakclient.archive.mapper.MappingType
//
//public fun archiveMappingToTinyTree(
//    mappings: ArchiveMapping,
//    fake: String,
//    real: String
//): TinyTree {
//    return object : TinyTree {
//        private val classMap: Map<String, ClassDef> = mappings.classes
//            .asSequence()
//            .filter {
//                it.key.type == MappingType.FAKE
//            }.map {
//                it.key.name to it.value
//            }.map { (className, classMapping) ->
//                className to object : ClassDef {
//                    private val methods = classMapping.methods
//                        .asSequence()
//                        .filter { it.key.type == MappingType.FAKE }
//                        .map { (_, methodMapping) ->
//                            object : MethodDef {
//                                override fun getName(namespace: String?): String? {
//                                    return when (namespace) {
//                                        fake -> methodMapping.fakeIdentifier.name
//                                        real -> methodMapping.realIdentifier.name
//                                        else -> null
//                                    }
//                                }
//
//                                override fun getRawName(namespace: String?): String? {
//                                    return getName(namespace)
//                                }
//
//                                override fun getComment(): String? {
//                                    return null
//                                }
//
//                                override fun getDescriptor(namespace: String): String? {
//                                    return when (namespace) {
//                                        fake -> {
//                                            "(" + methodMapping.fakeIdentifier.parameters.joinToString(separator = "") {
//                                                it.descriptor
//                                            } + ")" + methodMapping.fakeReturnType.descriptor
//                                        }
//
//                                        real -> {
//                                            "(" + methodMapping.realIdentifier.parameters.joinToString(separator = "") {
//                                                it.descriptor
//                                            } + ")" + methodMapping.realReturnType.descriptor
//                                        }
//
//                                        else -> null
//                                    }
//                                }
//
//                                override fun getParameters(): MutableCollection<ParameterDef> {
//                                    return mutableListOf()
//                                }
//
//                                override fun getLocalVariables(): MutableCollection<LocalVariableDef> {
//                                    return mutableListOf()
//                                }
//                            }
//                        }.toList()
//                    private val fields = classMapping.fields
//                        .asSequence()
//                        .filter { it.key.type == MappingType.FAKE }
//                        .map { (_, fieldMapping) ->
//                            object : FieldDef {
//                                override fun getName(namespace: String?): String? {
//                                    return when (namespace) {
//                                        fake -> fieldMapping.fakeIdentifier.name
//                                        real -> fieldMapping.realIdentifier.name
//                                        else -> null
//                                    }
//                                }
//
//                                override fun getRawName(namespace: String?): String? {
//                                    return getName(namespace)
//                                }
//
//                                override fun getComment(): String? {
//                                    return null
//                                }
//
//                                override fun getDescriptor(namespace: String?): String? {
//                                    return when (namespace) {
//                                        fake -> fieldMapping.fakeType.descriptor
//                                        real -> fieldMapping.realType.descriptor
//                                        else -> null
//                                    }
//                                }
//                            }
//                        }.toList()
//
//                    override fun getName(namespace: String?): String? {
//                        return when (namespace) {
//                            fake -> classMapping.fakeIdentifier.name
//                            real -> classMapping.realIdentifier.name
//                            else -> null
//                        }
//                    }
//
//                    override fun getRawName(namespace: String?): String? {
//                        return getName(namespace)
//                    }
//
//                    override fun getComment(): String? {
//                        return null
//                    }
//
//                    override fun getMethods(): MutableCollection<MethodDef> {
//                        return methods.toMutableList()
//                    }
//
//                    override fun getFields(): MutableCollection<FieldDef> {
//                        return fields.toMutableList()
//                    }
//
//                }
//            }.associate { it }
//        private val metadata: TinyMetadata = object : TinyMetadata {
//            override fun getMajorVersion(): Int {
//                return 1
//            }
//
//            override fun getMinorVersion(): Int {
//                return 0
//            }
//
//            override fun getNamespaces(): MutableList<String> {
//                return mutableListOf(
//                    real,
//                    fake
//                )
//            }
//
//            override fun getProperties(): MutableMap<String, String?> {
//                return mutableMapOf()
//            }
//        }
//
//        override fun getMetadata(): TinyMetadata {
//            return metadata
//        }
//
//        override fun getDefaultNamespaceClassMap(): MutableMap<String, ClassDef> {
//            return classMap.toMutableMap()
//        }
//
//        override fun getClasses(): MutableCollection<ClassDef> {
//            return classMap.values.toMutableList()
//        }
//
//    }
//}