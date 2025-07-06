package com.kaolinmc.integrations.fabric.mixin

import com.kaolinmc.core.instrument.InstrumentAgent
import org.objectweb.asm.tree.ClassNode

class EntrypointMixinAgent : InstrumentAgent {
    private val patches: MutableMap<String, ClassNode> = HashMap()

    fun registerPatches(
        patches: List<ClassNode>
    ) {
        this.patches.putAll(
            patches.associateBy {
                it.name.replace('/', '.')
            }
        )
    }

    override fun transformClass(name: String, node: ClassNode?): ClassNode? {
        return patches[name] ?: node
    }
}