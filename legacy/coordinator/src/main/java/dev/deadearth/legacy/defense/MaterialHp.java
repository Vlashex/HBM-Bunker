package dev.deadearth.legacy.defense;

import com.google.gson.*;
import dev.deadearth.legacy.*;
import dev.deadearth.legacy.data.GameDataFiles;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/** Narrow ordinary-building HP patch. Does not replace native ESM work/targeting or nuclear writers. */
public final class MaterialHp {
  private static final Map<String, Integer> MAXIMUM = new LinkedHashMap<String, Integer>();
  private static final ThreadLocal<String> REMOVING = new ThreadLocal<String>();
  private static LegacyRuntime owner;
  private static MaterialDamageStore store;
  private static int hostileDamage, cooldown, blastDamage, cacheChunks;
  private static boolean installed;
  private static boolean reported;

  private MaterialHp() {}

  public static void install(Path config) {
    try {
      JsonObject j = JsonFiles.read(config.resolve("dead-earth/material-hp.json"), 32768);
      GameDataFiles.onlyFields(j, "schema", "hostileDamage", "cooldownTicks",
          "explosionDamagePerPower", "cacheChunks", "blocks", "bunkerHitPoints");
      GameDataFiles.integer(j, "schema", 1, 1);
      hostileDamage = GameDataFiles.integer(j, "hostileDamage", 1, 1000000);
      cooldown = GameDataFiles.integer(j, "cooldownTicks", 1, 1200);
      blastDamage = GameDataFiles.integer(j, "explosionDamagePerPower", 1, 100000);
      cacheChunks = GameDataFiles.integer(j, "cacheChunks", 1, 256);
      JsonObject bunker = j.getAsJsonObject("bunkerHitPoints");
      GameDataFiles.onlyFields(bunker, "v1", "v2", "v3");
      DefenseDamageStore.configure(GameDataFiles.integer(bunker, "v1", 1, 1000000),
          GameDataFiles.integer(bunker, "v2", 1, 1000000),
          GameDataFiles.integer(bunker, "v3", 1, 1000000));
      JsonObject blocks = j.getAsJsonObject("blocks");
      if (blocks == null || blocks.entrySet().isEmpty() || blocks.entrySet().size() > 1024)
        throw new IllegalArgumentException("Material HP block bounds");
      MAXIMUM.clear();
      for (Map.Entry<String, JsonElement> row : blocks.entrySet()) {
        if (!row.getKey().matches("[a-z0-9_.-]+:[a-z0-9_/.-]+(?:#[0-9]{1,5})?"))
          throw new IllegalArgumentException("Material HP identifier");
        MAXIMUM.put(row.getKey(), GameDataFiles.integer(blocks, row.getKey(), 1, 1000000));
      }
    } catch (Exception invalid) {
      throw new IllegalStateException("Invalid material HP configuration; original preserved", invalid);
    }
    if (!installed) {
      installed = true;
      MinecraftForge.EVENT_BUS.register(new MaterialHp());
    }
  }

  public static boolean configured(String id) {
    if (MAXIMUM.containsKey(id)) return true;
    for (String token : MAXIMUM.keySet()) if (token.startsWith(id + "#")) return true;
    return false;
  }

  private static String token(Object world, Object pos) {
    Object state = Reflect.state(world, pos), block = Reflect.call(state, "getBlock/func_177230_c");
    return Reflect.call(block, "getRegistryName") + "#"
        + Reflect.call(block, "getMetaFromState/func_176201_c", state);
  }

  public static int maximum(Object world, Object pos) {
    if (!Reflect.loaded(world, pos)) return 0;
    Object state = Reflect.state(world, pos), block = Reflect.call(state, "getBlock/func_177230_c");
    if (LegacyDefense.isDefense(block) || Reflect.call(world, "getTileEntity/func_175625_s", pos) != null)
      return 0;
    Integer hp = MAXIMUM.get(token(world, pos));
    if (hp == null) hp = MAXIMUM.get(Reflect.call(block, "getRegistryName").toString());
    return hp == null ? 0 : hp;
  }

  private static MaterialDamageStore ledger(LegacyRuntime runtime) {
    if (owner != runtime) {
      owner = runtime;
      store = new MaterialDamageStore(runtime.worldDirectory.resolve("data/dead_earth_material_hp"), cacheChunks);
    }
    return store;
  }

  public static int remaining(Object world, Object pos) {
    LegacyRuntime runtime = DeadEarthLegacy.runtime;
    if (runtime == null || !runtime.onOwnerThread()) return 0;
    int hp = maximum(world, pos);
    return hp == 0 ? 0 : ledger(runtime).remaining(Reflect.dimension(world), Reflect.x(pos),
        Reflect.y(pos), Reflect.z(pos), token(world, pos), hp);
  }

  private static boolean hostile() {
    if (dev.deadearth.legacy.combat.RelaySiege.hostileWriteActive()) return true;
    return hostileFrames(Thread.currentThread().getStackTrace());
  }

