package dev.deadearth.legacyprobe.defense;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.defense.MaterialHp;
import com.dhanantry.scapeandrunparasites.legacyprobe.NativeWriter;

/** Real World write guard, ordinary materials, disk reload and native harvest boundaries. */
public final class MaterialHpProbe {
  private static void check(boolean ok, String reason) {
    if (!ok) throw new AssertionError(reason);
  }

  private static Object state(String id) {
    return Reflect.call(Reflect.registry("BLOCKS", id), "getDefaultState/func_176223_P");
  }

  public static Object run(LegacyRuntime runtime, Object world, int x, int y, int z) {
    runtime.requireThread();
    Object pos = Reflect.pos(x, y, z);
    check(!runtime.protectedAt(world, pos), "material HP fixture outside grace");
    long now = ((Number) Reflect.call(world, "getTotalWorldTime/func_82737_E")).longValue();
    try {
      Reflect.call(world, "setBlockState/func_180501_a", pos, state("minecraft:cobblestone"), 3);
      check(MaterialHp.remaining(world, pos) == 300, "native cobblestone starts at300HP");
      check(!NativeWriter.write(world, pos, state("minecraft:air")), "native SRP attempt intercepted");
      check(Reflect.blockId(world, pos).equals("minecraft:cobblestone")
          && MaterialHp.remaining(world, pos) == 200, "first native write consumes100HP");
      NativeWriter.write(world, pos, state("minecraft:air"));
      check(MaterialHp.remaining(world, pos) == 200, "same-tick repeated attempt is coalesced");
      MaterialHp.discardCacheForTest();
      check(MaterialHp.remaining(world, pos) == 200, "actual per-chunk file restores native damage");
      for (int i = 1; i <= 2; i++) {
        Reflect.call(Reflect.call(world, "getWorldInfo/func_72912_H"),
            "setWorldTotalTime/func_82572_b", now + 20L * i);
        NativeWriter.write(world, pos, state("minecraft:air"));
      }
      check(Reflect.blockId(world, pos).equals("minecraft:air"), "third native attempt finishes cobblestone");
      Reflect.call(world, "setBlockState/func_180501_a", pos, state("hbm:concrete"), 3);
      check(MaterialHp.remaining(world, pos) == 4000, "HBM construction block receives configured HP");
      MaterialHp.damage(world, pos, 1000, false);
      check(MaterialHp.remaining(world, pos) == 3000, "ordinary explosion-style damage consumes HP");
      MaterialHp.discardCacheForTest();
      check(MaterialHp.remaining(world, pos) == 3000, "HBM concrete damage reloads");
      Reflect.call(world, "setBlockToAir/func_175698_g", pos);
      Reflect.call(world, "setBlockState/func_180501_a", pos, state("minecraft:chest"), 3);
      check(MaterialHp.maximum(world, pos) == 0, "containers never enter generic material HP");
      Reflect.call(world, "setBlockToAir/func_175698_g", pos);
      return JsonFiles.map("cobblestoneHp", 300, "realHostileWorldWrites", true,
          "sameTickCooldown", true, "actualDiskReload", true,
          "hbmConcreteHp", 4000, "containersExcluded", true);
    } finally {
      Reflect.call(Reflect.call(world, "getWorldInfo/func_72912_H"),
          "setWorldTotalTime/func_82572_b", now);
    }
  }
}
