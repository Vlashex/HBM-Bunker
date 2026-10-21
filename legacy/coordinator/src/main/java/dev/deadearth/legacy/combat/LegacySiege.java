package dev.deadearth.legacy.combat;

import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.Reflect;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Owns the native ESM admission boundary; never replaces SRP's custom tasks itself. */
public final class LegacySiege {
  private static Set<String> configured = Collections.emptySet();
  private static SightSettings sight;
  private static Object sightServer;
  private static int sightTick = Integer.MIN_VALUE, rayCount;
  private static final java.util.WeakHashMap<Object, java.util.IdentityHashMap<Object, Boolean>>
      SIGHT_CACHE = new java.util.WeakHashMap<Object, java.util.IdentityHashMap<Object, Boolean>>();

  private LegacySiege() {}

  @SuppressWarnings("unchecked")
  public static void configure(Path directory) {
    Set<String> profiles;
    try {
      profiles =
          SiegePolicy.configuredEntities(
              JsonFiles.read(directory.resolve("esm/entity_ai.json"), 262144));
      sight =
          SightSettings.parse(JsonFiles.read(directory.resolve("dead-earth/siege.json"), 16384));
    } catch (IOException invalid) {
      throw new IllegalStateException("Cannot read native Epic Siege entity profiles", invalid);
    }
    Object registry =
        Reflect.get(
            Reflect.type("net.minecraftforge.fml.common.registry.ForgeRegistries"), "ENTITIES");
    List<Object> exemptions =
        (List<Object>)
            Reflect.get(Reflect.type("funwayguy.epicsiegemod.config.EsmConfigGlobal"), "AIExempt");
    Set<String> registered = new LinkedHashSet<String>();
    for (Object entry : (Collection<?>) Reflect.call(registry, "getValuesCollection")) {
      Object id = Reflect.call(entry, "getRegistryName");
      registered.add(id.toString());
      if (!profiles.contains(id.toString()) && !exemptions.contains(id)) exemptions.add(id);
    }
    if (!registered.containsAll(profiles)) {
      Set<String> missing = new LinkedHashSet<String>(profiles);
      missing.removeAll(registered);
      throw new IllegalStateException(
          "Epic Siege profiles reference unregistered entities: " + missing);
    }
    configured = profiles;
    LegacyPerception.configure(sight);
  }

  /** Prepared snapshot only: listener registration remains a startup responsibility. */
  public static SightSettings configuration() {
    return sight;
  }

  public static void applyConfiguration(SightSettings prepared, SightSweep<Object> worklist) {
    sight = prepared;
    SIGHT_CACHE.clear();
    LegacyPerception.applyConfiguration(prepared, worklist);
  }

  /** Rechecked by the native digging task, including cached targets after replacement. */
  public static boolean allowDig(Object entity, Object pos) {
    Object world = Reflect.world(entity);
    if ((Boolean) Reflect.get(world, "isRemote/field_72995_K") || !Reflect.loaded(world, pos))
      return false;
    dev.deadearth.legacy.LegacyRuntime runtime = dev.deadearth.legacy.DeadEarthLegacy.runtime;
    if (runtime == null || !runtime.onOwnerThread() || runtime.protectedAt(world, pos))
      return false;
    if (!NativeSiegePermissions.mobGriefing(world, entity)) return false;
    String block = Reflect.blockId(world, pos);
    if (block.startsWith("hbm:") || Reflect.call(world, "getTileEntity/func_175625_s", pos) != null)
      return false;
    Class<?> props = Reflect.type("funwayguy.epicsiegemod.config.props.CfgProps");
    if (!(Boolean) Reflect.call(Reflect.get(props, "DIGGING"), "get", entity)) return false;
    boolean invert = (Boolean) Reflect.call(Reflect.get(props, "DIG_BL_INV"), "get", entity);
    Collection<?> blocks =
        (Collection<?>) Reflect.call(Reflect.get(props, "DIG_BL"), "get", entity);
    return blocks.contains(block) == invert;
  }

