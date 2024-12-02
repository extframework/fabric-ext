package dev.extframework.integrations.fabric.mixin

import dev.extframework.extension.core.mixin.MixinAgent
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

class AccessWidenerMixinAgent : MixinAgent {
    private fun Int.del(flag: Int): Int {
        return if (this and flag == flag) this xor flag
        else this
    }

    private fun removeFlags(
        access: Int,
    ): Int {
        var modified = access
            .del(Opcodes.ACC_PRIVATE)
            .del(Opcodes.ACC_PROTECTED)
            .or(Opcodes.ACC_PUBLIC)

        if (modified.and(Opcodes.ACC_STATIC) != Opcodes.ACC_STATIC) {
            modified = modified.del(Opcodes.ACC_FINAL)
        }

        return modified
    }

    // This is a poor solution
    override fun transformClass(
        name: String,
        node: ClassNode?
    ): ClassNode? {
        if (node == null) return null

//        if (name.startsWith("org.lwjgl")
//            || name.startsWith("org.slf4j")
//            || name.startsWith("io.netty")
//        ) return node

        node.access = removeFlags(node.access)
        node.innerClasses?.forEach {
            it.access = removeFlags(it.access)
        }
        node.fields?.forEach {
            it.access = removeFlags(it.access)
        }
        node.methods?.forEach {
            it.access = removeFlags(it.access)
        }

        return node
    }
}