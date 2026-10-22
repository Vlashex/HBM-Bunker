package dev.deadearth.legacyprobe.combat;

import dev.deadearth.legacy.*;
import dev.deadearth.legacy.combat.RelaySiege;
import dev.deadearth.legacy.defense.LegacyDefense;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.*;

/** Real block placements and native AI/ordinary ticks. No injected targets, HP or dig progress. */
public final class RelaySiegeProbe {
  private RelaySiegeProbe() {}

  public static Session begin(
      LegacyRuntime runtime, Object world, Object player, int x, int y, int z) {
    return new Session(runtime, world, player, x, y, z);
  }

  public static final class Session {
    final LegacyRuntime runtime;
    final Object world, player;
    final int x, y, z;
    final List<Object> actors = new ArrayList<Object>();
    final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    final List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
    final List<Object> players;
    final boolean originallyPresent, invulnerable;
    final String oldGriefing;
    final double oldX, oldY, oldZ;
    final Object oldConnection;
    Object actor,
        relayPos,
        relayTile,
        protectedPos,
        protectedFloor,
        previousProtected,
        previousFloor;
    int stage, stageTicks, ticks, checks, nativeLoads;
    long previousTime = Long.MIN_VALUE, offlineStartTicks, offlineHitCount;
    int offlineHp, offlineAcquireDeadline, offlineAcquireWaitTicks;
    int defenseAcquireDeadline, defenseAcquiredAt, defenseWorkInterval, defenseDamage;
    long defenseHitsBefore;
    Map<String, Object> offlineAcquisitionBudget, defenseAcquisitionBudget;
    double initialX;
    boolean acquired, moved, savedHp, hadTicket, done, removedPlayer;
    String error;

