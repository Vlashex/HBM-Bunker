package dev.deadearth.legacy.combat;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.defense.LegacyDefense;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.entity.ai.EntityAIBase;

/** Real block objective using native navigation and scheduler; never creates an entity target. */
public final class RelaySiege {
  private static RelaySiegeSettings settings;
  private static Runner owner;
  private static final ThreadLocal<Boolean> NAVIGATION = new ThreadLocal<Boolean>();
  private static final ThreadLocal<Boolean> HOSTILE_WRITE = new ThreadLocal<Boolean>();

  private RelaySiege() {}

  public static void install(Path directory) {
    settings = RelaySiegeSettings.load(directory.resolve("dead-earth/relay-siege.json"));
  }

  public static void tick(LegacyRuntime runtime) {
    runtime.requireThread();
    if (settings == null || !settings.enabled || !runtime.settings.enabled) return;
    if (owner == null || owner.runtime != runtime) {
      close();
      owner = new Runner(runtime);
    }
    owner.tick();
  }

  public static void suspend() {
    if (owner != null) {
      owner.active.clear();
      owner.lastManagedTick = Integer.MIN_VALUE;
    }
  }

  public static boolean navigationActive() {
    return Boolean.TRUE.equals(NAVIGATION.get());
  }

  public static boolean hostileWriteActive() {
    return Boolean.TRUE.equals(HOSTILE_WRITE.get());
  }

  public static void close() {
    if (owner != null) {
      owner.close();
      owner = null;
    }
    HOSTILE_WRITE.remove();
    NAVIGATION.remove();
  }

  public static Map<String, Object> statistics() {
    return owner == null ? JsonFiles.map("ready", false) : owner.statistics();
  }

  public static int remaining(Object tile) {
    Object data = Reflect.call(tile, "getTileData");
    return (Boolean) Reflect.call(data, "hasKey/func_74764_b", "DeadEarthRelayHp")
        ? ((Number) Reflect.call(data, "getInteger/func_74762_e", "DeadEarthRelayHp")).intValue()
        : settings.relayHitPoints;
  }

  public static String targetKey(Object entity) {
    if (owner == null) return "";
    Goal goal = owner.goals.get(uuid(entity));
    return goal == null ? "" : goal.target.key;
  }

  private static String uuid(Object entity) {
    return Reflect.call(entity, "getUniqueID/func_110124_au").toString();
  }

  private static double number(Object entity, String field) {
    return ((Number) Reflect.get(entity, field)).doubleValue();
  }

  private static boolean alive(Object entity) {
    return !(Boolean) Reflect.get(entity, "isDead/field_70128_L")
        && (Boolean) Reflect.call(entity, "isEntityAlive/func_70089_S");
  }

