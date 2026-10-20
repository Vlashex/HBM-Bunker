package dev.deadearth.legacy;

import static dev.deadearth.legacy.JsonFiles.map;

import com.google.gson.*;
import dev.deadearth.legacy.kernel.*;
import java.util.*;

/** Loaded-only, bounded world adapter. The model never receives positions or spawn counts. */
public final class GameWorld implements CinematicEngine.World {
  final LegacyRuntime runtime;
  private final dev.deadearth.legacy.data.PhaseRoster phases;
  private final Map<String, Set<String>> signals = new HashMap<String, Set<String>>();
  private final ZoneScanner scanner;

  public GameWorld(LegacyRuntime r, dev.deadearth.legacy.data.PhaseRoster entities) {
    runtime = r;
    scanner = new ZoneScanner(r);
    phases = entities;
    for (String id : entities.entries().keySet())
      if (Reflect.registry("ENTITIES", id) == null)
        throw new IllegalArgumentException("Unknown entity roster entry " + id);
  }

  public void validate(CinematicCatalog c) {
    for (CinematicCatalog.Wave w : c.waves().values())
      for (String id : w.entities()) {
        if (!phases.contains(id) || Reflect.registry("ENTITIES", id) == null)
          throw new IllegalArgumentException("Unknown wave entity " + id);
      }
    for (String trace : c.traces().keySet())
      if (Reflect.registry("BLOCKS", "dead_earth:trace_" + trace) == null)
        throw new IllegalArgumentException("Missing trace asset " + trace);
    for (CinematicCatalog.Sound s : c.sounds().values())
      if (Reflect.registry("SOUND_EVENTS", s.sound()) == null)
        throw new IllegalArgumentException("Unknown sound " + s.sound());
  }

  public Object world(int dim) {
    return Reflect.call(runtime.server, "getWorld/func_71218_a", dim);
  }

  public List<Object> players() {
    List<Object> out = new ArrayList<Object>();
    for (Object w : worlds())
      for (Object p : (List<?>) Reflect.get(w, "playerEntities/field_73010_i"))
        if (survival(p)) out.add(p);
    return out;
  }

  public Object[] worlds() {
    return (Object[]) Reflect.get(runtime.server, "worlds/field_71305_c");
  }

  public boolean survival(Object p) {
    if (!(Boolean) Reflect.call(p, "isEntityAlive/func_70089_S")) return false;
    if ((Boolean) Reflect.call(p, "isSpectator/func_175149_v")) return false;
    Object caps = Reflect.get(p, "capabilities/field_71075_bZ");
    return !(Boolean) Reflect.get(caps, "isCreativeMode/field_75098_d");
  }

  public int players(Campaign.Zone z) {
    int count = 0;
    for (Object p : players())
      if (Reflect.dimension(Reflect.world(p)) == z.dimension
          && distance(Reflect.position(p), z) < 9216) count++;
    return count;
  }

  public static double distance(Object p, Campaign.Zone z) {
    double x = Reflect.x(p) - z.x, y = Reflect.y(p) - z.y, d = Reflect.z(p) - z.z;
    return x * x + y * y + d * d;
  }

  public Campaign.Zone zone(String id) {
    for (Campaign.Zone z : runtime.campaign.zones) if (z.id.equals(id)) return z;
    return null;
  }

  public boolean ready(CinematicEngine.Operation op) {
    Campaign.Zone z = zone(op.zoneId());
    if (z == null || players(z) == 0) return false;
    Object w = world(z.dimension);
    return w != null
        && Reflect.loaded(w, Reflect.pos(z.x, z.y, z.z))
        && Reflect.blockId(w, Reflect.pos(z.x, z.y, z.z)).equals("dead_earth:zone_relay");
  }

  public Set<String> signals(CinematicEngine.Operation op) {
    Campaign.Zone z = zone(op.zoneId());
    Set<String> out = new HashSet<String>();
    Set<String> latched = signals.remove(op.id().toString());
    if (latched != null) out.addAll(latched);
    if (z == null) return out;
    boolean near = false;
    for (Object p : players())
      if (Reflect.dimension(Reflect.world(p)) == z.dimension
          && distance(Reflect.position(p), z) < 1024) near = true;
    out.add(near ? "near" : "far");
    if (living(op.id().toString()) == 0 && op.spent() > 0) out.add("clear");
    return out;
  }

