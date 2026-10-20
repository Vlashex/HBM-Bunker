package dev.deadearth.legacy.defense;

import dev.deadearth.legacy.*;
import java.util.*;

/** One modest shared shelter. Deterministic bounded search reads already-loaded terrain only. */
final class StarterShelter {
  private static final int SEARCH_RADIUS = 64, STEP = 8, FOUNDATION = 8;
  private static final Set<String> NATURAL =
      new HashSet<String>(
          Arrays.asList(
              "dead_earth:ash",
              "minecraft:grass",
              "minecraft:dirt",
              "minecraft:stone",
              "minecraft:cobblestone",
              "minecraft:gravel",
              "minecraft:sand",
              "minecraft:sandstone",
              "minecraft:red_sandstone",
              "minecraft:clay",
              "minecraft:hardened_clay",
              "minecraft:stained_hardened_clay",
              "minecraft:log",
              "minecraft:log2",
              "minecraft:snow",
              "minecraft:snow_layer"));
  private static final Set<String> CLEAR =
      new HashSet<String>(
          Arrays.asList(
              "minecraft:air",
              "minecraft:snow_layer",
              "minecraft:tallgrass",
              "minecraft:double_plant",
              "minecraft:deadbush",
              "minecraft:red_flower",
              "minecraft:yellow_flower",
              "minecraft:vine",
              "minecraft:brown_mushroom",
              "minecraft:red_mushroom"));

  private StarterShelter() {}

  static boolean place(LegacyRuntime r) {
    r.requireThread();
    if (!r.campaign.starterState.equals("NONE")) return r.campaign.starterState.equals("DELIVERED");
    Object world = r.world.world(0);
    if (world == null) return false;
    Object initial = Reflect.call(world, "getSpawnPoint/func_175694_M");
    int[] site = find(world, Reflect.x(initial), Reflect.z(initial));
    if (site == null) return false;
    int x = site[0], y = site[1], z = site[2];
    String[] ids = {"dead_earth:zone_relay", "minecraft:dirt", "minecraft:sapling"};
    for (String id : ids)
      if (Reflect.registry("ITEMS", id) == null)
        throw new IllegalStateException("Missing starter ingredient " + id);
    // Move the native spawn FIRST, then persist the one-world claim: protectedAt uses this exact
    // center.
    Reflect.call(world, "setSpawnPoint/func_175652_B", Reflect.pos(x, y, z));
    r.campaign.starterState = "CLAIMED";
    r.checkpoint();
    // Support the floor over a bounded natural slope. Existing ground stays below the foundation.
    for (int dx = -3; dx <= 3; dx++)
      for (int dz = -3; dz <= 3; dz++)
        for (int dy = -FOUNDATION; dy < -1; dy++)
          if (CLEAR.contains(Reflect.blockId(world, Reflect.pos(x + dx, y + dy, z + dz))))
            put(world, x + dx, y + dy, z + dz, LegacyDefense.id("wall", 1), 0);
    for (int dx = -3; dx <= 3; dx++)
      for (int dz = -3; dz <= 3; dz++)
        for (int dy = -1; dy <= 3; dy++) {
          boolean shell = dy == -1 || dy == 3 || Math.abs(dx) == 3 || Math.abs(dz) == 3;
          put(
              world,
              x + dx,
              y + dy,
              z + dz,
              shell ? LegacyDefense.id("wall", 1) : "minecraft:air",
              0);
        }
    put(world, x, y, z + 3, LegacyDefense.id("door", 1), 1);
    put(world, x, y + 1, z + 3, LegacyDefense.id("door", 1), 8);
    put(world, x + 2, y + 3, z, LegacyDefense.id("hatch", 1), 3);
    for (int dy = 0; dy <= 2; dy++) put(world, x + 2, y + dy, z, "minecraft:ladder", 4);
    put(world, x, y + 3, z, "minecraft:glowstone", 0);
    Object chestPos = Reflect.pos(x - 2, y, z - 2);
    put(world, x - 2, y, z - 2, "minecraft:chest", 3);
    Object chest = Reflect.call(world, "getTileEntity/func_175625_s", chestPos);
    if (chest == null
        || ((Number) Reflect.call(chest, "getSizeInventory/func_70302_i_")).intValue() < 3)
      throw new IllegalStateException("Starter chest was not created");
    int[] counts = {6, 16, 8};
    for (int i = 0; i < ids.length; i++)
      Reflect.call(
          chest,
          "setInventorySlotContents/func_70299_a",
          i,
          Reflect.make(
              "net.minecraft.item.ItemStack", Reflect.registry("ITEMS", ids[i]), counts[i], 0));
    Reflect.call(chest, "markDirty/func_70296_d");
    if (!Reflect.blockId(world, Reflect.pos(x, y - 1, z)).equals(LegacyDefense.id("wall", 1))
        || !Reflect.blockId(world, Reflect.pos(x, y, z + 3)).equals(LegacyDefense.id("door", 1)))
      throw new IllegalStateException("Starter physical floor/door verification failed");
    r.campaign.starterState = "DELIVERED";
    r.checkpoint();
    return true;
  }

