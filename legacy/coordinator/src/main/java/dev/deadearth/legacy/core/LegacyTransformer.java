package dev.deadearth.legacy.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Narrow native mutation and sight boundaries; no navigation or combat task replacement. */
public final class LegacyTransformer implements IClassTransformer {
  public byte[] transform(String name, String transformedName, byte[] bytes) {
    if (bytes == null) return null;
    if ("com.hbm.handler.ability.IToolAreaAbility$4".equals(transformedName))
      return HammerPlaneTransformer.transform(bytes);
    boolean world = "net.minecraft.world.World".equals(transformedName);
    boolean digging = "funwayguy.epicsiegemod.ai.ESM_EntityAIDigging".equals(transformedName);
    boolean livingSight = "net.minecraft.entity.EntityLivingBase".equals(transformedName);
    boolean senses = "funwayguy.epicsiegemod.ai.hooks.EntitySensesProxy".equals(transformedName);
    if (!world && !digging && !senses && !livingSight) return bytes;
    ClassNode node = new ClassNode();
    new ClassReader(bytes).accept(node, 0);
    boolean found = false, sightOwner = false;
    for (MethodNode method : node.methods) {
      if (livingSight
          && (method.name.equals("func_70685_l") || method.name.equals("canEntityBeSeen"))
          && method.desc.equals("(Lnet/minecraft/entity/Entity;)Z")) {
        InsnList sight = new InsnList();
        LabelNode nativeSight = new LabelNode();
        sight.add(new VarInsnNode(Opcodes.ALOAD, 0));
        sight.add(new VarInsnNode(Opcodes.ALOAD, 1));
        sight.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/deadearth/legacy/combat/LegacyPerception",
                "applies",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false));
        sight.add(new JumpInsnNode(Opcodes.IFEQ, nativeSight));
        sight.add(new VarInsnNode(Opcodes.ALOAD, 0));
        sight.add(new VarInsnNode(Opcodes.ALOAD, 1));
        sight.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/deadearth/legacy/combat/LegacyPerception",
                "playerVisible",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false));
        sight.add(new InsnNode(Opcodes.IRETURN));
        sight.add(nativeSight);
        sight.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(sight);
        found = true;
      }
      if (senses && (method.name.equals("func_75522_a") || method.name.equals("canSee"))) {
        for (org.objectweb.asm.tree.AbstractInsnNode instruction = method.instructions.getFirst();
            instruction != null;
            instruction = instruction.getNext()) {
          if (!(instruction instanceof MethodInsnNode)) continue;
          MethodInsnNode call = (MethodInsnNode) instruction;
          if (call.owner.equals("funwayguy/epicsiegemod/config/types/CfgPropNumber")
              && call.name.equals("get")
              && call.desc.equals("(Lnet/minecraft/entity/Entity;)Ljava/lang/Object;")) {
            org.objectweb.asm.tree.AbstractInsnNode argument = call.getPrevious();
            org.objectweb.asm.tree.AbstractInsnNode property =
                argument == null ? null : argument.getPrevious();
            if (argument instanceof VarInsnNode
                && argument.getOpcode() == Opcodes.ALOAD
                && ((VarInsnNode) argument).var == 1
                && property instanceof org.objectweb.asm.tree.FieldInsnNode
                && ((org.objectweb.asm.tree.FieldInsnNode) property).name.equals("XRAY_VIEW")) {
              InsnList owner = new InsnList();
              owner.add(new VarInsnNode(Opcodes.ALOAD, 0));
              owner.add(
                  new org.objectweb.asm.tree.FieldInsnNode(
                      Opcodes.GETFIELD,
                      "funwayguy/epicsiegemod/ai/hooks/EntitySensesProxy",
                      "entityObj",
                      "Lnet/minecraft/entity/EntityLiving;"));
              method.instructions.insertBefore(argument, owner);
              method.instructions.remove(argument);
              sightOwner = true;
            }
          }
          if (call.owner.equals("net/minecraft/entity/EntityLiving")
              && (call.name.equals("func_70685_l") || call.name.equals("canEntityBeSeen"))
              && call.desc.equals("(Lnet/minecraft/entity/Entity;)Z")) {
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = "dev/deadearth/legacy/combat/LegacySiege";
            call.name = "visible";
            call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
            call.itf = false;
            found = true;
          }
        }
      }
      boolean worldWrite =
          world
              && (method.name.equals("func_180501_a") || method.name.equals("setBlockState"))
              && method.desc.equals(
                  "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z");
      boolean digAdmission =
          digging
              && method.name.equals("canHarvest")
              && method.desc.equals(
                  "(Lnet/minecraft/entity/EntityLiving;Lnet/minecraft/util/math/BlockPos;)Z");
      if (!worldWrite && !digAdmission) continue;
      InsnList guard = new InsnList();
      LabelNode pass = new LabelNode();
      if (worldWrite) {
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 2));
        guard.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/deadearth/legacy/MutationGuard",
                "allow",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
                false));
      } else {
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 2));
        guard.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/deadearth/legacy/combat/LegacySiege",
                "allowDig",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false));
      }
      guard.add(new JumpInsnNode(Opcodes.IFNE, pass));
      guard.add(new InsnNode(Opcodes.ICONST_0));
      guard.add(new InsnNode(Opcodes.IRETURN));
      guard.add(pass);
      guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
      method.instructions.insert(guard);
      found = true;
    }
    if (senses && !sightOwner)
      throw new IllegalStateException("DEAD EARTH native sight owner target absent");
    if (!found)
      throw new IllegalStateException("DEAD EARTH native guard target absent: " + transformedName);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }
}
