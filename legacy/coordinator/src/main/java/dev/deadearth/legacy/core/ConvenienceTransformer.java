package dev.deadearth.legacy.core;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Native water limit and a dedicated-server guard around VSB's client-only color lookup. */
final class ConvenienceTransformer {
  private ConvenienceTransformer() {}
  static byte[] transform(String name, byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    if (name.equals("net.minecraft.item.ItemPotion")) {
      for (MethodNode m : node.methods)
        if (m.name.equals("getItemStackLimit") && m.desc.equals("(Lnet/minecraft/item/ItemStack;)I"))
          throw new IllegalStateException("Unexpected native per-stack potion limit");
      MethodNode limit = new MethodNode(Opcodes.ACC_PUBLIC, "getItemStackLimit", "(Lnet/minecraft/item/ItemStack;)I", null, null);
      limit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
      limit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/deadearth/legacy/gameplay/WaterStacks", "potionLimit", "(Ljava/lang/Object;)I", false));
      limit.instructions.add(new InsnNode(Opcodes.IRETURN));
      node.methods.add(limit);
    } else {
      boolean found = false;
      for (MethodNode m : node.methods) {
        if (!(m.name.equals("func_150895_a") || m.name.equals("getSubItems"))
            || !m.desc.equals("(Lnet/minecraft/creativetab/CreativeTabs;Lnet/minecraft/util/NonNullList;)V")) continue;
        LabelNode client = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/common/FMLCommonHandler", "instance", "()Lnet/minecraftforge/fml/common/FMLCommonHandler;", false));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fml/common/FMLCommonHandler", "getSide", "()Lnet/minecraftforge/fml/relauncher/Side;", false));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fml/relauncher/Side", "isClient", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, client));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(client);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(guard);
        found = true;
      }
      if (!found) throw new IllegalStateException("VSB creative color method absent");
    }
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }
}