  private static boolean eligible(Object entity) {
    return Reflect.is(entity, "net.minecraft.entity.EntityLiving")
        && !Reflect.is(entity, "net.minecraft.entity.passive.EntityAnimal")
        && (Reflect.is(entity, "net.minecraft.entity.monster.IMob")
            || Reflect.entityId(entity).startsWith("srparasites:"))
        && alive(entity);
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

  private static double distance(Object entity, Object pos) {
    double x = number(entity, "posX/field_70165_t") - Reflect.x(pos) - .5,
        y = number(entity, "posY/field_70163_u") - Reflect.y(pos) - .5,
        z = number(entity, "posZ/field_70161_v") - Reflect.z(pos) - .5;
    return x * x + y * y + z * z;
  }

  private static double entityDistance(Object a, Object b) {
    double x = number(a, "posX/field_70165_t") - number(b, "posX/field_70165_t"),
        y = number(a, "posY/field_70163_u") - number(b, "posY/field_70163_u"),
        z = number(a, "posZ/field_70161_v") - number(b, "posZ/field_70161_v");
    return x * x + y * y + z * z;
  }

  private static final class Target {
    final Object world, pos, tile;
    final String key;

    Target(Object world, Object pos, Object tile) {
      this.world = world;
      this.pos = pos;
      this.tile = tile;
      key = key(world, pos);
    }

    boolean valid() {
      return Reflect.loaded(world, pos)
          && Reflect.blockId(world, pos).equals("dead_earth:zone_relay")
          && Reflect.call(world, "getTileEntity/func_175625_s", pos) == tile
          && !(Boolean) Reflect.call(tile, "isInvalid/func_145837_r");
    }
  }

  private static final class Runner {
    final LegacyRuntime runtime;
    final Map<String, Target> targets = new LinkedHashMap<String, Target>();
    final Map<String, Goal> goals = new LinkedHashMap<String, Goal>();
    final Set<String> active = new HashSet<String>();
    final Map<Object, Integer> tileCursors = new IdentityHashMap<Object, Integer>(),
        entityCursors = new IdentityHashMap<Object, Integer>();
    int worldCursor, lastManagedTick = Integer.MIN_VALUE, workTick = Integer.MIN_VALUE, workBudget;
    long acquired, navigationAttempts, relayHits, defenseHits, relayBreaks, ordinaryBreaks, denied;

    Runner(LegacyRuntime runtime) {
      this.runtime = runtime;
    }

    void tick() {
      runtime.requireThread();
      lastManagedTick =
          ((Number) Reflect.call(runtime.server, "getTickCounter/func_71259_af")).intValue();
      for (Iterator<Target> it = targets.values().iterator(); it.hasNext(); )
        if (!it.next().valid()) it.remove();
      for (Iterator<Goal> it = goals.values().iterator(); it.hasNext(); ) {
        Goal goal = it.next();
        if (!goal.valid()) {
          goal.detach();
          it.remove();
        }
      }
      active.clear();
      for (Map.Entry<String, Goal> entry : goals.entrySet()) {
        Goal goal = entry.getValue();
        if (active.size() < settings.maximumActiveGoals
            && goal.valid()
            && !runtime.protectedAt(goal.target.world, goal.target.pos)
            && !goal.combatNear()) active.add(entry.getKey());
      }
      Object[] worlds = runtime.world.worlds();
      if (worlds.length == 0) return;
      Object world = worlds[Math.floorMod(worldCursor++, worlds.length)];
      if (world == null || !runtime.settings.controls(Reflect.dimension(world))) return;
      List<?> tiles = (List<?>) Reflect.get(world, "loadedTileEntityList/field_147482_g");
      int cursor = cursor(tileCursors, world);
      for (int n = 0; n < Math.min(settings.tileVisitsPerTick, tiles.size()); n++) {
        Object tile = tiles.get(Math.floorMod(cursor++, tiles.size()));
        if (!(tile instanceof ZoneRelay.RelayTile)) continue;
        Object pos = Reflect.call(tile, "getPos/func_174877_v");
        String key = key(world, pos);
        if (targets.size() < settings.maximumRelays || targets.containsKey(key)) {
          Target target = new Target(world, pos, tile);
          if (target.valid()) targets.put(key, target);
        }
      }
      tileCursors.put(world, cursor);
      List<?> entities = (List<?>) Reflect.get(world, "loadedEntityList/field_72996_f");
      cursor = cursor(entityCursors, world);
      for (int n = 0; n < Math.min(settings.entityVisitsPerTick, entities.size()); n++) {
        Object entity = entities.get(Math.floorMod(cursor++, entities.size()));
        if (goals.size() >= settings.maximumTrackedGoals
            || !eligible(entity)
            || goals.containsKey(uuid(entity))) continue;
        Target nearest = null;
        double best = settings.radius * settings.radius;
        for (Target target : targets.values())
          if (target.world == world && !runtime.protectedAt(world, target.pos)) {
            double d = distance(entity, target.pos);
            if (d < best) {
              nearest = target;
              best = d;
            }
          }
        if (nearest != null) {
          Goal goal = new Goal(this, entity, nearest);
          goals.put(uuid(entity), goal);
          Reflect.call(
              Reflect.get(entity, "tasks/field_70714_bg"), "addTask/func_75776_a", 1, goal);
          acquired++;
        }
      }
      entityCursors.put(world, cursor);
    }

    private int cursor(Map<Object, Integer> cursors, Object world) {
      Integer value = cursors.get(world);
      return value == null ? 0 : value;
    }

    boolean online() {
      if (!settings.enabled || !runtime.settings.enabled) return false;
      for (Object world : runtime.world.worlds())
        if (world != null)
          for (Object player : (List<?>) Reflect.get(world, "playerEntities/field_73010_i"))
            if (runtime.world.survival(player)) return true;
      return false;
    }

    boolean lease() {
      long now =
          ((Number) Reflect.call(runtime.server, "getTickCounter/func_71259_af")).longValue();
      return lastManagedTick != Integer.MIN_VALUE
          && now - lastManagedTick >= 0
          && now - lastManagedTick <= 2;
    }

    boolean step() {
      int tick = ((Number) Reflect.call(runtime.server, "getTickCounter/func_71259_af")).intValue();
      if (tick != workTick) {
        workTick = tick;
        workBudget = settings.workStepsPerTick;
      }
      if (workBudget <= 0) return false;
      workBudget--;
      return true;
    }

    Map<String, Object> statistics() {
      return JsonFiles.map(
          "ready",
          true,
          "loadedRelays",
          targets.size(),
          "trackedGoals",
          goals.size(),
          "activeGoals",
          active.size(),
          "acquired",
          acquired,
          "navigationAttempts",
          navigationAttempts,
          "relayHits",
          relayHits,
          "defenseHits",
          defenseHits,
          "relayBreaks",
          relayBreaks,
          "ordinaryBreaks",
          ordinaryBreaks,
          "guardDenials",
          denied,
          "loadedAreaAdmission",
          true);
    }

    void close() {
      for (Goal goal : goals.values()) goal.detach();
      goals.clear();
      active.clear();
      targets.clear();
      tileCursors.clear();
      entityCursors.clear();
    }
  }

  /**
   * Native MOVE task preempts distant chasing; nearby combat remains in the original native tasks.
   */
  private static final class Goal extends EntityAIBase {
    final Runner runner;
    final Object entity;
    final Target target;
    int repath, work;
    String digging = "";
    double digProgress;
    Object crackPos;

    Goal(Runner runner, Object entity, Target target) {
      this.runner = runner;
      this.entity = entity;
      this.target = target;
      Reflect.call(this, "setMutexBits/func_75248_a", 1);
    }

    boolean valid() {
      return owner == runner
          && runner.online()
          && runner.lease()
          && alive(entity)
          && Reflect.world(entity) == target.world
          && Reflect.loaded(target.world, Reflect.position(entity))
          && target.valid()
          && distance(entity, target.pos) <= settings.radius * settings.radius;
    }

    boolean combatNear() {
      Object enemy = Reflect.call(entity, "getAttackTarget/func_70638_az");
      double range = settings.meleePriorityRadius * settings.meleePriorityRadius;
      if (enemy != null && alive(enemy) && entityDistance(entity, enemy) <= range) return true;
      List<?> players = (List<?>) Reflect.get(target.world, "playerEntities/field_73010_i");
      for (int n = 0; n < Math.min(64, players.size()); n++) {
        Object player = players.get(n);
        if (runner.runtime.world.survival(player) && entityDistance(entity, player) <= range)
          return true;
      }
      return false;
    }

    public boolean func_75250_a() {
      return runner.active.contains(uuid(entity))
          && valid()
          && !runner.runtime.protectedAt(target.world, target.pos)
          && !combatNear();
    }

    public boolean func_75253_b() {
      return func_75250_a();
    }

    public void func_75249_e() {
      repath = 0;
      work = 0;
    }

    public void func_75251_c() {
      crack(null, -1);
      digging = "";
      digProgress = 0;
      Reflect.call(Reflect.call(entity, "getNavigator/func_70661_as"), "clearPath/func_75499_g");
    }

    public void func_75246_d() {
      if (!func_75250_a()) return;
      if (--repath <= 0 && runner.step()) {
        repath = settings.repathTicks;
        navigate();
      }
      if (--work <= 0 && runner.step()) {
        work = settings.workIntervalTicks;
        attack();
      }
    }

    void detach() {
      crack(null, -1);
      Reflect.call(Reflect.get(entity, "tasks/field_70714_bg"), "removeTask/func_85156_a", this);
    }

    private boolean area(
        Object world, double x1, double y1, double z1, double x2, double y2, double z2) {
      return (Boolean)
          Reflect.call(
              world,
              "isAreaLoaded/func_175706_a",
              Reflect.pos(
                  (int) Math.floor(Math.min(x1, x2)),
                  Math.max(0, (int) Math.floor(Math.min(y1, y2))),
                  (int) Math.floor(Math.min(z1, z2))),
              Reflect.pos(
                  (int) Math.ceil(Math.max(x1, x2)),
                  Math.min(255, (int) Math.ceil(Math.max(y1, y2))),
                  (int) Math.ceil(Math.max(z1, z2))),
              false);
    }

    private Object ray(double eyeOffset) {
      Object world = target.world;
      double x = number(entity, "posX/field_70165_t"),
          y = number(entity, "posY/field_70163_u") + eyeOffset,
          z = number(entity, "posZ/field_70161_v");
      double tx = Reflect.x(target.pos) + .5,
          ty = Reflect.y(target.pos) + (eyeOffset < .5 ? .2 : .5),
          tz = Reflect.z(target.pos) + .5;
      if (!area(world, x, y, z, tx, ty, tz)) return null;
      Boolean previous = NAVIGATION.get();
      NAVIGATION.set(true);
      try {
        return Reflect.call(
            world,
            "rayTraceBlocks/func_147447_a",
            Reflect.make("net.minecraft.util.math.Vec3d", x, y, z),
            Reflect.make("net.minecraft.util.math.Vec3d", tx, ty, tz),
            false,
            true,
            false);
      } finally {
        if (previous == null) NAVIGATION.remove();
        else NAVIGATION.set(previous);
      }
    }

    private boolean stand(Object feet) {
      Object head = Reflect.pos(Reflect.x(feet), Reflect.y(feet) + 1, Reflect.z(feet)),
          floor = Reflect.pos(Reflect.x(feet), Reflect.y(feet) - 1, Reflect.z(feet));
      return Reflect.loaded(target.world, feet)
          && Reflect.loaded(target.world, head)
          && Reflect.loaded(target.world, floor)
          && (Boolean) Reflect.call(target.world, "isAirBlock/func_175623_d", feet)
          && (Boolean) Reflect.call(target.world, "isAirBlock/func_175623_d", head)
          && (Boolean) Reflect.call(Reflect.state(target.world, floor), "isFullCube/func_185913_b");
    }

    private void navigate() {
      Object at = null;
      double best = Double.POSITIVE_INFINITY;
      int tx = Reflect.x(target.pos), ty = Reflect.y(target.pos), tz = Reflect.z(target.pos);
      // An unreachable block destination produces no partial native path. Approach the
      // first wall on its near side, then the ordinary hardness/HP pipeline opens it.
      Object hit = ray(((Number) Reflect.call(entity, "getEyeHeight/func_70047_e")).doubleValue());
      if (hit != null) {
        Object barrier = Reflect.call(hit, "getBlockPos/func_178782_a");
        if (barrier != null && !barrier.equals(target.pos)) {
          int feetY = (int) Math.floor(number(entity, "posY/field_70163_u"));
          for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            Object candidate =
                Reflect.pos(Reflect.x(barrier) + d[0], feetY, Reflect.z(barrier) + d[1]);
            if (stand(candidate)) {
              double length = distance(entity, candidate);
              if (length < best) {
                best = length;
                at = candidate;
              }
            }
          }
        }
      }
      if (at == null)
        for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
          Object candidate = Reflect.pos(tx + d[0], ty, tz + d[1]);
          if (stand(candidate)) {
            double length = distance(entity, candidate);
            if (length < best) {
              best = length;
              at = candidate;
            }
          }
        }
      if (at == null) return;
      Object follow =
          Reflect.call(
              entity,
              "getEntityAttribute/func_110148_a",
              Reflect.get(
                  Reflect.type("net.minecraft.entity.SharedMonsterAttributes"),
                  "FOLLOW_RANGE/field_111265_b"));
      double followRange =
          follow == null
              ? 16
              : ((Number) Reflect.call(follow, "getAttributeValue/func_111126_e")).doubleValue();
      if (!Double.isFinite(followRange) || followRange > 256) return;
      // Vanilla PathNavigate builds a ChunkCache around the actor using FOLLOW_RANGE+8.
      // Guard the whole cache footprint before entering any native/custom navigator.
      int radius = (int) Math.ceil(followRange + 9);
      double ex = number(entity, "posX/field_70165_t"), ez = number(entity, "posZ/field_70161_v");
      if (!area(target.world, ex - radius, 0, ez - radius, ex + radius, 255, ez + radius)) return;
      runner.navigationAttempts++;
      Boolean previous = NAVIGATION.get();
      NAVIGATION.set(true);
      try {
        Reflect.call(
            Reflect.call(entity, "getNavigator/func_70661_as"),
            "tryMoveToXYZ/func_75492_a",
            Reflect.x(at) + .5,
            (double) Reflect.y(at),
            Reflect.z(at) + .5,
            settings.navigationSpeed);
      } finally {
        if (previous == null) NAVIGATION.remove();
        else NAVIGATION.set(previous);
      }
    }

