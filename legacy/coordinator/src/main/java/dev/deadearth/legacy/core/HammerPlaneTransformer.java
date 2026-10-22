package dev.deadearth.legacy.core;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Changes only the vertical iteration bounds; native harvesting and reference position survive. */
final class HammerPlaneTransformer {
  private HammerPlaneTransformer() {}

  static byte[] transform(byte[] bytes) {
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    int lower = 0, upper = 0;
    for (MethodNode method : node.methods) {
      if (!method.name.equals("onDig")
          || !method.desc.equals("(ILnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/player/EntityPlayer;Lcom/hbm/items/tool/ItemToolAbility;)Z")) continue;
      int y = -1, radius = -1;
      for (AbstractInsnNode i = method.instructions.getFirst(); i != null; i = i.getNext()) {
        if (!(i instanceof MethodInsnNode)) continue;
        MethodInsnNode call = (MethodInsnNode) i;
        AbstractInsnNode next = next(i);
        if ((call.name.equals("func_177956_o") || call.name.equals("getY"))
            && call.desc.equals("()I") && next instanceof VarInsnNode
            && next.getOpcode() == Opcodes.ISTORE) y = ((VarInsnNode) next).var;
      }
      if (y < 0) throw new IllegalStateException("HBM flat hammer Y anchor absent");
      for (AbstractInsnNode i = method.instructions.getFirst(); i != null; ) {
        AbstractInsnNode following = i.getNext();
        if (i.getOpcode() == Opcodes.ISUB || i.getOpcode() == Opcodes.IADD) {
          AbstractInsnNode r = previous(i), center = previous(r);
          if (center instanceof VarInsnNode && r instanceof VarInsnNode
              && center.getOpcode() == Opcodes.ILOAD && r.getOpcode() == Opcodes.ILOAD
              && ((VarInsnNode) center).var == y) {
            if (radius != -1 && radius != ((VarInsnNode) r).var)
              throw new IllegalStateException("HBM flat hammer radius changed");
            radius = ((VarInsnNode) r).var;
            String hook = i.getOpcode() == Opcodes.ISUB ? "wallBottom" : "wallTop";
            if (i.getOpcode() == Opcodes.ISUB) lower++; else upper++;
            InsnList replacement = new InsnList();
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 4));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, 5));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/deadearth/legacy/gameplay/NativeTools", hook,
                "(IILjava/lang/Object;Ljava/lang/Object;)I", false));
            method.instructions.insertBefore(i, replacement);
            method.instructions.remove(i);
          }
        }
        i = following;
      }
    }
    if (lower != 1 || upper != 1)
      throw new IllegalStateException("HBM flat hammer loop bounds changed");
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }

  private static AbstractInsnNode previous(AbstractInsnNode node) {
    if (node == null) return null;
    do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
    return node;
  }

  private static AbstractInsnNode next(AbstractInsnNode node) {
    do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
    return node;
  }
}
