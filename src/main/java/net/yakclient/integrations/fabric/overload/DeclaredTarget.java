//package net.yakclient.integrations.fabric.overload;
//
//import org.objectweb.asm.Type;
//import org.spongepowered.asm.mixin.transformer.MixinInfo;
//
//public final class DeclaredTarget {
//
//    final String name;
//
//    final boolean isPrivate;
//
//    private DeclaredTarget(String name, boolean isPrivate) {
//        this.name = name;
//        this.isPrivate = isPrivate;
//    }
//
//    @Override
//    public String toString() {
//        return this.name;
//    }
//
//    static MixinInfo.DeclaredTarget of(Object target, MixinInfo info) {
//        if (target instanceof String) {
//            String remappedName = info.remapClassName((String)target);
//            return remappedName != null ? new MixinInfo.DeclaredTarget(remappedName, true) : null;
//        } else if (target instanceof Type) {
//            return new MixinInfo.DeclaredTarget(((Type)target).getClassName(), false);
//        }
//        return null;
//    }
//}