    private void attack() {
      Object world = target.world;
      Object hit = ray(((Number) Reflect.call(entity, "getEyeHeight/func_70047_e")).doubleValue());
      if (hit == null) return;
      Object pos = Reflect.call(hit, "getBlockPos/func_178782_a");
      if (pos == null || distance(entity, pos) > settings.reach * settings.reach) {
        hit = ray(.2);
        pos = hit == null ? null : Reflect.call(hit, "getBlockPos/func_178782_a");
      }
      if (pos == null
          || distance(entity, pos) > settings.reach * settings.reach
          || !allowed(world, pos)) return;
      Reflect.call(
          Reflect.call(entity, "getLookHelper/func_70671_ap"),
          "setLookPosition/func_75650_a",
          Reflect.x(pos) + .5,
          Reflect.y(pos) + .5,
          Reflect.z(pos) + .5,
          30f,
          30f);
      Reflect.call(
          entity,
          "swingArm/func_184609_a",
          Reflect.get(Reflect.type("net.minecraft.util.EnumHand"), "MAIN_HAND"));
      Object state = Reflect.state(world, pos),
          block = Reflect.call(state, "getBlock/func_177230_c");
      String id = Reflect.call(block, "getRegistryName").toString();
      if (LegacyDefense.isDefense(block)) {
        if (LegacyDefense.damage(world, pos, settings.damagePerHit, true)) runner.defenseHits++;
        return;
      }
      if (id.equals("dead_earth:zone_relay")) {
        Object tile = Reflect.call(world, "getTileEntity/func_175625_s", pos);
        if (!(tile instanceof ZoneRelay.RelayTile)) return;
        int hp = remaining(tile);
        if (hp < 0 || hp > 100000)
          throw new IllegalStateException("Relay HP is malformed; block preserved");
        int next = Math.max(0, hp - settings.damagePerHit);
        Object data = Reflect.call(tile, "getTileData");
        Reflect.call(data, "setInteger/func_74768_a", "DeadEarthRelayHp", next);
        Reflect.call(tile, "markDirty/func_70296_d");
        runner.relayHits++;
        crack(
            pos,
            Math.min(9, (int) (10L * (settings.relayHitPoints - next) / settings.relayHitPoints)));
        if (next == 0 && destroy(world, pos, state)) runner.relayBreaks++;
      } else {
        String key = key(world, pos) + ":" + id;
        if (!digging.equals(key)) {
          digging = key;
          digProgress = 0;
        }
        double hardness =
            ((Number) Reflect.call(state, "getBlockHardness/func_185887_b", world, pos))
                .doubleValue();
        // Keep normal terrain finite and hardness-dependent; one work stroke is one configured
        // interval.
        digProgress += settings.workIntervalTicks / Math.max(5d, hardness * 30d);
        crack(pos, Math.min(9, (int) (digProgress * 10)));
        if (digProgress >= 1 && destroy(world, pos, state)) {
          runner.ordinaryBreaks++;
          digging = "";
          digProgress = 0;
          repath = 0;
        }
      }
    }