  /**
   * Called only at native EntitySensesProxy's ordinary LOS fallback, so ESM keeps its tick cache.
   */
  public static boolean visible(Object entity, Object target) {
    if ((Boolean) Reflect.call(entity, "canEntityBeSeen/func_70685_l", target)) return true;
    if (sight == null
        || !sight.mode.equals("glass")
        || !configured.contains(Reflect.entityId(entity))) return false;
    dev.deadearth.legacy.LegacyRuntime runtime = dev.deadearth.legacy.DeadEarthLegacy.runtime;
    if (runtime == null || !runtime.onOwnerThread()) return false;
    Class<?> props = Reflect.type("funwayguy.epicsiegemod.config.props.CfgProps");
    if (!(Boolean) Reflect.call(Reflect.get(props, "DIGGING"), "get", entity)) return false;
    final Object world = Reflect.world(entity);
    if (world != Reflect.world(target) || (Boolean) Reflect.get(world, "isRemote/field_72995_K"))
      return false;
    Object server = Reflect.call(world, "getMinecraftServer/func_73046_m");
    int tick = ((Number) Reflect.call(server, "getTickCounter/func_71259_af")).intValue();
    if (sightServer != server || sightTick != tick) {
      sightServer = server;
      sightTick = tick;
      rayCount = 0;
      SIGHT_CACHE.clear();
    }
    java.util.IdentityHashMap<Object, Boolean> cache = SIGHT_CACHE.get(entity);
    if (cache != null && cache.containsKey(target)) return cache.get(target);
    if (rayCount >= sight.raysPerTick || cache != null && cache.size() >= sight.targetsPerEntity)
      return false;
    if (cache == null) {
      cache = new java.util.IdentityHashMap<Object, Boolean>();
      SIGHT_CACHE.put(entity, cache);
    }
    final double[] from = eye(entity), to = eye(target);
    final Object start = Reflect.make("net.minecraft.util.math.Vec3d", from[0], from[1], from[2]);
    final Object end = Reflect.make("net.minecraft.util.math.Vec3d", to[0], to[1], to[2]);
    rayCount++;
    boolean result =
        TransparentSight.visible(
            from,
            to,
            sight.radius,
            sight.maximumCells,
            sight.maximumTransparent,
            new TransparentSight.WorldRay() {
              public int cell(int x, int y, int z) {
                Object pos = Reflect.pos(x, y, z);
                if (y < 0 || y > 255 || !Reflect.loaded(world, pos))
                  return TransparentSight.UNKNOWN;
                Object state = Reflect.state(world, pos);
                String id = Reflect.blockId(world, pos);
                if (sight.transparent.contains(id)
                    && Reflect.call(world, "getTileEntity/func_175625_s", pos) == null)
                  return TransparentSight.TRANSPARENT;
                Object block = Reflect.call(state, "getBlock/func_177230_c");
                if (!(Boolean) Reflect.call(block, "canCollideCheck/func_176209_a", state, false))
                  return TransparentSight.CLEAR;
                return Reflect.call(
                            state, "collisionRayTrace/func_185910_a", world, pos, start, end)
                        == null
                    ? TransparentSight.CLEAR
                    : TransparentSight.OCCLUDED;
              }
            });
    cache.put(target, result);
    return result;
  }

  private static double[] eye(Object entity) {
    return new double[] {
      ((Number) Reflect.get(entity, "posX/field_70165_t")).doubleValue(),
      ((Number) Reflect.get(entity, "posY/field_70163_u")).doubleValue()
          + ((Number) Reflect.call(entity, "getEyeHeight/func_70047_e")).doubleValue(),
      ((Number) Reflect.get(entity, "posZ/field_70161_v")).doubleValue()
    };
  }

  public static void close() {
    SIGHT_CACHE.clear();
    sightServer = null;
    sightTick = Integer.MIN_VALUE;
    rayCount = 0;
  }

  public static Set<String> configuredEntities() {
    return configured;
  }
}
