package dev.deadearth.legacyprobe.sieving;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.sieving.*;
import java.util.*;
import net.minecraftforge.items.CapabilityItemHandler;

/** Direct native conservation/persistence fixtures; ordinary gameplay is tested separately. */
public final class SievingProbe {
  private static void require(boolean ok, String detail) {
    if (!ok) throw new IllegalStateException(detail);
  }

  public static Object stack(String id, int n) {
    return Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", id), n, 0);
  }

  public static Object empty() {
    return Reflect.get(Reflect.type("net.minecraft.item.ItemStack"), "EMPTY/field_190927_a");
  }

  public static int count(Object s) {
    return (Boolean) Reflect.call(s, "isEmpty/func_190926_b")
        ? 0
        : ((Number) Reflect.call(s, "getCount/func_190916_E")).intValue();
  }

  private static String id(Object s) {
    return String.valueOf(Reflect.call(Reflect.call(s, "getItem/func_77973_b"), "getRegistryName"));
  }

  private static Object handler(Object tile) {
    return Reflect.call(tile, "getCapability", CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
  }

  private static Object place(Object w, Object p) {
    require(
        (Boolean)
            Reflect.call(
                w,
                "setBlockState/func_180501_a",
                p,
                Reflect.call(
                    Reflect.registry("BLOCKS", "dead_earth:compact_sieve_v3"),
                    "getDefaultState/func_176223_P"),
                3),
        "native frame placed");
    Object t = Reflect.call(w, "getTileEntity/func_175625_s", p);
    require(t instanceof SieveTile, "native frame tile");
    return t;
  }

  private static void insert(Object h, int slot, String id) {
    require(
        count(Reflect.call(h, "insertItem", slot, stack(id, 1), false)) == 0,
        "matching item accepted: " + id);
  }

  public static long num(Object tile, String key) {
    return ((Number) Reflect.get(tile, key)).longValue();
  }

  public static void step(Object tile) throws Exception {
    java.lang.reflect.Method m = SieveTile.class.getDeclaredMethod("step");
    m.setAccessible(true);
    m.invoke(tile);
  }

  public static void compute(Object tile, int bound) throws Exception {
    for (int i = 0; num(tile, "remaining") > 0 && i < bound + 2; i++) step(tile);
    require(
        num(tile, "remaining") == 0 && !(Boolean) Reflect.get(tile, "fault"),
        "bounded native computation completed");
  }

  public static Object actor(Object world, String name, int suffix, int x, int y, int z) {
    Object profile =
        Reflect.make("com.mojang.authlib.GameProfile", new UUID(0x195dab410c704d11L, suffix), name);
    Object p =
        Reflect.call(
            Reflect.type("net.minecraftforge.common.util.FakePlayerFactory"),
            "get",
            world,
            profile);
    Reflect.call(p, "setPosition/func_70107_b", x + .5, y + 1.0, z + .5);
    return p;
  }

  public static void fillInventory(Object actor) {
    Object inv = Reflect.get(actor, "inventory/field_71071_by");
    for (int i = 0; i < 36; i++)
      Reflect.call(inv, "setInventorySlotContents/func_70299_a", i, stack("minecraft:barrier", 64));
  }

  /** OFF_HAND leaves a deliberately full main inventory full, including selected slot zero. */
  public static void work(Object tile, Object actor, int strokes) {
    Object hand = Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "OFF_HAND");
    Reflect.call(actor, "setSneaking/func_70095_a", false);
    Reflect.call(actor, "setHeldItem/func_184611_a", hand, empty());
    for (int i = 0; i < strokes; i++) Reflect.call(tile, "interact", actor, hand);
  }

  public static void add(Map<String, Long> map, Object s) {
    int n = count(s);
    if (n == 0) return;
    require(
        n <= 64 && n <= ((Number) Reflect.call(s, "getMaxStackSize/func_77976_d")).intValue(),
        "every physical reward stack respects native capacity");
    String key =
        id(s)
            + ":"
            + Reflect.call(s, "getMetadata/func_77960_j")
            + ":"
            + Reflect.call(s, "getTagCompound/func_77978_p");
    Long previous = map.get(key);
    map.put(key, (previous == null ? 0 : previous) + n);
  }