    Session(LegacyRuntime runtime, Object world, Object player, int x, int y, int z) {
      this.runtime = runtime;
      this.world = world;
      this.player = player;
      this.x = x;
      this.y = y;
      this.z = z;
      runtime.requireThread();
      if (!Boolean.getBoolean("deadearth.testControls")
          || !Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test")))
        throw new IllegalStateException("Isolated native controls required");
      @SuppressWarnings("unchecked")
      List<Object> nativePlayers =
          (List<Object>) Reflect.get(world, "playerEntities/field_73010_i");
      players = nativePlayers;
      originallyPresent = players.contains(player);
      oldX = number(player, "posX/field_70165_t");
      oldY = number(player, "posY/field_70163_u");
      oldZ = number(player, "posZ/field_70161_v");
      oldConnection = Reflect.get(player, "connection/field_71135_a");
      invulnerable =
          (Boolean)
              Reflect.get(
                  Reflect.get(player, "capabilities/field_71075_bZ"),
                  "disableDamage/field_75102_a");
      oldGriefing =
          Reflect.call(
                  Reflect.call(world, "getGameRules/func_82736_K"),
                  "getString/func_82767_a",
                  "mobGriefing")
              .toString();
      try {
        if (oldConnection == null) {
          Object network =
              Reflect.make(
                  "net.minecraft.network.NetworkManager",
                  Reflect.get(
                      Reflect.type("net.minecraft.network.EnumPacketDirection"), "SERVERBOUND"));
          Reflect.make(
              "net.minecraft.network.NetHandlerPlayServer", runtime.server, network, player);
        }
        if (!originallyPresent) players.add(player);
        Reflect.set(
            Reflect.get(player, "capabilities/field_71075_bZ"),
            "disableDamage/field_75102_a",
            true);
        MinecraftForge.EVENT_BUS.register(this);
        check(!runtime.protectedAt(world, Reflect.pos(x, y, z)), "relay arena outside grace");
        setupBreach();
      } catch (Throwable failure) {
        fail(failure);
      }
    }

    @SubscribeEvent
    public void observe(Event event) {
      if (!done
          && event.getClass().getName().endsWith("ChunkEvent$Load")
          && RelaySiege.navigationActive()) nativeLoads++;
    }

    public boolean poll() {
      if (done) return true;
      runtime.requireThread();
      ticks++;
      stageTicks++;
      try {
        long now = ((Number) Reflect.call(world, "getTotalWorldTime/func_82737_E")).longValue();
        check(now > previousTime, "only real forward ordinary world ticks observed");
        previousTime = now;
        check(nativeLoads == 0, "relay navigation/rays never load a chunk");
        if (stage == 0) breach();
        else if (stage == 1) defense();
        else if (stage == 2) grace();
        else offline();
      } catch (Throwable failure) {
        fail(failure);
      }
      return done;
    }

    public Map<String, Object> result() {
      if (!done) throw new IllegalStateException("Relay siege session still active");
      return JsonFiles.map(
          "passed",
          error == null,
          "error",
          error,
          "normalServerTicks",
          ticks,
          "assertions",
          checks,
          "nativeTaskScheduler",
          true,
          "injectedTargetOrDamageProgress",
          false,
          "ownNavigationChunkLoads",
          nativeLoads,
          "cases",
          rows,
          "statistics",
          RelaySiege.statistics(),
          "diagnostics",
          diagnostics);
    }

    private void setupBreach() {
      stage = 0;
      stageTicks = 0;
      acquired = false;
      moved = false;
      savedHp = false;
      arena();
      placeRelay();
      for (int dy = 0; dy < 3; dy++) put(Reflect.pos(x + 3, y + dy, z), "minecraft:dirt");
      griefing("false");
      position(player, x + 25.5, y, z + .5);
      actor = spawn("minecraft:husk", x + .5, y, z + .5);
      initialX = number(actor, "posX/field_70165_t");
    }

    private void breach() {
      acquired |= !RelaySiege.targetKey(actor).isEmpty();
      moved |= Math.abs(number(actor, "posX/field_70165_t") - initialX) > 1.5;
      if (stageTicks < 25) {
        check(RelaySiege.remaining(relayTile) == 1000, "mobGriefing=false preserves relay HP");
        return;
      }
      if (stageTicks == 25) griefing("true");
      hadTicket |= Reflect.get(relayTile, "ticket") != null;
      if (Reflect.blockId(world, relayPos).equals("dead_earth:zone_relay")) {
        int hp = RelaySiege.remaining(relayTile);
        check(hp >= 0 && hp <= 1000, "finite relay HP range");
        if (hp < 1000 && !savedHp) {
          Object written = Reflect.make("net.minecraft.nbt.NBTTagCompound");
          Reflect.call(relayTile, "writeToNBT/func_189515_b", written);
          Object forge = Reflect.call(written, "getCompoundTag/func_74775_l", "ForgeData");
          check(
              ((Number) Reflect.call(forge, "getInteger/func_74762_e", "DeadEarthRelayHp"))
                      .intValue()
                  == hp,
              "actual tile serialization retains damage");
          savedHp = true;
        }
        check(stageTicks < 480, "ordinary husk breaches terrain, walks and destroys 1000 HP relay");
        return;
      }
      check(
          acquired && moved && savedHp,
          "real block objective acquired, navigated and damaged durably");
      check(
          (Boolean) Reflect.call(relayTile, "isInvalid/func_145837_r")
              && Reflect.get(relayTile, "ticket") == null,
          "native break invalidates relay tile and releases any ticket");
      check(relayDrops() == 1, "exactly one relay item dropped by ordinary native break");
      rows.add(
          JsonFiles.map(
              "case",
              "husk_breaches_and_destroys_real_relay",
              "passed",
              true,
              "normalTicks",
              stageTicks,
              "hp",
              1000,
              "nativeDropCount",
              1,
              "tileInvalidated",
              true,
              "ticketWasAllocated",
              hadTicket,
              "ticketReleasedOrAbsent",
              true,
              "movementObserved",
              true,
              "serializedPartialHp",
              true));
      clearActors();
      setupDefense();
    }

    private void setupDefense() {
      stage = 1;
      stageTicks = 0;
      acquired = false;
      arena();
      placeRelay();
      for (int dy = 0; dy < 3; dy++)
        put(Reflect.pos(x + 3, y + dy, z), "dead_earth:defense_wall_v3");
      position(player, x + 25.5, y, z + .5);
      actor = spawn("srparasites:sim_human", x + .5, y, z + .5);
      defenseAcquiredAt = -1;
      defenseHitsBefore = counter("defenseHits");
      defenseAcquisitionBudget = discoveryBudget("defense");
      defenseAcquireDeadline = ((Number) defenseAcquisitionBudget.get("deadlineTicks")).intValue();
      Object policy =
          Reflect.get(Reflect.type("dev.deadearth.legacy.combat.RelaySiege"), "settings");
      defenseWorkInterval = ((Number) Reflect.get(policy, "workIntervalTicks")).intValue();
      defenseDamage = ((Number) Reflect.get(policy, "damagePerHit")).intValue();
      check(
          LegacyDefense.remaining(world, Reflect.pos(x + 3, y + 1, z)) == 48000,
          "V3 fixture starts at native full48000HP");
      diagnose("defense_acquisition_setup");
    }

    private void defense() {
      Object middle = Reflect.pos(x + 3, y + 1, z);
      for (int dy = 0; dy < 3; dy++)
        check(
            Reflect.blockId(world, Reflect.pos(x + 3, y + dy, z))
                .equals("dead_earth:defense_wall_v3"),
            "V3 cannot be physically bypassed or raw-broken by relay objective");
      check(
          Reflect.blockId(world, relayPos).equals("dead_earth:zone_relay")
              && RelaySiege.remaining(relayTile) == 1000,
          "solid V3 wall prevents through-wall relay damage");
      if (defenseAcquiredAt < 0) {
        if (!targetsRelay()) {
          check(
              stageTicks < defenseAcquireDeadline,
              "defense objective acquired within derived native scanner rounds");
          return;
        }
        acquired = true;
        defenseAcquiredAt = stageTicks;
        diagnose("defense_task_acquired");
      }
      // Discovery is deliberately excluded from this100-ordinary-server-tick work window.
      int workTicks = stageTicks - defenseAcquiredAt;
      if (workTicks < 100) return;
      int hp = LegacyDefense.remaining(world, middle), hpLost = 0;
      for (int dy = 0; dy < 3; dy++)
        hpLost += 48000 - LegacyDefense.remaining(world, Reflect.pos(x + 3, y + dy, z));
      long nativeHits = counter("defenseHits") - defenseHitsBefore;
      int maximumHits = 100 / defenseWorkInterval + 1;
      check(
          acquired
              && nativeHits > 0
              && hp < 48000
              && hpLost > 0
              && hpLost % defenseDamage == 0
              && hpLost <= (long) maximumHits * defenseDamage,
          "acquired parasite reaches physical obstruction and damages native V3 HP at bounded"
              + " native hit rate");
      diagnose("defense_work_window_complete");
      rows.add(
          JsonFiles.map(
              "case",
              "parasite_respects_v3_hp",
              "passed",
              true,
              "normalTicks",
              stageTicks,
              "acquisitionWaitTicks",
              defenseAcquiredAt,
              "ordinaryTicksAfterAcquisition",
              workTicks,
              "acquisitionBudget",
              defenseAcquisitionBudget,
              "startingHp",
              48000,
              "remainingHp",
              hp,
              "totalWallHpLost",
              hpLost,
              "observedNativeDefenseHits",
              nativeHits,
              "observedLocalWallHits",
              hpLost / defenseDamage,
              "maximumHitsFromConfiguredInterval",
              maximumHits,
              "configuredWorkIntervalTicks",
              defenseWorkInterval,
              "configuredDamagePerHit",
              defenseDamage,
              "relayHp",
              1000));
      clearActors();
      removeRelay();
      setupGrace();
    }

    private void setupGrace() {
      stage = 2;
      stageTicks = 0;
      Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
      int py = Math.min(250, Reflect.y(spawn) + 20);
      protectedPos = Reflect.pos(Reflect.x(spawn), py, Reflect.z(spawn));
      protectedFloor = Reflect.pos(Reflect.x(spawn), py - 1, Reflect.z(spawn));
      check(
          Reflect.loaded(world, protectedPos)
              && Reflect.loaded(world, protectedFloor)
              && runtime.protectedAt(world, protectedPos),
          "live grace arena loaded and protected");
      check(
          Reflect.call(world, "getTileEntity/func_175625_s", protectedPos) == null
              && Reflect.call(world, "getTileEntity/func_175625_s", protectedFloor) == null,
          "grace fixture never overwrites inventory/tile");
      previousProtected = Reflect.state(world, protectedPos);
      previousFloor = Reflect.state(world, protectedFloor);
      put(protectedFloor, "minecraft:obsidian");
      put(protectedPos, "dead_earth:zone_relay");
      relayPos = protectedPos;
      relayTile = Reflect.call(world, "getTileEntity/func_175625_s", relayPos);
      position(player, Reflect.x(spawn) + 25.5, py, Reflect.z(spawn) + .5);
      actor = spawn("srparasites:sim_human", Reflect.x(spawn) + 2.5, py, Reflect.z(spawn) + .5);
    }

    private void grace() {
      check(
          Reflect.blockId(world, protectedPos).equals("dead_earth:zone_relay")
              && RelaySiege.remaining(relayTile) == 1000,
          "three-hour bunker grace prevents relay damage");
      check(
          RelaySiege.targetKey(actor).isEmpty(),
          "protected relay is never assigned as an enemy objective");
      if (stageTicks < 40) return;
      rows.add(
          JsonFiles.map(
              "case",
              "protected_bunker_relay",
              "passed",
              true,
              "normalTicks",
              stageTicks,
              "relayHp",
              1000));
      clearActors();
      restoreProtected();
      setupOffline();
    }

    private void setupOffline() {
      stage = 3;
      stageTicks = 0;
      acquired = false;
      arena();
      placeRelay();
      position(player, x + 25.5, y, z + .5);
      griefing("false");
      actor = spawn("minecraft:husk", x + 4.5, y, z + .5);
      offlineAcquisitionBudget = discoveryBudget("offline");
      offlineAcquireDeadline = ((Number) offlineAcquisitionBudget.get("deadlineTicks")).intValue();
      diagnose("offline_acquisition_setup");
    }

    private void offline() {
      if (!removedPlayer) {
        acquired |= targetsRelay();
        check(
            RelaySiege.remaining(relayTile) == 1000,
            "offline setup uses normal griefing permission, not injected HP");
        if (!acquired) {
          check(
              stageTicks < offlineAcquireDeadline,
              "existing relay task acquired within bounded native scan rounds before offline"
                  + " transition");
          return;
        }
        if (offlineAcquireWaitTicks == 0) {
          offlineAcquireWaitTicks = stageTicks;
          diagnose("offline_task_acquired");
        }
        if (stageTicks < 20) return;
        check(
            runtime.world.players().size() == 1,
            "isolated offline fixture has exactly one live player");
        players.remove(player);
        removedPlayer = true;
        griefing("true");
        stageTicks = 0;
        return;
      }
      if (stageTicks == 5) {
        offlineHp = RelaySiege.remaining(relayTile);
        offlineHitCount = counter("relayHits");
        offlineStartTicks = runtime.campaign.activeTicks;
      }
      if (stageTicks > 5) {
        check(
            RelaySiege.remaining(relayTile) == offlineHp && counter("relayHits") == offlineHitCount,
            "pre-existing native relay task stops while no players online");
        check(
            runtime.campaign.activeTicks == offlineStartTicks,
            "campaign clock stays paused offline");
      }
      if (stageTicks < 40) return;
      rows.add(
          JsonFiles.map(
              "case",
              "existing_relay_task_suspends_offline",
              "passed",
              true,
              "normalOfflineTicks",
              40,
              "hpStableAfterTransition",
              offlineHp,
              "acquisitionWaitTicks",
              offlineAcquireWaitTicks,
              "acquisitionBudget",
              offlineAcquisitionBudget));
      finish();
    }

    /** Separate bounded discovery from physical work; both stages share production scan math. */
    private Map<String, Object> discoveryBudget(String purpose) {
      Object policy =
          Reflect.get(Reflect.type("dev.deadearth.legacy.combat.RelaySiege"), "settings");
      int tileBudget = ((Number) Reflect.get(policy, "tileVisitsPerTick")).intValue();
      int entityBudget = ((Number) Reflect.get(policy, "entityVisitsPerTick")).intValue();
      int loadedTiles =
          ((Collection<?>) Reflect.get(world, "loadedTileEntityList/field_147482_g")).size();
      int loadedEntities =
          ((Collection<?>) Reflect.get(world, "loadedEntityList/field_72996_f")).size();
      int worldCount = Math.max(1, runtime.world.worlds().length);
      long tileRounds = (loadedTiles + (long) tileBudget - 1) / tileBudget;
      long entityRounds = (loadedEntities + (long) entityBudget - 1) / entityBudget;
      // A new tile must be found, then its actor visited. Allow3 round-robin boundary ticks.
      long requiredTicks = worldCount * (tileRounds + entityRounds + 3) + 10;
      check(
          requiredTicks <= 2000,
          "bounded " + purpose + " acquisition fixture requires at most2000ticks");
      return JsonFiles.map(
          "purpose",
          purpose,
          "loadedTiles",
          loadedTiles,
          "loadedEntities",
          loadedEntities,
          "worldCount",
          worldCount,
          "tileVisitsPerTick",
          tileBudget,
          "entityVisitsPerTick",
          entityBudget,
          "deadlineTicks",
          Math.max(100, (int) requiredTicks),
          "deadlineDerivedFromProductionScanBudgets",
          true);
    }

    private boolean targetsRelay() {
      return RelaySiege.targetKey(actor)
          .equals(
              Reflect.dimension(world)
                  + ":"
                  + Reflect.x(relayPos)
                  + ":"
                  + Reflect.y(relayPos)
                  + ":"
                  + Reflect.z(relayPos));
    }

    private long counter(String key) {
      Object value = RelaySiege.statistics().get(key);
      return value == null ? 0 : ((Number) value).longValue();
    }

    private void placeRelay() {
      relayPos = Reflect.pos(x + 6, y, z);
      put(relayPos, "dead_earth:zone_relay");
      relayTile = Reflect.call(world, "getTileEntity/func_175625_s", relayPos);
      check(relayTile instanceof ZoneRelay.RelayTile, "real relay tile placed by native World API");
    }

    private Object spawn(String id, double px, double py, double pz) {
      Object entity =
          Reflect.call(
              Reflect.type("net.minecraft.entity.EntityList"),
              "createEntityByIDFromName/func_188429_b",
              Reflect.make("net.minecraft.util.ResourceLocation", id),
              world);
      check(entity != null, "native entity exists " + id);
      Reflect.call(entity, "setLocationAndAngles/func_70012_b", px, py, pz, 0f, 0f);
      check(
          (Boolean) Reflect.call(world, "spawnEntity/func_72838_d", entity),
          "native entity spawned " + id);
      actors.add(entity);
      return entity;
    }

    private void arena() {
      removeRelay();
      for (int dx = -2; dx <= 10; dx++)
        for (int dz = -1; dz <= 1; dz++) {
          put(Reflect.pos(x + dx, y - 1, z + dz), "minecraft:obsidian");
          for (int dy = 0; dy <= 3; dy++)
            put(
                Reflect.pos(x + dx, y + dy, z + dz),
                dx == -2 || dx == 10 || dz == -1 || dz == 1 || dy == 3
                    ? "hbm:block_steel"
                    : "minecraft:air");
        }
    }

    private void put(Object pos, String id) {
      check(Reflect.loaded(world, pos), "fixture operates in already loaded chunk");
      Reflect.call(
          world,
          "setBlockState/func_180501_a",
          pos,
          Reflect.call(Reflect.registry("BLOCKS", id), "getDefaultState/func_176223_P"),
          2);
      check(Reflect.blockId(world, pos).equals(id), "native fixture placement succeeded " + id);
    }

    private int relayDrops() {
      int count = 0;
      for (Object entity :
          new ArrayList<Object>(
              (Collection<?>) Reflect.get(world, "loadedEntityList/field_72996_f")))
        if (Reflect.is(entity, "net.minecraft.entity.item.EntityItem")) {
          Object stack = Reflect.call(entity, "getItem/func_92059_d");
          if (Reflect.call(Reflect.call(stack, "getItem/func_77973_b"), "getRegistryName")
                  .toString()
                  .equals("dead_earth:zone_relay")
              && Math.abs(number(entity, "posX/field_70165_t") - Reflect.x(relayPos)) < 2
              && Math.abs(number(entity, "posZ/field_70161_v") - Reflect.z(relayPos)) < 2) {
            count += ((Number) Reflect.call(stack, "getCount/func_190916_E")).intValue();
            Reflect.call(entity, "setDead/func_70106_y");
            Reflect.call(world, "removeEntity/func_72900_e", entity);
          }
        }
      return count;
    }

    private void griefing(String value) {
      Reflect.call(
          Reflect.call(world, "getGameRules/func_82736_K"),
          "setOrCreateGameRule/func_82764_b",
          "mobGriefing",
          value);
    }

    private void clearActors() {
      for (Object entity : actors) {
        Reflect.call(entity, "setDead/func_70106_y");
        Reflect.call(world, "removeEntity/func_72900_e", entity);
      }
      actors.clear();
      actor = null;
    }

    private void removeRelay() {
      if (relayPos != null
          && Reflect.loaded(world, relayPos)
          && Reflect.blockId(world, relayPos).equals("dead_earth:zone_relay"))
        Reflect.call(world, "destroyBlock/func_175655_b", relayPos, false);
      relayPos = null;
      relayTile = null;
    }

    private void restoreProtected() {
      if (protectedPos != null) {
        removeRelay();
        Reflect.call(world, "setBlockState/func_180501_a", protectedPos, previousProtected, 2);
        Reflect.call(world, "setBlockState/func_180501_a", protectedFloor, previousFloor, 2);
        protectedPos = null;
      }
    }

    private void check(boolean condition, String detail) {
      checks++;
      if (!condition) throw new AssertionError(detail);
    }

    private void diagnose(String reason) {
      if (diagnostics.size() >= 8) return;
      Map<String, Object> snapshot =
          JsonFiles.map(
              "reason",
              reason,
              "stage",
              stage,
              "stageTicks",
              stageTicks,
              "loadedTiles",
              ((Collection<?>) Reflect.get(world, "loadedTileEntityList/field_147482_g")).size(),
              "loadedEntities",
              ((Collection<?>) Reflect.get(world, "loadedEntityList/field_72996_f")).size(),
              "survivalPlayers",
              runtime.world.players().size(),
              "playerPresent",
              players.contains(player),
              "playerHealth",
              Reflect.call(player, "getHealth/func_110143_aJ"),
              "playerSurvival",
              runtime.world.survival(player),
              "mobGriefing",
              Reflect.call(
                  Reflect.call(world, "getGameRules/func_82736_K"),
                  "getString/func_82767_a",
                  "mobGriefing"),
              "relayBlockLoaded",
              relayPos != null && Reflect.loaded(world, relayPos),
              "relayTileInvalid",
              relayTile == null ? null : Reflect.call(relayTile, "isInvalid/func_145837_r"),
              "relayTileInLoadedList",
              ((Collection<?>) Reflect.get(world, "loadedTileEntityList/field_147482_g"))
                  .contains(relayTile),
              "statisticsBeforeCleanup",
              RelaySiege.statistics());
      if (stage == 1) {
        snapshot.put("acquisitionBudget", defenseAcquisitionBudget);
        snapshot.put("acquiredAtStageTick", defenseAcquiredAt);
        snapshot.put(
            "ordinaryTicksAfterAcquisition",
            defenseAcquiredAt < 0 ? 0 : stageTicks - defenseAcquiredAt);
        snapshot.put("nativeDefenseHitDelta", counter("defenseHits") - defenseHitsBefore);
        snapshot.put(
            "wallRemainingHp",
            Arrays.asList(
                LegacyDefense.remaining(world, Reflect.pos(x + 3, y, z)),
                LegacyDefense.remaining(world, Reflect.pos(x + 3, y + 1, z)),
                LegacyDefense.remaining(world, Reflect.pos(x + 3, y + 2, z))));
      }
      if (actor != null) {
        snapshot.put(
            "actor",
            JsonFiles.map(
                "type",
                Reflect.entityId(actor),
                "isDead",
                Reflect.get(actor, "isDead/field_70128_L"),
                "alive",
                Reflect.call(actor, "isEntityAlive/func_70089_S"),
                "ticksExisted",
                Reflect.get(actor, "ticksExisted/field_70173_aa"),
                "inLoadedEntityList",
                ((Collection<?>) Reflect.get(world, "loadedEntityList/field_72996_f"))
                    .contains(actor),
                "chunkLoaded",
                Reflect.loaded(world, Reflect.position(actor)),
                "position",
                Arrays.asList(
                    number(actor, "posX/field_70165_t"),
                    number(actor, "posY/field_70163_u"),
                    number(actor, "posZ/field_70161_v")),
                "targetKey",
                RelaySiege.targetKey(actor)));
      }
      diagnostics.add(snapshot);
    }

    private void fail(Throwable failure) {
      error = failure.toString();
      try {
        diagnose("failure_before_cleanup");
      } catch (Throwable diagnosticFailure) {
        diagnostics.add(JsonFiles.map("diagnosticError", diagnosticFailure.toString()));
      }
      failure.printStackTrace();
      finish();
    }

    private void finish() {
      if (done) return;
      done = true;
      MinecraftForge.EVENT_BUS.unregister(this);
      clearActors();
      restoreProtected();
      removeRelay();
      if (removedPlayer && !players.contains(player)) players.add(player);
      if (!originallyPresent) players.remove(player);
      position(player, oldX, oldY, oldZ);
      Reflect.set(
          Reflect.get(player, "capabilities/field_71075_bZ"),
          "disableDamage/field_75102_a",
          invulnerable);
      griefing(oldGriefing);
      if (oldConnection == null) Reflect.set(player, "connection/field_71135_a", null);
    }
  }

  private static void position(Object entity, double x, double y, double z) {
    Reflect.call(entity, "setPosition/func_70107_b", x, y, z);
  }

  private static double number(Object entity, String field) {
    return ((Number) Reflect.get(entity, field)).doubleValue();
  }
}