  static boolean hostileFrames(StackTraceElement[] frames) {
    boolean hostile = false;
    for (StackTraceElement frame : frames) {
      String name = frame.getClassName();
      // Pathfinding may populate a new chunk synchronously below a parasite AI frame.
      // Those ore/terrain writes belong to generation, not to the mob's attack.
      if (name.startsWith("net.minecraft.world.gen.")
          || name.equals("net.minecraftforge.fml.common.registry.GameRegistry")
              && frame.getMethodName().equals("generateWorld")) return false;
      if (name.startsWith("com.dhanantry.scapeandrunparasites.")
          || name.startsWith("funwayguy.epicsiegemod.")) hostile = true;
    }
    return hostile;
  }

  private static String key(Object world, Object pos) {
    return Reflect.dimension(world) + ":" + Reflect.x(pos) + ":" + Reflect.y(pos) + ":" + Reflect.z(pos);
  }

  public static boolean allowChange(Object world, Object pos, Object proposed) {
    if (world == null
        || (Boolean) Reflect.get(world, "isRemote/field_72995_K") || !Reflect.loaded(world, pos))
      return true;
    if (key(world, pos).equals(REMOVING.get())) return true;
    Object before = Reflect.state(world, pos);
    if (proposed != null && Reflect.call(before, "getBlock/func_177230_c")
        == Reflect.call(proposed, "getBlock/func_177230_c")) return true;
    if (maximum(world, pos) == 0) return true;
    LegacyRuntime runtime = DeadEarthLegacy.runtime;
    if (runtime == null || !runtime.onOwnerThread()) return !hostile();
    if (hostile()) {
      damage(world, pos, hostileDamage, true);
      return false;
    }
    // Ordinary player mining and native HBM nuclear writers retain their native semantics.
    try {
      ledger(runtime).removed(Reflect.dimension(world), Reflect.x(pos), Reflect.y(pos), Reflect.z(pos));
    } catch (RuntimeException invalid) {
      report(invalid);
      return false;
    }
    return true;
  }

  public static boolean damage(Object world, Object pos, int amount, boolean mob) {
    try {
      return damageChecked(world, pos, amount, mob);
    } catch (RuntimeException invalid) {
      report(invalid);
      return true; // Preserve the block on persistence failure; never crash the server tick.
    }
  }

  private static void report(RuntimeException invalid) {
    if (!reported) {
      reported = true;
      System.err.println("[DEAD EARTH] Material HP unavailable; original data preserved: "
          + invalid.getClass().getSimpleName());
    }
  }

  private static boolean damageChecked(Object world, Object pos, int amount, boolean mob) {
    LegacyRuntime runtime = DeadEarthLegacy.runtime;
    if (runtime == null || !runtime.onOwnerThread() || !Reflect.loaded(world, pos)) return false;
    int maximum = maximum(world, pos);
    if (maximum == 0) return false;
    if (runtime.protectedAt(world, pos)) return true;
    int hp = ledger(runtime).damage(Reflect.dimension(world), Reflect.x(pos), Reflect.y(pos), Reflect.z(pos),
        token(world, pos), maximum, amount,
        ((Number) Reflect.call(world, "getTotalWorldTime/func_82737_E")).longValue(), mob ? cooldown : 0);
    int marker = -1 - ((Reflect.x(pos) * 73428767 ^ Reflect.y(pos) * 912931
        ^ Reflect.z(pos) * 19349663) & 0x3fffffff);
    Reflect.call(world, "sendBlockBreakProgress/func_175715_c", marker,
        pos, Math.min(9, (int) (10L * (maximum - hp) / maximum)));
    if (hp == 0) {
      String previous = REMOVING.get();
      REMOVING.set(key(world, pos));
      try {
        if ((Boolean) Reflect.call(world, "destroyBlock/func_175655_b", pos, false))
          ledger(runtime).removed(Reflect.dimension(world), Reflect.x(pos), Reflect.y(pos), Reflect.z(pos));
      } finally {
        if (previous == null) REMOVING.remove(); else REMOVING.set(previous);
      }
      Reflect.call(world, "sendBlockBreakProgress/func_175715_c", marker, pos, -1);
    }
    return true;
  }

  public static void discardCacheForTest() {
    if (!Boolean.getBoolean("deadearth.testControls")) throw new IllegalStateException("test only");
    owner = null;
    store = null;
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void events(Event event) {
    String name = event.getClass().getName();
    if (name.endsWith("BlockEvent$HarvestDropsEvent")) {
      if (hostile() && configured(Reflect.call(
          Reflect.call(Reflect.call(event, "getState"), "getBlock/func_177230_c"),
          "getRegistryName").toString())) ((List<?>) Reflect.call(event, "getDrops")).clear();
      return;
    }
    if (!name.endsWith("ExplosionEvent$Detonate")) return;
    Object world = Reflect.call(event, "getWorld");
    LegacyRuntime runtime = DeadEarthLegacy.runtime;
    if (runtime == null || !runtime.onOwnerThread()) return;
    int amount = (int) Math.min(1000000, Math.max(1, Math.ceil(blastDamage *
        ((Number) Reflect.get(Reflect.call(event, "getExplosion"), "size/field_77280_f")).doubleValue())));
    for (Iterator<?> it = ((List<?>) Reflect.call(event, "getAffectedBlocks")).iterator(); it.hasNext();) {
      Object pos = it.next();
      if (maximum(world, pos) == 0) continue;
      it.remove();
      damage(world, pos, amount, false);
    }
  }
}