  public static Map<String, Long> inventory(Object player) {
    Map<String, Long> out = new TreeMap<String, Long>();
    for (Object s :
        (Iterable<?>)
            Reflect.get(
                Reflect.get(player, "inventory/field_71071_by"), "mainInventory/field_70462_a"))
      if (count(s) > 0 && !id(s).equals("minecraft:barrier")) add(out, s);
    return out;
  }

  public static void drain(Object tile, Map<String, Long> out) throws Exception {
    Object h = handler(tile);
    for (int pass = 0; pass < 256; pass++) {
      step(tile);
      boolean moved = false;
      for (int i = 2; i < 29; i++) {
        Object s = Reflect.call(h, "extractItem", i, 64, false);
        moved |= count(s) > 0;
        add(out, s);
      }
      if (!moved) return;
    }
    throw new IllegalStateException("bounded overflow drain did not settle");
  }

  public static long rollSeed(long value, int index) {
    long z = value + 0x9E3779B97F4A7C15L * (index + 1L);
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  private static List<?> nativeRoll(long seed, int index) {
    Object
        registry =
            Reflect.get(
                Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),
                "SIEVE_REGISTRY"),
        state =
            Reflect.call(
                Reflect.registry("BLOCKS", "minecraft:gravel"), "getDefaultState/func_176223_P");
    return (List<?>)
        Reflect.call(registry, "getRewardDrops", new Random(rollSeed(seed, index)), state, 4, 0);
  }

  public static Map<String, Long> expected(long seed, int draws) {
    Map<String, Long> out = new TreeMap<String, Long>();
    for (int i = 0; i < draws; i++) for (Object s : nativeRoll(seed, i)) add(out, s);
    return out;
  }

  private static Object nbt(Object tile) {
    return Reflect.call(
        tile, "writeToNBT/func_189515_b", Reflect.make("net.minecraft.nbt.NBTTagCompound"));
  }

  private static Object replace(Object world, Object p, Object old, Object saved) {
    Object tile = Reflect.make(old.getClass().getName());
    Reflect.call(tile, "setWorld/func_145834_a", world);
    Reflect.call(tile, "setPos/func_174878_a", p);
    Reflect.call(tile, "readFromNBT/func_145839_a", saved);
    Reflect.call(world, "setTileEntity/func_175690_a", p, tile);
    require(!(Boolean) Reflect.get(tile, "fault"), "valid persisted receipt remains readable");
    return tile;
  }

  private static void hidden(Object tile) {
    Object h = handler(tile);
    for (int i = 2; i < 29; i++)
      require(
          count(Reflect.call(h, "getStackInSlot", i)) == 0
              && count(Reflect.call(h, "extractItem", i, 64, true)) == 0,
          "loot remains hidden until fourth stroke");
  }

