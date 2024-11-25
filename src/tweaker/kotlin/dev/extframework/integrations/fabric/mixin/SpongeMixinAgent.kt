package dev.extframework.integrations.fabric.mixin

import dev.extframework.extension.core.mixin.MixinAgent
import org.objectweb.asm.tree.ClassNode

class SpongeMixinAgent : MixinAgent {
    var delegateAgent: ((name: String, node: ClassNode?) -> ClassNode?)? = null

    override fun transformClass(name: String, node: ClassNode?): ClassNode? {
        return delegateAgent?.invoke(name, node) ?: node
    }
}