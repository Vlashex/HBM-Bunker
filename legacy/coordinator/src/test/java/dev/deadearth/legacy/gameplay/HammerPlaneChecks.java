package dev.deadearth.legacy.gameplay;

import dev.deadearth.legacy.core.LegacyTransformer;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Pure geometry plus transformation of the actual pinned HBM class, without a game world. */
public final class HammerPlaneChecks {
  private static int checks;
  private static void check(boolean condition) {
    checks++;
    if (!condition) throw new AssertionError("Hammer plane check " + checks);
  }
  public static void main(String[] args) throws Exception {
    for (int radius : new int[] {2, 3}) {
      for (int target = 64; target <= 64 + 2 * radius; target++) {
        check(HammerPlane.bottom(target, radius, 64.0, true) == 64);
        check(HammerPlane.bottom(target, radius, 64.9, true) == 64);
        check(HammerPlane.bottom(target, radius, 64.0, false) == target - radius);
      }
    }
    check(HammerPlane.bottom(65, 0, 64, true) == 65); // floor/ceiling horizontal plane
    check(HammerPlane.bottom(63, 2, 64, true) == 61); // deliberate target below player
    check(HammerPlane.bottom(75, 3, 64, true) == 72); // high target stays in original plane
    check(HammerPlane.bottom(-4, 3, -4.1, true) == -5);
    check(HammerPlane.bottom(65, 2, Double.NaN, true) == 63);
    byte[] original;
    String target = "com.hbm.handler.ability.IToolAreaAbility$4";
    try (ZipFile jar = new ZipFile(args[0])) {
      try (InputStream input = jar.getInputStream(jar.getEntry(target.replace('.', '/') + ".class"))) {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) copy.write(buffer, 0, n);
        original = copy.toByteArray();
      }
    }
    LegacyTransformer transformer = new LegacyTransformer();
    byte[] patched = transformer.transform(target, target, original);
    check(patched != original);
    check(transformer.transform("unrelated", "unrelated", original) == original);
    ClassNode node = new ClassNode();
    new ClassReader(patched).accept(node, 0);
    int lower = 0, upper = 0, harvest = 0;
    for (MethodNode method : node.methods) {
      for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
        if (!(insn instanceof MethodInsnNode)) continue;
        MethodInsnNode call = (MethodInsnNode) insn;
        if (call.owner.equals("dev/deadearth/legacy/gameplay/NativeTools")) {
          if (call.name.equals("wallBottom")) lower++;
          if (call.name.equals("wallTop")) upper++;
        }
        if (call.name.equals("breakExtraBlock")) harvest++;
      }
    }
    check(lower == 1 && upper == 1);
    check(harvest == 1); // original HBM harvest path and its reference position retained
    System.out.println("Hammer plane checks passed: " + checks);
  }
}