  public static Map<String, Object> run(
      LegacyRuntime runtime, Object world, Object player, int x, int y, int z) throws Exception {
    runtime.requireThread();
    require(
        Boolean.getBoolean("deadearth.testControls")
            && java.nio.file.Files.isRegularFile(
                java.nio.file.Paths.get(".dead-earth-isolated-native-test")),
        "isolated native fixture only");
    Object p = Reflect.pos(x, y, z);
    require(
        Reflect.loaded(world, p) && !runtime.protectedAt(world, p), "fixture loaded outside grace");
    Object tile = place(world, p),
        h = handler(tile),
        worker = actor(world, "CompactManual", 21, x, y, z);
    for (boolean simulate : new boolean[] {true, false})
      require(
          count(
                  Reflect.call(
                      h, "insertItem", 0, stack("dead_earth:compressed_gravel_2", 64), simulate))
              == 64,
          "no input before matching mesh, including simulation");
    require(
        ((Number) Reflect.call(h, "getSlotLimit", 0)).intValue() == 1,
        "input capacity exactly one");
    require(
        count(
                Reflect.call(
                    h, "insertItem", 1, stack("dead_earth:compact_mesh_diamond_v4", 1), false))
            == 1,
        "wrong tier mesh rejected");
    insert(h, 1, "dead_earth:compact_mesh_diamond_v3");
    require(
        count(Reflect.call(h, "insertItem", 0, stack("dead_earth:compressed_gravel_2", 64), true))
                == 63
            && count(Reflect.call(h, "getStackInSlot", 0)) == 0,
        "simulation accepts only one without mutation");
    require(
        count(Reflect.call(h, "insertItem", 0, stack("dead_earth:compressed_gravel_2", 64), false))
                == 63
            && count(Reflect.call(h, "getStackInSlot", 0)) == 1,
        "real stack64 leaves63");
    step(tile);
    require(
        num(tile, "draws") == 0 && num(tile, "manualStrokes") == 0,
        "loading alone never starts native draws");
    work(tile, worker, 1);
    long firstSeed = num(tile, "seed");
    compute(tile, 81);
    require(
        num(tile, "draws") == 81 && num(tile, "manualStrokes") == 1 && num(tile, "completed") == 0,
        "calculation can finish before remaining work strokes");
    hidden(tile);
    require(inventory(worker).isEmpty(), "no first-stroke payout");
    require(
        count(Reflect.call(h, "extractItem", 1, 1, false)) == 0,
        "active batch locks original mesh");
    work(tile, worker, 2);
    hidden(tile);
    require(
        inventory(worker).isEmpty() && num(tile, "manualStrokes") == 3,
        "no payout on strokes two or three");
    work(tile, worker, 1);
    Map<String, Long> actual = inventory(worker);
    drain(tile, actual);
    require(
        actual.equals(expected(firstSeed, 81))
            && num(tile, "draws") == 81
            && num(tile, "completed") == 1,
        "fourth stroke pays exactly81 native rolls together");
    Object returned = Reflect.call(h, "extractItem", 1, 1, false);
    require(
        count(returned) == 1 && id(returned).equals("dead_earth:compact_mesh_diamond_v3"),
        "finished mesh remains reusable");
    Object nativeRegistry =
        Reflect.get(
            Reflect.type("exnihilocreatio.registries.manager.ExNihiloRegistryManager"),
            "SIEVE_REGISTRY");
    boolean hbm = false;
    for (Object drop :
        (List<?>) Reflect.call(nativeRegistry, "getDrops", stack("minecraft:gravel", 1)))
      if (id(Reflect.call(Reflect.call(drop, "getDrop"), "getItemStack")).startsWith("hbm:"))
        hbm = true;
    require(hbm, "actual native table includes HBM");
    insert(h, 1, "dead_earth:compact_mesh_diamond_v3");
    insert(h, 0, "dead_earth:compressed_gravel_2");
    Object full = actor(world, "CompactOverflow", 22, x, y, z);
    fillInventory(full);
    work(tile, full, 1);
    long secondSeed = num(tile, "seed");
    compute(tile, 81);
    work(tile, full, 2);
    hidden(tile);
    require(
        !((List<?>) Reflect.get(tile, "bank")).isEmpty() && num(tile, "manualStrokes") == 3,
        "real native loot is retained in private bank");
    tile = replace(world, p, tile, nbt(tile));
    h = handler(tile);
    step(tile);
    require(
        num(tile, "draws") == 162 && num(tile, "manualStrokes") == 3,
        "schema2 checkpoint preserves bank and cursor without reroll");
    hidden(tile);
    Set<Object> before = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    before.addAll((List<?>) Reflect.get(world, "loadedEntityList/field_72996_f"));
    Reflect.call(world, "destroyBlock/func_175655_b", p, true);
    Object packed = null;
    int frames = 0;
    for (Object e : (List<?>) Reflect.get(world, "loadedEntityList/field_72996_f"))
      if (!before.contains(e) && Reflect.is(e, "net.minecraft.entity.item.EntityItem")) {
        Object s = Reflect.call(e, "getItem/func_92059_d");
        if (count(s) > 0 && id(s).equals("dead_earth:compact_sieve_v3")) {
          frames += count(s);
          packed = s;
        }
      }
    require(frames == 1 && packed != null, "break emits exactly one packed frame");
    require(
        count(Reflect.call(h, "extractItem", 2, 64, false)) == 0
            && count(
                    Reflect.call(
                        h, "insertItem", 0, stack("dead_earth:compressed_gravel_2", 1), false))
                == 1,
        "cached removed handler cannot duplicate or accept contents");
    tile = place(world, p);
    require(
        (Boolean)
            Reflect.call(
                Reflect.type("net.minecraft.item.ItemBlock"),
                "setTileEntityNBT/func_179224_a",
                world,
                player,
                p,
                packed),
        "native ItemBlock applies packed receipt");
    h = handler(tile);
    require(
        num(tile, "manualStrokes") == 3 && num(tile, "draws") == 162,
        "packed third-stroke receipt restored");
    work(tile, full, 1);
    require(inventory(full).isEmpty(), "full player inventory never deletes overflow");
    require(
        count(Reflect.call(h, "insertItem", 0, stack("dead_earth:compressed_gravel_2", 64), true))
            == 64,
        "unclaimed output blocks next batch");
    Map<String, Long> overflow = new TreeMap<String, Long>();
    drain(tile, overflow);
    require(
        overflow.equals(expected(secondSeed, 81)),
        "full-inventory overflow retains every native reward in legal stacks");
    legacy(world, p, tile);
    Object plainPos = Reflect.pos(x + 2, y, z);
    before.clear();
    before.addAll((List<?>) Reflect.get(world, "loadedEntityList/field_72996_f"));
    place(world, plainPos);
    Reflect.call(world, "destroyBlock/func_175655_b", plainPos, true);
    int plain = 0;
    for (Object e : (List<?>) Reflect.get(world, "loadedEntityList/field_72996_f"))
      if (!before.contains(e) && Reflect.is(e, "net.minecraft.entity.item.EntityItem")) {
        Object s = Reflect.call(e, "getItem/func_92059_d");
        if (count(s) > 0
            && id(s).equals("dead_earth:compact_sieve_v3")
            && Reflect.call(s, "getTagCompound/func_77978_p") == null) plain += count(s);
      }
    require(plain == 1, "empty frame remains an ordinary upgradeable frame");
    return JsonFiles.map(
        "nativeRawDraws",
        81,
        "nativeRewardMatch",
        true,
        "hbmProvider",
        true,
        "noInputWithoutMesh",
        true,
        "oneOf64Accepted",
        true,
        "noDrawBeforeWork",
        true,
        "fourStrokeWholeBatch",
        true,
        "hiddenBankNbtRoundTrip",
        true,
        "packedBreakAndPlace",
        true,
        "cachedHandlerRejectedAfterBreak",
        true,
        "fullInventoryOverflowConserved",
        true,
        "legacyPendingMigratedOnce",
        true,
        "legacySurplusRefunded",
        true,
        "emptyFrameUpgradeable",
        true);
  }

