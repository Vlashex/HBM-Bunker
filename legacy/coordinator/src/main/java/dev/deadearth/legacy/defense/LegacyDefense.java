package dev.deadearth.legacy.defense;

import dev.deadearth.legacy.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.material.Material;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/** Main-thread finite defense boundary for native SRP/ESM writes and Forge explosions. */
public final class LegacyDefense {
  private static final Map<String, Object> BLOCKS = new LinkedHashMap<String, Object>();
  private static boolean installed;
  private static DefenseSettings settings;
  private static LegacyRuntime owner;
  private static DefenseDamageStore journal;
  private static RuntimeException failed;
  private static boolean reported;
  private static final LinkedHashMap<String, Request> PENDING =
      new LinkedHashMap<String, Request>();
  private static long flushTick = Long.MIN_VALUE;
  private static int flushBudget;

  private static final class Request {
    Object world, pos, block;
    String key, id;
    int tier, amount;
    long tick;
    boolean hostile;

    DefenseDamageStore.Hit hit() {
      return new DefenseDamageStore.Hit(key, id, tier, amount, tick, hostile);
    }
  }

  private static final ThreadLocal<Map<String, Request>> BULK_CLEANUP =
      new ThreadLocal<Map<String, Request>>();
  private static final ThreadLocal<Set<String>> BREAK_SCOPE = new ThreadLocal<Set<String>>();

  public static void applyConfiguration(DefenseSettings prepared) {
    settings = prepared;
  }

  private LegacyDefense() {}

  public static void install(Path config) {
    if (installed) return;
    settings = DefenseSettings.load(config.resolve("dead-earth/defense.json"));
    MinecraftForge.EVENT_BUS.register(new LegacyDefense());
    installed = true;
  }

  public static String id(String kind, int tier) {
    return "dead_earth:defense_" + kind + "_v" + tier;
  }

  static Material material(boolean handOperable) {
    return (Material)
        Reflect.get(
            Reflect.type("net.minecraft.block.material.Material"),
            handOperable ? "WOOD/field_151575_d" : "IRON/field_151573_f");
  }

  static void configure(Object block, String kind, int tier) {
    Reflect.call(block, "setRegistryName", "dead_earth", "defense_" + kind + "_v" + tier);
    Reflect.call(
        block, "setUnlocalizedName/func_149663_c", "dead_earth.defense_" + kind + "_v" + tier);
    Reflect.call(block, "setHardness/func_149711_c", 5f);
    Reflect.call(block, "setResistance/func_149752_b", 2f);
    metalSound(block);
    Reflect.call(block, "setHarvestLevel", "pickaxe", 1);
    Reflect.call(
        block,
        "setCreativeTab/func_149647_a",
        Reflect.get(
            Reflect.type("net.minecraft.creativetab.CreativeTabs"),
            "BUILDING_BLOCKS/field_78030_b"));
  }

  private static void metalSound(Object block) {
    Object sound =
        Reflect.get(Reflect.type("net.minecraft.block.SoundType"), "METAL/field_185852_e");
    Class<?> base = Reflect.type("net.minecraft.block.Block");
    for (String name : new String[] {"func_149672_a", "setSoundType"})
      try {
        java.lang.reflect.Method method =
            base.getDeclaredMethod(name, Reflect.type("net.minecraft.block.SoundType"));
        method.setAccessible(true);
        method.invoke(block, sound);
        return;
      } catch (NoSuchMethodException absent) {
      } catch (Exception error) {
        throw new IllegalStateException("Defense metal sound", error);
      }
    throw new IllegalStateException("Defense sound API is unavailable");
  }

  public static void registerBlocks(Object registry) {
    for (int tier = 1; tier <= 3; tier++) {
      add(registry, "wall", tier, new DefenseWall(tier));
      add(registry, "door", tier, new DefenseDoor(tier));
      add(registry, "hatch", tier, new DefenseHatch(tier));
    }
  }

  private static void add(Object registry, String kind, int tier, Object block) {
    BLOCKS.put(id(kind, tier), block);
    Reflect.call(registry, "register", block);
  }

