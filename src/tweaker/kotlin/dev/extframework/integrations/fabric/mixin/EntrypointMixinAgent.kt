package dev.extframework.integrations.fabric.mixin

import dev.extframework.extension.core.mixin.MixinAgent
import dev.extframework.extension.core.util.withDots
import org.objectweb.asm.tree.ClassNode

class EntrypointMixinAgent : MixinAgent {
    private val patches: MutableMap<String, ClassNode> = HashMap()

    fun registerPatches(
        patches: List<ClassNode>
    ) {
       this.patches.putAll(
           patches.associateBy {
               it.name.withDots()
           }
       )
    }

    override fun transformClass(name: String, node: ClassNode?): ClassNode? {
        return patches[name] ?: node
    }
}