    private void crack(Object pos, int stage) {
      int id = ((Number) Reflect.call(entity, "getEntityId/func_145782_y")).intValue();
      if (crackPos != null && (pos == null || !crackPos.equals(pos)))
        Reflect.call(target.world, "sendBlockBreakProgress/func_175715_c", id, crackPos, -1);
      crackPos = pos;
      if (pos != null)
        Reflect.call(
            target.world, "sendBlockBreakProgress/func_175715_c", id, pos, Math.max(0, stage));
    }

    private boolean allowed(Object world, Object pos) {
      if (!Reflect.loaded(world, pos) || runner.runtime.protectedAt(world, pos)) {
        runner.denied++;
        return false;
      }
      if (!NativeSiegePermissions.mobGriefing(world, entity)) {
        runner.denied++;
        return false;
      }
      Object state = Reflect.state(world, pos),
          block = Reflect.call(state, "getBlock/func_177230_c");
      String id = Reflect.call(block, "getRegistryName").toString();
      if (id.equals("minecraft:air")
          || id.startsWith("hbm:")
          || id.equals("minecraft:bedrock")
          || ((Number) Reflect.call(state, "getBlockHardness/func_185887_b", world, pos))
                  .doubleValue()
              < 0) return false;
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", pos);
      if (tile != null && !id.equals("dead_earth:zone_relay")) return false;
      if ((!LegacyDefense.isDefense(block)
              && !NativeSiegePermissions.blockCanDestroy(block, state, world, pos, entity))
          || !NativeSiegePermissions.destroyEvent(entity, pos, state)) {
        runner.denied++;
        return false;
      }
      return Reflect.loaded(world, pos)
          && !runner.runtime.protectedAt(world, pos)
          && Reflect.state(world, pos).equals(state);
    }

    private boolean destroy(Object world, Object pos, Object state) {
      // Recheck all mutable permissions immediately before the guarded native World mutation.
      if (!allowed(world, pos) || !Reflect.state(world, pos).equals(state)) return false;
      Boolean previous = HOSTILE_WRITE.get();
      HOSTILE_WRITE.set(true);
      try {
        boolean removed = (Boolean) Reflect.call(world, "destroyBlock/func_175655_b", pos, false);
        if (!removed || Reflect.state(world, pos).equals(state)) return false;
        Object block = Reflect.call(state, "getBlock/func_177230_c");
        Reflect.call(block, "dropBlockAsItem/func_176226_b", world, pos, state, 0);
        return true;
      } finally {
        if (previous == null) HOSTILE_WRITE.remove();
        else HOSTILE_WRITE.set(previous);
      }
    }
  }
}