  public void signal(Object player, String trigger) {
    Object pos = Reflect.position(player);
    int dim = Reflect.dimension(Reflect.world(player));
    for (CinematicEngine.Operation op : runtime.engine.operations()) {
      Campaign.Zone z = zone(op.zoneId());
      if (z != null && z.dimension == dim && distance(pos, z) < 4096) {
        signals.computeIfAbsent(op.id().toString(), k -> new HashSet<String>()).add(trigger);
        runtime.metric(op.id().toString(), "traceInteractions", 1);
      }
    }
  }

  public int livingOwned() {
    return runtime.campaign.owned.size();
  }

  private int living(String operation) {
    int n = 0;
    for (Map.Entry<String, String> e : runtime.campaign.ownership.entrySet())
      if (e.getValue().equals(operation)) {
        Object entity = ownedEntity(e.getKey());
        if (entity == null || (Boolean) Reflect.call(entity, "isEntityAlive/func_70089_S")) n++;
      }
    return n;
  }

  private Object ownedEntity(String uuid) {
    for (Object w : worlds()) {
      Object e = Reflect.call(w, "getEntityFromUuid/func_175733_a", UUID.fromString(uuid));
      if (e != null) return e;
    }
    return null;
  }

  public void checkpoint() {
    runtime.checkpoint();
  }

  public void emit(
      CinematicEngine.Operation op,
      CinematicCatalog.Action action,
      int count,
      CinematicCatalog frozen) {
    Campaign.Zone z = zone(op.zoneId());
    if (z == null) throw new IllegalStateException("Missing zone");
    Object w = world(z.dimension);
    Object p = point(z, action.role(), action.type().equals("wave"));
    if (p == null) throw new IllegalStateException("No safe loaded point");
    String type = action.type();
    if (type.equals("sound")) {
      CinematicCatalog.Sound sound = frozen.sounds().get(action.key());
      Object category = Reflect.get(Reflect.type("net.minecraft.util.SoundCategory"), "HOSTILE");
      Reflect.call(
          w,
          "playSound/func_184148_a",
          null,
          (double) Reflect.x(p),
          (double) Reflect.y(p),
          (double) Reflect.z(p),
          Reflect.registry("SOUND_EVENTS", sound.sound()),
          category,
          (float) sound.volume(),
          (float) sound.pitch());
      runtime.metric(op.id().toString(), "signalEmissions", 1);
    } else if (type.equals("wave")) {
      List<String> pool = new ArrayList<String>();
      for (String id : frozen.waves().get(action.key()).entities())
        if (phases.waveAllowed(id, runtime.campaign.phase)) pool.add(id);
      if (pool.isEmpty()) return;
      for (int i = 0; i < count; i++) {
        Object at = point(z, action.role(), true);
        if (at == null) break;
        String id = pool.get(Math.floorMod(op.id().hashCode() + i, pool.size()));
        Object e =
            Reflect.call(
                Reflect.type("net.minecraft.entity.EntityList"),
                "createEntityByIDFromName/func_188429_b",
                Reflect.make("net.minecraft.util.ResourceLocation", id),
                w);
        Reflect.call(
            e,
            "setLocationAndAngles/func_70012_b",
            Reflect.x(at) + .5,
            (double) Reflect.y(at),
            Reflect.z(at) + .5,
            0f,
            0f);
        Reflect.call(
            Reflect.nbt(e), "setString/func_74778_a", "dead_earth_operation", op.id().toString());
        Reflect.call(
            Reflect.nbt(e), "setLong/func_74772_a", "dead_earth_deadline", op.expires() + 1200);
        if ((Boolean) Reflect.call(w, "spawnEntity/func_72838_d", e)) {
          String uuid = Reflect.call(e, "getUniqueID/func_110124_au").toString();
          runtime.campaign.owned.add(uuid);
          runtime.campaign.ownership.put(uuid, op.id().toString());
          runtime.scale(e);
          target(e, z, action.role());
        }
      }
    } else if (type.equals("trace")) {
      if (runtime.campaign.traces.size() >= 48) return;
      String block = "dead_earth:trace_" + action.key();
      runtime.campaign.traces.add(
          map(
              "dimension",
              z.dimension,
              "x",
              Reflect.x(p),
              "y",
              Reflect.y(p),
              "z",
              Reflect.z(p),
              "block",
              block,
              "expires",
              op.expires(),
              "operation",
              op.id().toString()));
      runtime.checkpoint();
      Reflect.call(
          w,
          "setBlockState/func_180501_a",
          p,
          Reflect.call(Reflect.registry("BLOCKS", block), "getDefaultState/func_176223_P"),
          3);
      runtime.metric(op.id().toString(), "tracePlacements", 1);
    } else if (type.equals("particles")) {
      Object particle =
          Reflect.get(
              Reflect.type("net.minecraft.util.EnumParticleTypes"),
              action.key().equals("frost") ? "SNOW_SHOVEL" : "SMOKE_NORMAL");
      particles(w, particle, Reflect.x(p), Reflect.y(p) + 1, Reflect.z(p), 12);
    } else if (type.equals("move")) {
      for (Map.Entry<String, String> entry : runtime.campaign.ownership.entrySet())
        if (entry.getValue().equals(op.id().toString())) {
          Object e = ownedEntity(entry.getKey());
          if (e != null) target(e, z, action.key());
        }
    }
    runtime.timeline(op.id().toString(), type, action.role(), action.key(), count);
  }

