package dev.deadearth.legacyprobe.gameplay;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.gameplay.NativeTools;
import java.lang.invoke.*;
import java.util.*;

/** Actual native HBM harvesting in a disposable world; no replacement harvesting implementation. */
public final class HammerMiningProbe {
  private HammerMiningProbe() {}

  public static Map<String, Object> run(Object world, Object player, int x, int y, int z)
      throws Throwable {
    if (!Boolean.getBoolean("deadearth.testControls")) throw new AssertionError("test world only");
    Object hand = Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "MAIN_HAND");
    Object held = Reflect.call(player, "getHeldItemMainhand/func_184614_ca");
    Object connection = Reflect.get(player, "connection/field_71135_a");
    double px = ((Number) Reflect.get(player, "posX/field_70165_t")).doubleValue(),
        py = ((Number) Reflect.get(player, "posY/field_70163_u")).doubleValue(),
        pz = ((Number) Reflect.get(player, "posZ/field_70161_v")).doubleValue();
    float yaw = ((Number) Reflect.get(player, "rotationYaw/field_70177_z")).floatValue(),
        pitch = ((Number) Reflect.get(player, "rotationPitch/field_70125_A")).floatValue();
    MethodHandle mine = MethodHandles.publicLookup().findVirtual(
        Reflect.type("com.hbm.items.tool.ItemToolAbility"), "onBlockStartBreak",
        MethodType.methodType(boolean.class, Reflect.type("net.minecraft.item.ItemStack"),
            Reflect.type("net.minecraft.util.math.BlockPos"),
            Reflect.type("net.minecraft.entity.player.EntityPlayer")));
    List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
    try {
      if (connection == null) {
        Object network = Reflect.make("net.minecraft.network.NetworkManager",
            Reflect.get(Reflect.type("net.minecraft.network.EnumPacketDirection"), "SERVERBOUND"));
        Reflect.make("net.minecraft.network.NetHandlerPlayServer",
            Reflect.call(world, "getMinecraftServer/func_73046_m"), network, player);
      }
      for (int tier : new int[] {2, 3}) {
        int radius = tier, cx = x + (tier - 2) * 16;
        for (int dx = -radius - 1; dx <= radius + 1; dx++)
          for (int dy = -radius - 1; dy <= radius * 2 + 1; dy++)
            for (int dz = -3; dz <= 1; dz++)
              put(world, cx + dx, y + dy, z + dz,
                  dz == 0 ? "minecraft:stone" : "minecraft:air");
        Object stack = Reflect.make("net.minecraft.item.ItemStack",
            Reflect.registry("ITEMS", "dead_earth:hammer_v" + tier));
        NativeTools.initialize(stack);
        Reflect.call(player, "setHeldItem/func_184611_a", hand, stack);
        Reflect.call(player, "setPositionAndRotation/func_70080_a",
            cx + .5, (double) y, z - 2.5, 0f, 0f);
        boolean handled = (boolean) mine.invokeWithArguments(
            Reflect.call(stack, "getItem/func_77973_b"), stack,
            Reflect.pos(cx, y + 1, z), player);
        int removed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
          for (int dy = 0; dy <= radius * 2; dy++) {
            if (!Reflect.blockId(world, Reflect.pos(cx + dx, y + dy, z))
                .equals("minecraft:air")) throw new AssertionError("native hammer misses tunnel cell");
            removed++;
          }
          for (int dy : new int[] {-1, radius * 2 + 1})
            if (!Reflect.blockId(world, Reflect.pos(cx + dx, y + dy, z))
                .equals("minecraft:stone")) throw new AssertionError("native hammer damages floor/ceiling");
        }
        if (!handled || removed != (2 * radius + 1) * (2 * radius + 1))
          throw new AssertionError("native hammer area");
        int wear = ((Number) Reflect.call(stack, "getItemDamage/func_77952_i")).intValue();
        if (wear < 1) throw new AssertionError("native durability charge retained");
        cases.add(JsonFiles.map("tier", tier, "removed", removed,
            "floorPreserved", true, "ceilingPreserved", true, "nativeWear", wear));
      }
    } finally {
      Reflect.call(player, "setHeldItem/func_184611_a", hand, held);
      Reflect.call(player, "setPositionAndRotation/func_70080_a", px, py, pz, yaw, pitch);
      if (connection == null) Reflect.set(player, "connection/field_71135_a", null);
    }
    return JsonFiles.map("nativeHbmHarvest", true, "cases", cases);
  }

  private static void put(Object world, int x, int y, int z, String id) {
    Reflect.call(world, "setBlockState/func_180501_a", Reflect.pos(x, y, z),
        Reflect.call(Reflect.registry("BLOCKS", id), "getDefaultState/func_176223_P"), 3);
  }
}