  private static int[] find(Object world, int originX, int originZ) {
    Map<Long, Boolean> footprints = new HashMap<Long, Boolean>();
    for (int ring = 0; ring <= SEARCH_RADIUS; ring += STEP)
      for (int dx = -ring; dx <= ring; dx += STEP)
        for (int dz = -ring; dz <= ring; dz += STEP) {
          if (ring > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
          int x = originX + dx, z = originZ + dz;
          int y = safe(world, x, z, footprints);
          if (y >= 0) return new int[] {x, y, z};
        }
    return null;
  }

  private static int safe(Object world, int x, int z, Map<Long, Boolean> footprints) {
    int min = 256, max = 0;
    for (int dx = -3; dx <= 3; dx++)
      for (int dz = -3; dz <= 3; dz++) {
        Object p = Reflect.pos(x + dx, 64, z + dz);
        if (!Reflect.loaded(world, p) || reserved(world, (x + dx) >> 4, (z + dz) >> 4, footprints))
          return -1;
        Object top = Reflect.call(world, "getTopSolidOrLiquidBlock/func_175672_r", p);
        int h = Reflect.y(top);
        if (h < FOUNDATION + 1 || h > 248 || !natural(world, Reflect.pos(x + dx, h - 1, z + dz)))
          return -1;
        for (int depth = 2; depth <= FOUNDATION; depth++)
          if (!natural(world, Reflect.pos(x + dx, h - depth, z + dz))) return -1;
        min = Math.min(min, h);
        max = Math.max(max, h);
        if (max - min >= FOUNDATION) return -1;
      }
    for (int dx = -3; dx <= 3; dx++)
      for (int dz = -3; dz <= 3; dz++)
        for (int dy = -FOUNDATION; dy <= 3; dy++) {
          Object p = Reflect.pos(x + dx, max + dy, z + dz);
          if (!Reflect.loaded(world, p)
              || Reflect.call(world, "getTileEntity/func_175625_s", p) != null) return -1;
          String id = Reflect.blockId(world, p);
          if (dy >= 0) {
            if (!CLEAR.contains(id)) return -1;
          } else if (!CLEAR.contains(id) && !natural(world, p)) return -1;
        }
    return max;
  }

  private static boolean natural(Object world, Object pos) {
    Object state = Reflect.state(world, pos), block = Reflect.call(state, "getBlock/func_177230_c");
    String id = Reflect.call(block, "getRegistryName").toString();
    if (NATURAL.contains(id)) return true;
    return id.equals("minecraft:stonebrick")
        && ((Number) Reflect.call(block, "getMetaFromState/func_176201_c", state)).intValue() == 2;
  }

  private static boolean reserved(Object world, int cx, int cz, Map<Long, Boolean> cache) {
    long key = (cx & 0xffffffffL) | ((long) cz << 32);
    Boolean old = cache.get(key);
    if (old != null) return old;
    try {
      java.lang.reflect.Method m =
          Reflect.type("dev.deadearth.legacy.worldgen.HbmFootprints")
              .getDeclaredMethod("reserved", Object.class, int.class, int.class);
      m.setAccessible(true);
      boolean value = (Boolean) m.invoke(null, world, cx, cz);
      cache.put(key, value);
      return value;
    } catch (Exception unknown) {
      throw new IllegalStateException("Cannot verify native HBM starter footprint", unknown);
    }
  }

  private static void put(Object world, int x, int y, int z, String id, int meta) {
    Object state =
        Reflect.call(Reflect.registry("BLOCKS", id), "getStateFromMeta/func_176203_a", meta);
    Object p = Reflect.pos(x, y, z);
    Reflect.call(world, "setBlockState/func_180501_a", p, state, 2);
    if (!Reflect.blockId(world, p).equals(id))
      throw new IllegalStateException("Starter placement failed: " + id);
  }
}