  private void target(Object entity, Campaign.Zone z, String role) {
    if (!Reflect.is(entity, "net.minecraft.entity.EntityLiving")) return;
    Object at = point(z, role, false);
    if (at != null)
      Reflect.call(
          Reflect.call(entity, "getNavigator/func_70661_as"),
          "tryMoveToXYZ/func_75492_a",
          Reflect.x(at) + .5,
          (double) Reflect.y(at),
          Reflect.z(at) + .5,
          1d);
  }

  private static void particles(Object w, Object type, double x, double y, double z, int count) {
    Reflect.call(
        w, "spawnParticle/func_175739_a", type, x, y, z, count, .3, .15, .3, .01, new int[0]);
  }

  public Object point(Campaign.Zone z, String role, boolean spawn) {
    Object w = world(z.dimension);
    if (w == null) return null;
    int offset = spawn ? 24 : role.equals("distraction") ? 20 : 12;
    int dx = role.equals("flank") ? offset : role.equals("rear") ? -offset : 0,
        dz = role.equals("flank") || role.equals("rear") ? 0 : offset;
    for (int attempt = 0; attempt < 12; attempt++) {
      int x = z.x + dx + (attempt % 3 - 1) * 3, zz = z.z + dz + (attempt / 3 - 1) * 3;
      Object seed = Reflect.pos(x, z.y, zz);
      if (!Reflect.loaded(w, seed)) continue;
      for (int dy = 12; dy >= -12; dy--) {
        int y = Math.max(1, Math.min(253, z.y + dy));
        Object feet = Reflect.pos(x, y, zz),
            head = Reflect.pos(x, y + 1, zz),
            ground = Reflect.pos(x, y - 1, zz);
        if (!(Boolean) Reflect.call(w, "isAirBlock/func_175623_d", feet)
            || !(Boolean) Reflect.call(w, "isAirBlock/func_175623_d", head)) continue;
        Object state = Reflect.state(w, ground);
        if (!(Boolean) Reflect.call(state, "isFullCube/func_185913_b")) continue;
        if (spawn) {
          boolean visible = false;
          for (Object p : players())
            if (Reflect.dimension(Reflect.world(p)) == z.dimension) {
              double px = ((Number) Reflect.get(p, "posX/field_70165_t")).doubleValue() - x,
                  pz = ((Number) Reflect.get(p, "posZ/field_70161_v")).doubleValue() - zz;
              if (px * px + pz * pz < 400) visible = true;
            }
          if (visible || runtime.protectedAt(w, feet)) continue;
        }
        return feet;
      }
    }
    return null;
  }

  public boolean phaseAllowed(Object entity) {
    return phases.nativeSpawnAllowed(Reflect.entityId(entity), runtime.campaign.phase);
  }

  public void scan() {
    scanner.tick();
  }
}
