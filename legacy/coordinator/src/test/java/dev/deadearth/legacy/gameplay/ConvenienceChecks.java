package dev.deadearth.legacy.gameplay;

import dev.deadearth.legacy.core.LegacyTransformer;
import java.io.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ConvenienceChecks {
  public static void main(String[] args) throws Exception {
    ClassWriter mock = new ClassWriter(0);
    mock.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraft/item/ItemPotion", null, "java/lang/Object", null);
    mock.visitEnd();
    LegacyTransformer transformer = new LegacyTransformer();
    byte[] source = mock.toByteArray();
    byte[] patched = transformer.transform("net.minecraft.item.ItemPotion", "net.minecraft.item.ItemPotion", source);
    if (source == patched) throw new AssertionError("Water stack hook absent");
    ClassNode node = new ClassNode(); new ClassReader(patched).accept(node, 0);
    int hooks = 0;
    for (MethodNode m : node.methods)
      if (m.name.equals("getItemStackLimit") && m.desc.equals("(Lnet/minecraft/item/ItemStack;)I")) hooks++;
    if (hooks != 1) throw new AssertionError("Per-NBT limit override count");
    try (ZipFile jar = new ZipFile(args[0])) {
      InputStream in = jar.getInputStream(jar.getEntry("v0id/vsb/item/ItemBackpack.class"));
      ByteArrayOutputStream copy = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
      while ((n = in.read(buffer)) != -1) copy.write(buffer, 0, n);
      source = copy.toByteArray();
    }
    patched = transformer.transform("v0id.vsb.item.ItemBackpack", "v0id.vsb.item.ItemBackpack", source);
    node = new ClassNode(); new ClassReader(patched).accept(node, 0);
    boolean guard = false, nativeColor = false;
    for (MethodNode m : node.methods)
      for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext())
        if (i instanceof MethodInsnNode) {
          MethodInsnNode call = (MethodInsnNode) i;
          if (call.owner.equals("net/minecraftforge/fml/relauncher/Side") && call.name.equals("isClient")) guard = true;
          if (call.name.equals("func_193350_e")) nativeColor = true;
        }
    if (!guard || !nativeColor) throw new AssertionError("VSB client-only color guard");
    if (transformer.transform("unrelated", "unrelated", source) != source) throw new AssertionError("Unrelated class modified");
    System.out.println("Convenience ASM checks: native water override, pinned VSB side guard, unrelated class unchanged");
  }
}