  public static void registerItems(Object registry) {
    for (Map.Entry<String, Object> row : BLOCKS.entrySet()) {
      Object item =
          Reflect.make(
              row.getValue() instanceof DefenseDoor
                  ? "net.minecraft.item.ItemDoor"
                  : "net.minecraft.item.ItemBlock",
              row.getValue());
      Reflect.call(
          item,
          "setRegistryName",
          Reflect.make("net.minecraft.util.ResourceLocation", row.getKey()));
      Reflect.call(item, "setUnlocalizedName/func_77655_b", row.getKey().replace(':', '.'));
      Reflect.call(
          item,
          "setCreativeTab/func_77637_a",
          Reflect.get(
              Reflect.type("net.minecraft.creativetab.CreativeTabs"),
              "BUILDING_BLOCKS/field_78030_b"));
      Reflect.call(registry, "register", item);
    }
  }

  public static void registerModels() {
    for (String id : BLOCKS.keySet())
      Reflect.call(
          Reflect.type("net.minecraftforge.client.model.ModelLoader"),
          "setCustomModelResourceLocation",
          Reflect.registry("ITEMS", id),
          0,
          Reflect.make(
              "net.minecraft.client.renderer.block.model.ModelResourceLocation", id, "inventory"));
  }

  public static boolean isDefense(Object block) {
    return block instanceof DefenseMaterial;
  }

  static Object block(Object state) {
    return Reflect.call(state, "getBlock/func_177230_c");
  }

  static boolean upper(Object state) {
    Object half = Reflect.get(Reflect.type("net.minecraft.block.BlockDoor"), "HALF/field_176523_O");
    return Reflect.call(state, "getValue/func_177229_b", half).toString().equalsIgnoreCase("upper");
  }

  private static Object anchor(Object pos, Object state) {
    return block(state) instanceof DefenseDoor && upper(state)
        ? Reflect.pos(Reflect.x(pos), Reflect.y(pos) - 1, Reflect.z(pos))
        : pos;
  }

  private static String key(Object world, Object pos) {
    return Reflect.dimension(world)
        + ":"
        + Reflect.x(pos)
        + ":"
        + Reflect.y(pos)
        + ":"
        + Reflect.z(pos);
  }

  private static boolean server(Object world) {
    return world != null && !(Boolean) Reflect.get(world, "isRemote/field_72995_K");
  }

  private static DefenseDamageStore store(LegacyRuntime r) {
    if (owner != r) {
      owner = r;
      journal = null;
      failed = null;
      reported = false;
    }
    if (failed != null) throw failed;
    if (journal == null)
      try {
        journal =
            new DefenseDamageStore(
                r.worldDirectory.resolve("data/dead_earth_defense.json"),
                settings.maximumDamagedBlocks);
      } catch (RuntimeException invalid) {
        failed = invalid;
        throw invalid;
      }
    return journal;
  }

  /** Same-block state changes are always free: hinges, powered state and open state retain HP. */
  public static boolean allowChange(Object world, Object pos, Object proposedState) {
    if (!server(world) || !Reflect.loaded(world, pos)) return true;
    Object before = Reflect.state(world, pos), oldBlock = block(before);
    if (!isDefense(oldBlock) || proposedState != null && block(proposedState) == oldBlock)
      return true;
    Object base = anchor(pos, before);
    String k = key(world, base);
    Set<String> scope = BREAK_SCOPE.get();
    if (scope != null && scope.contains(k)) return true;
    LegacyRuntime r = DeadEarthLegacy.runtime;
    if (r == null) return !hostileStack();
    if (!r.onOwnerThread()) return false;
    boolean hostile = hostileStack();
    if (hostile) {
      damage(world, pos, settings.hostileDamage, true);
      return false;
    }
    // Unknown vanilla/mod writes (including HBM nuclear writers) retain their own semantics.
    // Player mining reaches this branch and keeps normal native harvest/drop behavior.
    Map<String, Request> bulk = BULK_CLEANUP.get();
    if (bulk != null) {
      Request removed = new Request();
      removed.world = world;
      removed.pos = base;
      removed.block = oldBlock;
      removed.key = k;
      if (!bulk.containsKey(k)) bulk.put(k, removed);
      return true;
    }
    try {
      PENDING.remove(k);
      store(r).removed(k);
    } catch (RuntimeException invalid) {
      report(invalid);
    }
    return true;
  }

