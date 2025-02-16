package dev.extframework.integrations.fabric.mixin

import dev.extframework.core.instrument.InstrumentAgent
import org.objectweb.asm.tree.ClassNode

class SpongeMixinAgent : InstrumentAgent {
    var delegateAgent: ((name: String, node: ClassNode?) -> ClassNode?)? = null

    override fun transformClass(name: String, node: ClassNode?): ClassNode? {
        return delegateAgent?.invoke(name, node) ?: node
    }
}