  private static void legacy(Object world, Object p, Object old) throws Exception {
    long seed = 1;
    List<?> pending = nativeRoll(seed, 0);
    while (pending.isEmpty() && seed < 256) pending = nativeRoll(++seed, 0);
    require(!pending.isEmpty(), "native pending migration fixture has real loot");
    Object saved = Reflect.make("net.minecraft.nbt.NBTTagCompound");
    for (String key :
        new String[] {"DeadEarthSieveSchema", "Level", "Remaining", "Grade", "Cursor"})
      Reflect.call(
          saved,
          "setInteger/func_74768_a",
          key,
          key.equals("DeadEarthSieveSchema")
              ? 1
              : key.equals("Level")
                  ? 3
                  : key.equals("Remaining") ? 81 : key.equals("Grade") ? 4 : 0);
    Reflect.call(saved, "setLong/func_74772_a", "Seed", seed);
    Reflect.call(saved, "setLong/func_74772_a", "NativeDraws", 82L);
    Reflect.call(saved, "setLong/func_74772_a", "CompletedBatches", 1L);
    Reflect.call(saved, "setString/func_74778_a", "Raw", "minecraft:gravel");
    Reflect.call(saved, "setBoolean/func_74757_a", "HasPending", true);
    Object oldOutput = Reflect.call(pending.get(0), "copy/func_77946_l");
    Reflect.call(oldOutput, "setCount/func_190920_e", 1);
    Object items = Reflect.make("net.minecraft.nbt.NBTTagList");
    for (int i = 0; i < 29; i++) {
      Object s =
          i == 0
              ? stack("dead_earth:compressed_gravel_2", 64)
              : i == 1
                  ? stack("dead_earth:compact_mesh_diamond_v3", 1)
                  : i == 2 ? oldOutput : empty();
      Reflect.call(
          items,
          "appendTag/func_74742_a",
          Reflect.call(
              s, "writeToNBT/func_77955_b", Reflect.make("net.minecraft.nbt.NBTTagCompound")));
    }
    Reflect.call(saved, "setTag/func_74782_a", "SieveItems", items);
    Object pendingTag = Reflect.make("net.minecraft.nbt.NBTTagList");
    for (Object s : pending)
      Reflect.call(
          pendingTag,
          "appendTag/func_74742_a",
          Reflect.call(
              s, "writeToNBT/func_77955_b", Reflect.make("net.minecraft.nbt.NBTTagCompound")));
    Reflect.call(saved, "setTag/func_74782_a", "Pending", pendingTag);
    Object tile = replace(world, p, old, saved);
    require(
        num(tile, "manualStrokes") == 4
            && num(tile, "draws") == 82
            && count(Reflect.call(handler(tile), "getStackInSlot", 0)) == 1,
        "schema1 active batch authorized and legacy64 normalized to one");
    tile = replace(world, p, tile, nbt(tile));
    compute(tile, 81);
    step(tile);
    Map<String, Long> actual = new TreeMap<String, Long>();
    drain(tile, actual);
    Map<String, Long> expected = expected(seed, 81);
    add(expected, oldOutput);
    add(expected, stack("dead_earth:compressed_gravel_2", 63));
    require(
        actual.equals(expected)
            && num(tile, "draws") == 162
            && count(Reflect.call(handler(tile), "getStackInSlot", 0)) == 1,
        "pending is not rerolled and63 excess inputs refunded exactly once");
    // An old queued stack with no mesh is returned completely, never accepted as a latent input.
    Reflect.call(saved, "setInteger/func_74768_a", "Remaining", 0);
    Reflect.call(saved, "setInteger/func_74768_a", "Grade", 0);
    Reflect.call(saved, "setLong/func_74772_a", "NativeDraws", 0L);
    Reflect.call(saved, "setLong/func_74772_a", "CompletedBatches", 0L);
    Reflect.call(saved, "setString/func_74778_a", "Raw", "");
    Reflect.call(saved, "setBoolean/func_74757_a", "HasPending", false);
    Object noMeshItems = Reflect.make("net.minecraft.nbt.NBTTagList");
    for (int i = 0; i < 29; i++)
      Reflect.call(
          noMeshItems,
          "appendTag/func_74742_a",
          Reflect.call(
              i == 0 ? stack("dead_earth:compressed_gravel_2", 64) : empty(),
              "writeToNBT/func_77955_b",
              Reflect.make("net.minecraft.nbt.NBTTagCompound")));
    Reflect.call(saved, "setTag/func_74782_a", "SieveItems", noMeshItems);
    tile = replace(world, p, tile, saved);
    Map<String, Long> refund = new TreeMap<String, Long>();
    drain(tile, refund);
    Map<String, Long> all64 = new TreeMap<String, Long>();
    add(all64, stack("dead_earth:compressed_gravel_2", 64));
    require(
        refund.equals(all64)
            && count(Reflect.call(handler(tile), "getStackInSlot", 0)) == 0
            && num(tile, "draws") == 0,
        "old no-mesh64 entirely refunded without free processing");
  }
}