  private static boolean hostileStack() {
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      String c = frame.getClassName();
      if (c.startsWith("com.dhanantry.scapeandrunparasites.")
          || c.startsWith("funwayguy.epicsiegemod.")) return true;
    }
    return false;
  }

  private static Request capture(
      LegacyRuntime r, Object world, Object pos, int amount, boolean hostile) {
    if (!Reflect.loaded(world, pos)) return null;
    Object state = Reflect.state(world, pos), block = block(state);
    if (!isDefense(block)) return null;
    Object base = anchor(pos, state);
    if (r.protectedAt(world, pos) || r.protectedAt(world, base)) return null;
    Request q = new Request();
    q.world = world;
    q.pos = base;
    q.block = block;
    q.key = key(world, base);
    q.id = Reflect.call(block, "getRegistryName").toString();
    q.tier = ((DefenseMaterial) block).tier();
    q.amount = Math.min(100000000, Math.max(1, amount));
    q.tick = ((Number) Reflect.call(world, "getTotalWorldTime/func_82737_E")).longValue();
    q.hostile = hostile;
    return q;
  }

  /**
   * Hostile attempts coalesce until the bounded server END batch; no physical removal precedes its
   * checkpoint.
   */
  public static boolean damage(Object world, Object pos, int amount, boolean hostile) {
    LegacyRuntime r = DeadEarthLegacy.runtime;
    if (r == null
        || !r.onOwnerThread()
        || !server(world)
        || !Reflect.loaded(world, pos)
        || !isDefense(block(Reflect.state(world, pos)))) return false;
    Request request = capture(r, world, pos, amount, hostile);
    if (request == null) return true;
    try {
      DefenseDamageStore data = store(r);
      if (hostile) {
        if (data.hostileDue(request.key, request.id, request.tick, settings.cooldownTicks)
            && !PENDING.containsKey(request.key)
            && PENDING.size() < settings.maximumPendingAttacks) PENDING.put(request.key, request);
      } else applyBatch(r, Collections.singletonList(request));
    } catch (RuntimeException invalid) {
      report(invalid);
    }
    return true;
  }

  /**
   * Also used explicitly by the isolated probe; repeated calls cannot exceed the same-world-tick
   * budget.
   */
  public static void flush(LegacyRuntime r) {
    r.requireThread();
    Object world = r.world.world(0);
    if (world == null) return;
    long tick = ((Number) Reflect.call(world, "getTotalWorldTime/func_82737_E")).longValue();
    if (tick != flushTick) {
      flushTick = tick;
      flushBudget = settings.hostileAnchorsPerTick;
    }
    int used = flushPending(r, flushBudget);
    flushBudget -= used;
  }

  private static int flushPending(LegacyRuntime r, int maximum) {
    List<Request> requests = new ArrayList<Request>();
    int visited = 0;
    Iterator<Request> it = PENDING.values().iterator();
    while (it.hasNext() && visited < maximum) {
      Request q = it.next();
      it.remove();
      visited++;
      if (!Reflect.loaded(q.world, q.pos)
          || block(Reflect.state(q.world, q.pos)) != q.block
          || r.protectedAt(q.world, q.pos)) continue;
      requests.add(q);
    }
    if (!requests.isEmpty())
      try {
        applyBatch(r, requests);
      } catch (RuntimeException invalid) {
        report(invalid);
      }
    return visited;
  }

  private static void applyBatch(LegacyRuntime r, List<Request> requests) {
    DefenseDamageStore data = store(r);
    List<DefenseDamageStore.Hit> hits = new ArrayList<DefenseDamageStore.Hit>();
    for (Request q : requests) hits.add(q.hit());
    Map<String, Integer> hp = data.damageBatch(hits, settings.cooldownTicks);
    Map<String, Request> previous = BULK_CLEANUP.get(),
        cleanup = new LinkedHashMap<String, Request>();
    for (Request q : requests) cleanup.put(q.key, q);
    BULK_CLEANUP.set(cleanup);
    try {
      for (Request q : requests) if (hp.get(q.key) != null && hp.get(q.key) <= 0) breakTerminal(q);
    } finally {
      if (previous == null) BULK_CLEANUP.remove();
      else BULK_CLEANUP.set(previous);
    }
    List<String> removed = new ArrayList<String>();
    for (Request q : cleanup.values())
      if (Reflect.loaded(q.world, q.pos) && block(Reflect.state(q.world, q.pos)) != q.block) {
        removed.add(q.key);
        PENDING.remove(q.key);
      }
    data.removedBatch(removed);
  }

  private static boolean breakTerminal(Request q) {
    Set<String> previous = BREAK_SCOPE.get();
    Set<String> scope = new HashSet<String>();
    scope.add(q.key);
    if (q.block instanceof DefenseDoor)
      scope.add(
          key(q.world, Reflect.pos(Reflect.x(q.pos), Reflect.y(q.pos) + 1, Reflect.z(q.pos))));
    BREAK_SCOPE.set(scope);
    try {
      if (Reflect.loaded(q.world, q.pos) && block(Reflect.state(q.world, q.pos)) == q.block)
        Reflect.call(q.world, "destroyBlock/func_175655_b", q.pos, false);
      if (q.block instanceof DefenseDoor) {
        Object top = Reflect.pos(Reflect.x(q.pos), Reflect.y(q.pos) + 1, Reflect.z(q.pos));
        if (Reflect.loaded(q.world, top) && block(Reflect.state(q.world, top)) == q.block)
          Reflect.call(q.world, "setBlockToAir/func_175698_g", top);
      }
      return block(Reflect.state(q.world, q.pos)) != q.block;
    } finally {
      if (previous == null) BREAK_SCOPE.remove();
      else BREAK_SCOPE.set(previous);
    }
  }

  public static int remaining(Object world, Object pos) {
    LegacyRuntime r = DeadEarthLegacy.runtime;
    if (r == null || !r.onOwnerThread() || !Reflect.loaded(world, pos)) return 0;
    Object state = Reflect.state(world, pos), b = block(state);
    if (!isDefense(b)) return 0;
    return store(r)
        .remaining(
            key(world, anchor(pos, state)),
            Reflect.call(b, "getRegistryName").toString(),
            ((DefenseMaterial) b).tier());
  }

  private static void report(RuntimeException invalid) {
    if (!reported) {
      reported = true;
      failed = invalid;
      System.err.println(
          "[DEAD EARTH] Defense HP unavailable; existing journal preserved: "
              + invalid.getMessage());
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void events(Event event) {
    String name = event.getClass().getName();
    if (name.endsWith("TickEvent$ServerTickEvent")) {
      LegacyRuntime r = DeadEarthLegacy.runtime;
      if (r != null && r.onOwnerThread() && Reflect.get(event, "phase").toString().equals("END"))
        flush(r);
      return;
    }
    if (name.endsWith("BlockEvent$HarvestDropsEvent")) {
      Object state = Reflect.call(event, "getState");
      if (isDefense(block(state)) && hostileStack())
        ((List<?>) Reflect.call(event, "getDrops")).clear();
      return;
    }
    if (!name.endsWith("ExplosionEvent$Detonate")) return;
    Object world = Reflect.call(event, "getWorld");
    LegacyRuntime r = DeadEarthLegacy.runtime;
    if (r == null || !r.onOwnerThread() || !server(world)) return;
    Object explosion = Reflect.call(event, "getExplosion");
    float power = ((Number) Reflect.get(explosion, "size/field_77280_f")).floatValue();
    int amount =
        (int) Math.min(100000000, Math.max(1, Math.ceil(power * settings.explosionDamagePerPower)));
    Set<String> seen = new HashSet<String>();
    @SuppressWarnings("unchecked")
    List<Object> affected = (List<Object>) Reflect.call(event, "getAffectedBlocks");
    List<Request> hits = new ArrayList<Request>();
    for (Iterator<Object> it = affected.iterator(); it.hasNext(); ) {
      Object pos = it.next();
      if (!Reflect.loaded(world, pos)) continue;
      Object state = Reflect.state(world, pos);
      if (!isDefense(block(state))) continue;
      if (seen.add(key(world, anchor(pos, state)))) {
        Request q = capture(r, world, pos, amount, false);
        if (q != null) hits.add(q);
      }
      it.remove();
    }
    if (!hits.isEmpty())
      try {
        applyBatch(r, hits);
      } catch (RuntimeException invalid) {
        report(invalid);
      }
  }

  public static boolean placeStarterShelter(LegacyRuntime runtime) {
    return StarterShelter.place(runtime);
  }

  public static void close() {
    LegacyRuntime r = DeadEarthLegacy.runtime;
    if (r != null && r.onOwnerThread() && settings != null && !PENDING.isEmpty())
      flushPending(r, settings.maximumPendingAttacks);
    PENDING.clear();
    flushTick = Long.MIN_VALUE;
    flushBudget = 0;
    owner = null;
    journal = null;
    failed = null;
    reported = false;
    BREAK_SCOPE.remove();
    BULK_CLEANUP.remove();
  }
}
