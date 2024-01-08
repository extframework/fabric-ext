package net.yakclient.integrations.fabric.util

import net.fabricmc.loader.impl.lib.mappingio.MappingVisitor
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree
import net.fabricmc.loader.impl.lib.mappingio.tree.VisitOrder

fun emptyMappings(src: String, dst: String) = object : MappingTree {
    override fun getSrcNamespace(): String {
        return src
    }

    override fun getDstNamespaces(): MutableList<String> {
        return mutableListOf(dst)
    }

    override fun getClasses(): MutableCollection<out MappingTree.ClassMapping> {
        return mutableListOf()
    }

    override fun getClass(p0: String?): MappingTree.ClassMapping? {
        return null
    }

    override fun accept(p0: MappingVisitor?, p1: VisitOrder?) {}
}