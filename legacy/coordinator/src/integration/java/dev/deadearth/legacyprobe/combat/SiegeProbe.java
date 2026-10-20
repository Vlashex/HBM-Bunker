package dev.deadearth.legacyprobe.combat;

import dev.deadearth.legacy.DeadEarthLegacy;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.LegacyRuntime;
import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.combat.LegacySiege;
import dev.deadearth.legacy.defense.LegacyDefense;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Functional native AI session: live entities and ordinary server ticks, never injected digTick.
 */
public final class SiegeProbe {
  private static final String DIG = "funwayguy.epicsiegemod.ai.ESM_EntityAIDigging";
  private static final String[] CHASERS = {
    "srparasites:sim_human", "srparasites:pri_longarms", "srparasites:ada_longarms"
  };

  private SiegeProbe() {}

  public static Session begin(
      LegacyRuntime runtime, Object world, Object player, int x, int y, int z) {
    return new Session(runtime, world, player, x, y, z);
  }

  public static final class Session {
    private final LegacyRuntime runtime;
    private final Object world, player, originalPlayer;
    private final int x, y, z;
    private final List<Object> actors = new ArrayList<Object>();
    private final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
    private final Map<Object, Double> committedDamage = new LinkedHashMap<Object, Double>();
    private final Map<Object, Double> tickDamage = new LinkedHashMap<Object, Double>();
    private final double oldX, oldY, oldZ, oldMaxHealth;
    private final float oldHealth;
    private final int oldHurtTime;
    private final Object healthAttribute, previousConnection;
    private final boolean oldInvulnerable;
    private final String oldMobGriefing, oldMobSpawning;
    private final boolean oldAnimalsAllowed;
    private int ticks, stageTicks, stage, chaseIndex, assertions;
    private Object cow, herd, otherSpecies, actor, nativeDig;
    private double startX, startZ, herdStartX, herdStartZ, herdStartDistance, previousHealth;
    private boolean provoked,
        observedGroup,
        observedDigProgress,
        observedAcquisition,
        crossedBreach,
        done;
    private final List<Object> worldPlayers;
    private final boolean addedWorldPlayer, originalPlayerPresent;
    private String error;

    private Session(
        LegacyRuntime runtime, Object world, Object suppliedPlayer, int x, int y, int z) {
      this.runtime = runtime;
      this.world = world;
      this.originalPlayer = suppliedPlayer;
      Object player = combatPlayer(runtime, world);
      this.player = player;
      this.x = x;
      this.y = y;
      this.z = z;
      runtime.requireThread();
      check(
          Boolean.getBoolean("deadearth.testControls")
              && Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test")),
          "isolated native controls");
      check(
          DeadEarthLegacy.runtime == runtime && !runtime.protectedAt(world, Reflect.pos(x, y, z)),
          "fresh arena outside spawn grace");
      oldX = number(player, "posX/field_70165_t");
      oldY = number(player, "posY/field_70163_u");
      oldZ = number(player, "posZ/field_70161_v");
      oldHealth = ((Number) Reflect.call(player, "getHealth/func_110143_aJ")).floatValue();
      oldHurtTime = ((Number) Reflect.get(player, "hurtResistantTime/field_70172_ad")).intValue();
      healthAttribute =
          Reflect.call(
              player,
              "getEntityAttribute/func_110148_a",
              Reflect.get(
                  Reflect.type("net.minecraft.entity.SharedMonsterAttributes"),
                  "MAX_HEALTH/field_111267_a"));
      oldMaxHealth =
          ((Number) Reflect.call(healthAttribute, "getBaseValue/func_111125_b")).doubleValue();
      Object caps = Reflect.get(player, "capabilities/field_71075_bZ");
      oldInvulnerable = (Boolean) Reflect.get(caps, "disableDamage/field_75102_a");
      previousConnection = Reflect.get(player, "connection/field_71135_a");
      @SuppressWarnings("unchecked")
      List<Object> nativePlayers =
          (List<Object>) Reflect.get(world, "playerEntities/field_73010_i");
      worldPlayers = nativePlayers;
      originalPlayerPresent = suppliedPlayer != null && worldPlayers.contains(suppliedPlayer);
      addedWorldPlayer = !worldPlayers.contains(player);
      Object rules = Reflect.call(world, "getGameRules/func_82736_K");
      oldMobGriefing = Reflect.call(rules, "getString/func_82767_a", "mobGriefing").toString();
      oldMobSpawning = Reflect.call(rules, "getString/func_82767_a", "doMobSpawning").toString();
      oldAnimalsAllowed = (Boolean) Reflect.call(runtime.server, "getCanSpawnAnimals/func_71268_U");
      try {
        if (previousConnection == null) {
          Object network =
              Reflect.make(
                  "net.minecraft.network.NetworkManager",
                  Reflect.get(
                      Reflect.type("net.minecraft.network.EnumPacketDirection"), "SERVERBOUND"));
          Reflect.make(
              "net.minecraft.network.NetHandlerPlayServer",
              Reflect.call(world, "getMinecraftServer/func_73046_m"),
              network,
              player);
        }
        Reflect.call(healthAttribute, "setBaseValue/func_111128_a", 1000d);
        Reflect.call(player, "setHealth/func_70606_j", 1000f);
        Reflect.set(caps, "disableDamage/field_75102_a", false);
        Reflect.set(player, "hurtResistantTime/field_70172_ad", 0);
        Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "mobGriefing", "true");
        // spawn-animals=false kills even manually spawned cows on their first native update.
        // Suppress natural spawning separately, while allowing the real test animals to live.
        Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "doMobSpawning", "false");
        Reflect.call(runtime.server, "setCanSpawnAnimals/func_71251_e", true);
        previousHealth = health();
        if (originalPlayerPresent) worldPlayers.remove(originalPlayer);
        if (addedWorldPlayer) worldPlayers.add(player);
        Object generic =
            Reflect.get(Reflect.type("net.minecraft.util.DamageSource"), "GENERIC/field_76377_j");
        check(
            !Reflect.is(player, "net.minecraftforge.common.util.FakePlayer"),
            "combat uses actual EntityPlayerMP, not always-invulnerable Forge FakePlayer");
        check(
            !(Boolean) Reflect.call(player, "isEntityInvulnerable/func_180431_b", generic),
            "ordinary survival combat player admits native damage");
        MinecraftForge.EVENT_BUS.register(this);
        admissionsAndCachedTargets();
        setupAnimals();
      } catch (Throwable failure) {
        fail(failure);
      }
    }

    /**
     * Observe only actual damage to this isolated player; pair it with committed HP loss at END.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void observe(Event event) {
      if (done || !event.getClass().getName().endsWith("LivingDamageEvent") || event.isCanceled())
        return;
      if (Reflect.call(event, "getEntityLiving") != player) return;
      Object source = Reflect.call(Reflect.call(event, "getSource"), "getTrueSource/func_76346_g");
      double amount = ((Number) Reflect.call(event, "getAmount")).doubleValue();
      if (source != null && actors.contains(source) && amount > 0)
        tickDamage.put(source, value(tickDamage, source) + amount);
    }

    public boolean poll() {
      if (done) return true;
      runtime.requireThread();
      ticks++;
      stageTicks++;
      try {
        double current = health(), loss = Math.max(0, previousHealth - current), total = 0;
        for (double amount : tickDamage.values()) total += amount;
        if (loss > 0 && total > 0)
          for (Map.Entry<Object, Double> row : tickDamage.entrySet())
            committedDamage.put(
                row.getKey(), value(committedDamage, row.getKey()) + loss * row.getValue() / total);
        tickDamage.clear();
        previousHealth = current;
        check(current > 0, "native AI test player remains alive");
        if (stage == 0) animals();
        else if (stage == 1) chase();
        else if (stage == 2) breach();
        else finish();
      } catch (Throwable failure) {
        fail(failure);
      }
      return done;
    }

    public Map<String, Object> result() {
      if (!done) throw new IllegalStateException("Native siege session still running");
      return JsonFiles.map(
          "passed",
          error == null,
          "error",
          error,
          "normalServerTicks",
          ticks,
          "assertions",
          assertions,
          "nativeAiScheduler",
          true,
          "chaseAndBreachInjectedDigTicks",
          false,
          "liveCases",
          rows,
          "diagnostics",
          diagnostics,
          "playerImplementation",
          player.getClass().getName(),
          "playerDamageMocked",
          false,
          "fixtureAnimalsAllowed",
          true,
          "fixtureNaturalSpawningDisabled",
          true,
          "animalsProactive",
          false,
          "groupRetaliation",
          observedGroup,
          "allCasesPassed",
          error == null);
    }

    private void admissionsAndCachedTargets() throws Exception {
      Collection<?> exemptions =
          (Collection<?>)
              Reflect.get(
                  Reflect.type("funwayguy.epicsiegemod.config.EsmConfigGlobal"), "AIExempt");
      for (String id :
          new String[] {
            "minecraft:cow",
            "minecraft:pig",
            "minecraft:zombie",
            "srparasites:sim_human",
            "srparasites:pri_longarms",
            "srparasites:ada_longarms"
          })
        check(
            LegacySiege.configuredEntities().contains(id)
                && !exemptions.contains(Reflect.make("net.minecraft.util.ResourceLocation", id)),
            "explicit native ESM profile admitted: " + id);
      for (String id :
          new String[] {"srparasites:buglin", "srparasites:anc_overlord", "srparasites:beckon_siv"})
        check(
            exemptions.contains(Reflect.make("net.minecraft.util.ResourceLocation", id)),
            "special SRP AI remains excluded: " + id);
      arena(false);
      Object probe = create("srparasites:sim_human", x + .5, y, z + .5, false);
      Object ai = Reflect.make(DIG, probe), pos = Reflect.pos(x + 2, y, z);
      Method can =
          ai.getClass()
              .getDeclaredMethod(
                  "canHarvest",
                  Reflect.type("net.minecraft.entity.EntityLiving"),
                  Reflect.type("net.minecraft.util.math.BlockPos"));
      can.setAccessible(true);
      put(pos, "minecraft:dirt");
      rows.add(ForgeSiegeProbe.verify(world, probe, pos, ai, can));
      check(
          (Boolean) can.invoke(ai, probe, pos),
          "transformed native canHarvest accepts ordinary dirt");
      put(pos, "minecraft:chest");
      check(
          !(Boolean) can.invoke(ai, probe, pos),
          "native cached dirt-to-container replacement refused");
      put(pos, "hbm:block_steel");
      check(!(Boolean) can.invoke(ai, probe, pos), "native cached dirt-to-HBM replacement refused");
      put(pos, "minecraft:bedrock");
      check(
          !(Boolean) can.invoke(ai, probe, pos),
          "native cached dirt-to-bedrock replacement refused");
      put(pos, "minecraft:dirt");
      Object rules = Reflect.call(world, "getGameRules/func_82736_K");
      Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "mobGriefing", "false");
      check(!(Boolean) can.invoke(ai, probe, pos), "native digging obeys actual mobGriefing rule");
      Reflect.call(rules, "setOrCreateGameRule/func_82764_b", "mobGriefing", "true");
      Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
      Object protectedPos = Reflect.pos(Reflect.x(spawn), Reflect.y(spawn), Reflect.z(spawn));
      check(runtime.protectedAt(world, protectedPos), "fresh native world grace active");
      check(
          !(Boolean) can.invoke(ai, probe, protectedPos),
          "native dig admission cannot target protected bunker");
      globalMobSight();
      rows.add(
          JsonFiles.map(
              "case",
              "native_cached_target_guards",
              "passed",
              true,
              "ordinaryDirt",
              true,
              "container",
              false,
              "hbm",
              false,
              "bedrock",
              false,
              "mobGriefing",
              true,
              "grace",
              true));
    }

    private void globalMobSight() {
      arena(false);
      for (int dy = 0; dy < 3; dy++)
        for (int dz = -1; dz <= 1; dz++) put(Reflect.pos(x + 2, y + dy, z + dz), "hbm:block_steel");
      position(player, x + 40.5, y, z + .5);
      List<String> observed = new ArrayList<String>();
      for (String id :
          new String[] {
            "minecraft:skeleton", "minecraft:bat", "srparasites:buglin", "minecraft:cow"
          }) {
        Object mob = create(id, x + .5, y, z + .5, true);
        check(
            (Boolean) Reflect.call(mob, "canEntityBeSeen/func_70685_l", player),
            "real mob sees player through opaque HBM at40 blocks: " + id);
        observed.add(id);
      }
      Object mob = actors.get(0), caps = Reflect.get(player, "capabilities/field_71075_bZ");
      boolean creative = (Boolean) Reflect.get(caps, "isCreativeMode/field_75098_d");
      try {
        Reflect.set(caps, "isCreativeMode/field_75098_d", true);
        check(
            !(Boolean) Reflect.call(mob, "canEntityBeSeen/func_70685_l", player),
            "global xray obeys default creative-player visual policy");
      } finally {
        Reflect.set(caps, "isCreativeMode/field_75098_d", creative);
      }
      Object provider = Reflect.call(world, "getChunkProvider/func_72863_F");
      int loaded = ((Collection<?>) Reflect.call(provider, "getLoadedChunks/func_189548_a")).size();
      position(player, x + 10000.5, y, z + .5);
      check(
          !(Boolean) Reflect.call(mob, "canEntityBeSeen/func_70685_l", player),
          "unloaded/out-of-radius player cannot be seen");
      check(
          ((Collection<?>) Reflect.call(provider, "getLoadedChunks/func_189548_a")).size()
              == loaded,
          "global xray never force-loads target chunks");
      rows.add(
          JsonFiles.map(
              "case",
              "global_mob_xray",
              "passed",
              true,
              "nativeObservers",
              observed,
              "opaqueHbmWall",
              true,
              "distance",
              40,
              "configuredRadius",
              64,
              "coversEsmBlacklistedMobs",
              true,
              "nativeFollowRangeIncreased",
              true,
              "creativeExcluded",
              true,
              "unloadedTargetDoesNotLoad",
              true));
      clearActors();
    }

    private void setupAnimals() {
      clearActors();
      arena(false);
      stage = 0;
      stageTicks = 0;
      provoked = false;
      observedGroup = false;
      position(player, x + 5.5, y, z + .5);
      cow = create("minecraft:cow", x + .5, y, z + .5, true);
      herd = create("minecraft:cow", x + 1.5, y, z + 1.5, true);
      otherSpecies = create("minecraft:pig", x + .5, y, z - 2.5, true);
      check(
          task(cow, "tasks/field_70714_bg", "funwayguy.epicsiegemod.ai.ESM_EntityAIAttackMelee")
              != null,
          "real cow has native ESM retaliation attack");
      check(
          task(cow, "targetTasks/field_70715_bh", "net.minecraft.entity.ai.EntityAIHurtByTarget")
              != null,
          "real cow has native group hurt-by target task");
      startX = number(cow, "posX/field_70165_t");
      startZ = number(cow, "posZ/field_70161_v");
      herdStartX = number(herd, "posX/field_70165_t");
      herdStartZ = number(herd, "posZ/field_70161_v");
      herdStartDistance =
          ((Number) Reflect.call(herd, "getDistanceSq/func_70068_e", player)).doubleValue();
      diagnose("animals_before_provocation");
    }

    private void animals() {
      if (!provoked) {
        check(
            target(cow) == null && target(herd) == null && target(otherSpecies) == null,
            "animals do not select player before provocation");
        if (stageTicks < 20) return;
        check(
            ((Number) Reflect.get(cow, "ticksExisted/field_70173_aa")).intValue() >= 15,
            "fixture cow receives real ordinary native ticks before provocation");
        Object source =
            Reflect.call(
                Reflect.type("net.minecraft.util.DamageSource"),
                "causePlayerDamage/func_76365_a",
                player);
        check(
            (Boolean) Reflect.call(cow, "attackEntityFrom/func_70097_a", source, 1f),
            "actual player damage provokes cow");
        provoked = true;
        diagnose("animals_after_actual_player_hit");
        return;
      }
      observedGroup |= target(cow) == player && target(herd) == player;
      if (stageTicks == 21
          || stageTicks == 30
          || stageTicks == 40
          || stageTicks == 80
          || stageTicks == 160) diagnose("animals_tick_" + stageTicks);
      check(target(otherSpecies) != player, "retaliation stays within same-species group");
      if (observedGroup && value(committedDamage, herd) > 0) {
        diagnose("animals_real_group_melee_committed");
        check(
            moved(herd, herdStartX, herdStartZ) > .25
                && ((Number) Reflect.call(herd, "getDistanceSq/func_70068_e", player)).doubleValue()
                    < herdStartDistance,
            "unhit same-species responder physically approached and struck the player");
        rows.add(
            JsonFiles.map(
                "case",
                "cow_group_retaliation",
                "passed",
                true,
                "normalTicks",
                stageTicks,
                "unprovokedTargets",
                0,
                "sameSpeciesGroup",
                true,
                "otherSpeciesNeutral",
                true,
                "unhitResponderMoved",
                moved(herd, herdStartX, herdStartZ),
                "unhitResponderHpLost",
                value(committedDamage, herd),
                "actualPlayerHpLost",
                value(committedDamage, cow) + value(committedDamage, herd)));
        chaseIndex = 0;
        setupBreach();
        return;
      }
      check(
          stageTicks < 180,
          "native group retaliation and physical attack finish within 180 real ticks");
    }

    private void setupChase() {
      clearActors();
      arena(false);
      stage = 1;
      stageTicks = 0;
      position(player, x + 7.5, y, z + .5);
      actor = create(CHASERS[chaseIndex], x + .5, y, z + .5, true);
      nativeDig = task(actor, "tasks/field_70714_bg", DIG);
      check(
          nativeDig != null,
          "spawned parasite has native ESM digging task: " + CHASERS[chaseIndex]);
      check(
          ((Number) Reflect.call(nativeDig, "getMutexBits/func_75247_h")).intValue() == 0,
          "native digging does not reserve SRP melee movement mutex");
      check(target(actor) == null, "native SRP chase starts without injected target");
      startX = number(actor, "posX/field_70165_t");
      startZ = number(actor, "posZ/field_70161_v");
    }

    private void chase() {
      if (value(committedDamage, actor) > 0) {
        check(
            moved(actor, startX, startZ) > 2,
            "native parasite physically pursued reachable target");
        rows.add(
            JsonFiles.map(
                "case",
                "native_srp_chase_and_attack",
                "entity",
                CHASERS[chaseIndex],
                "passed",
                true,
                "normalTicks",
                stageTicks,
                "distanceMoved",
                moved(actor, startX, startZ),
                "actualPlayerHpLost",
                value(committedDamage, actor),
                "nativeCustomTasksPreserved",
                true));
        if (++chaseIndex < CHASERS.length) setupChase();
        else finish();
        return;
      }
      check(
          stageTicks < 220,
          "native SRP pursuit and attack complete within 220 real ticks: " + CHASERS[chaseIndex]);
    }

    private void setupBreach() {
      clearActors();
      arena(true);
      stage = 2;
      stageTicks = 0;
      observedDigProgress = false;
      observedAcquisition = false;
      crossedBreach = false;
      position(player, x + 6.5, y, z + .5);
      actor = create("srparasites:sim_human", x + .5, y, z + .5, true);
      nativeDig = task(actor, "tasks/field_70714_bg", DIG);
      check(nativeDig != null, "live corridor parasite retains real native ESM digging task");
      check(target(actor) == null, "opaque acquisition starts without injected target");
      check(opaqueBarrier() == 3, "opaque physical dirt barrier blocks the full corridor");
    }

    private void breach() {
      observedAcquisition |= target(actor) == player;
      Object cur = Reflect.get(nativeDig, "curBlock");
      int progress = ((Number) Reflect.get(nativeDig, "digTick")).intValue();
      if (cur != null && Reflect.blockId(world, cur).equals("minecraft:dirt") && progress > 0)
        observedDigProgress = true;
      crossedBreach |= number(actor, "posX/field_70165_t") > x + 3;
      if (observedAcquisition
          && observedDigProgress
          && opaqueBarrier() < 3
          && crossedBreach
          && value(committedDamage, actor) > 0) {
        rows.add(
            JsonFiles.map(
                "case",
                "native_opaque_xray_acquisition_breach_attack",
                "entity",
                "srparasites:sim_human",
                "passed",
                true,
                "normalTicks",
                stageTicks,
                "opaqueWallTargetAcquiredNaturally",
                true,
                "injectedTarget",
                false,
                "nativeTaskSelectedBlock",
                true,
                "nativeTaskAdvancedDig",
                true,
                "remainingBarrierBlocks",
                opaqueBarrier(),
                "physicallyCrossed",
                true,
                "actualPlayerHpLost",
                value(committedDamage, actor)));
        finiteDefense();
        setupChase();
        return;
      }
      check(
          stageTicks < 420,
          "native SRP/ESM naturally acquire through opaque blocks, select, dig, cross and attack"
              + " within 420 real ticks; acquired="
              + observedAcquisition
              + ", selected="
              + observedDigProgress
              + ", remaining="
              + opaqueBarrier()
              + ", crossed="
              + crossedBreach
              + ", damage="
              + value(committedDamage, actor));
    }

    private void finiteDefense() {
      Object pos = Reflect.pos(x + 10, y, z + 3);
      String wall = LegacyDefense.id("wall", 3);
      put(pos, wall);
      position(actor, x + 10.5, y, z + 2.5);
      // Existing native defense test proves the native completion path. Here the actor is an actual
      // spawned SRP and the task is its live ESM instance, after a fully natural corridor breach.
      Reflect.set(nativeDig, "target", player);
      Reflect.set(nativeDig, "curBlock", pos);
      Reflect.set(nativeDig, "digTick", 100000);
      Reflect.call(nativeDig, "updateTask/func_75246_d");
      LegacyDefense.flush(runtime);
      check(
          Reflect.blockId(world, pos).equals(wall) && LegacyDefense.remaining(world, pos) == 7900,
          "live parasite ESM cannot bypass V3 finite HP");
      rows.add(
          JsonFiles.map(
              "case",
              "live_srp_v3_completion_guard",
              "passed",
              true,
              "remainingHp",
              7900,
              "forcedCompletionOnlyForDefenseBoundary",
              true));
    }

    private Object create(String id, double px, double py, double pz, boolean spawn) {
      Object entity =
          Reflect.call(
              Reflect.type("net.minecraft.entity.EntityList"),
              "createEntityByIDFromName/func_188429_b",
              Reflect.make("net.minecraft.util.ResourceLocation", id),
              world);
      check(entity != null, "native entity exists: " + id);
      position(entity, px, py, pz);
      Reflect.set(entity, "onGround/field_70122_E", true);
      Reflect.call(entity, "enablePersistence/func_110163_bv");
      Set<String> nativeTasks = customTasks(entity);
      String navigator = Reflect.call(entity, "getNavigator/func_70661_as").getClass().getName();
      if (spawn) {
        check(
            (Boolean) Reflect.call(world, "spawnEntity/func_72838_d", entity),
            "actual native entity joins world: " + id);
        actors.add(entity);
        boolean configured = LegacySiege.configuredEntities().contains(id);
        check(
            Reflect.get(entity, "tasks/field_70714_bg")
                    .getClass()
                    .getName()
                    .equals("funwayguy.epicsiegemod.ai.hooks.EntityAITasksProxy")
                == configured,
            "native ESM replacement stays scoped while sight is global: " + id);
        Object follow =
            Reflect.call(
                entity,
                "getEntityAttribute/func_110148_a",
                Reflect.get(
                    Reflect.type("net.minecraft.entity.SharedMonsterAttributes"),
                    "FOLLOW_RANGE/field_111265_b"));
        check(
            ((Number) Reflect.call(follow, "getAttributeValue/func_111126_e")).doubleValue() >= 64,
            "global native FOLLOW_RANGE is increased: " + id);
        if (id.startsWith("srparasites:")) {
          check(
              !nativeTasks.isEmpty() && customTasks(entity).containsAll(nativeTasks),
              "SRP native custom combat/target tasks remain: " + id);
          if (!navigator.equals("net.minecraft.pathfinding.PathNavigateGround"))
            check(
                Reflect.call(entity, "getNavigator/func_70661_as")
                    .getClass()
                    .getName()
                    .equals(navigator),
                "SRP custom navigator remains exact: " + id);
        }
      }
      return entity;
    }

    private void arena(boolean corridor) {
      for (int dx = -4; dx <= 12; dx++)
        for (int dz = -4; dz <= 4; dz++) {
          put(Reflect.pos(x + dx, y - 1, z + dz), "minecraft:obsidian");
          for (int dy = 0; dy <= 4; dy++) put(Reflect.pos(x + dx, y + dy, z + dz), "minecraft:air");
        }
      if (corridor) {
        for (int dx = -1; dx <= 8; dx++)
          for (int dy = 0; dy <= 3; dy++)
            for (int dz = -1; dz <= 1; dz++)
              if (dx == -1 || dx == 8 || dz == -1 || dz == 1 || dy == 3)
                put(Reflect.pos(x + dx, y + dy, z + dz), "minecraft:obsidian");
        for (int dy = 0; dy < 3; dy++) put(Reflect.pos(x + 2, y + dy, z), "minecraft:dirt");
      }
    }

    private int opaqueBarrier() {
      int count = 0;
      for (int dy = 0; dy < 3; dy++)
        if (Reflect.blockId(world, Reflect.pos(x + 2, y + dy, z)).equals("minecraft:dirt")) count++;
      return count;
    }

    private Object target(Object entity) {
      return Reflect.call(entity, "getAttackTarget/func_70638_az");
    }

    private double health() {
      return ((Number) Reflect.call(player, "getHealth/func_110143_aJ")).doubleValue();
    }

    private void put(Object pos, String id) {
      check(Reflect.loaded(world, pos), "native AI arena never forces chunk load");
      Object block = Reflect.registry("BLOCKS", id);
      check(
          block != null && Reflect.call(block, "getRegistryName").toString().equals(id),
          "native fixture block exists: " + id);
      Reflect.call(
          world,
          "setBlockState/func_180501_a",
          pos,
          Reflect.call(block, "getDefaultState/func_176223_P"),
          2);
    }

    private void clearActors() {
      for (Object entity : actors) {
        Reflect.call(entity, "setDead/func_70106_y");
        Reflect.call(world, "removeEntity/func_72900_e", entity);
      }
      actors.clear();
      committedDamage.clear();
      tickDamage.clear();
    }

    private void diagnose(String reason) {
      if (diagnostics.size() >= 32) return;
      Object generic =
          Reflect.get(Reflect.type("net.minecraft.util.DamageSource"), "GENERIC/field_76377_j");
      Object caps = Reflect.get(player, "capabilities/field_71075_bZ");
      diagnostics.add(
          JsonFiles.map(
              "reason",
              reason,
              "stage",
              stage,
              "stageTicks",
              stageTicks,
              "observedGroup",
              observedGroup,
              "player",
              JsonFiles.map(
                  "class",
                  player.getClass().getName(),
                  "health",
                  health(),
                  "invulnerable",
                  Reflect.call(player, "isEntityInvulnerable/func_180431_b", generic),
                  "disableDamage",
                  Reflect.get(caps, "disableDamage/field_75102_a"),
                  "creative",
                  Reflect.get(caps, "isCreativeMode/field_75098_d"),
                  "spectator",
                  Reflect.call(player, "isSpectator/func_175149_v"),
                  "position",
                  coordinates(player),
                  "inWorldPlayers",
                  worldPlayers.contains(player)),
              "cow",
              describe(cow),
              "herd",
              describe(herd),
              "otherSpecies",
              describe(otherSpecies),
              "actor",
              describe(actor)));
    }

    private Object describe(Object entity) {
      if (entity == null) return null;
      Object revenge = Reflect.call(entity, "getRevengeTarget/func_70643_av");
      Object aim = target(entity);
      Object navigator = Reflect.call(entity, "getNavigator/func_70661_as");
      return JsonFiles.map(
          "type",
          Reflect.entityId(entity),
          "class",
          entity.getClass().getName(),
          "ticksExisted",
          Reflect.get(entity, "ticksExisted/field_70173_aa"),
          "isDead",
          Reflect.get(entity, "isDead/field_70128_L"),
          "inLoadedEntityList",
          ((Collection<?>) Reflect.get(world, "loadedEntityList/field_72996_f")).contains(entity),
          "position",
          coordinates(entity),
          "onGround",
          Reflect.get(entity, "onGround/field_70122_E"),
          "health",
          Reflect.call(entity, "getHealth/func_110143_aJ"),
          "target",
          label(aim),
          "revengeTarget",
          label(revenge),
          "revengeTimer",
          Reflect.call(entity, "getRevengeTimer/func_142015_aE"),
          "distanceSqToPlayer",
          Reflect.call(entity, "getDistanceSq/func_70068_e", player),
          "committedPlayerDamage",
          value(committedDamage, entity),
          "noPath",
          Reflect.call(navigator, "noPath/func_75500_f"),
          "tasks",
          describeTasks(entity, "tasks/field_70714_bg"),
          "targetTasks",
          describeTasks(entity, "targetTasks/field_70715_bh"));
    }

    private List<Map<String, Object>> describeTasks(Object entity, String field) {
      Object scheduler = Reflect.get(entity, field);
      Collection<?> running =
          (Collection<?>) Reflect.get(scheduler, "executingTaskEntries/field_75780_b");
      List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
      for (Object entry : (Collection<?>) Reflect.get(scheduler, "taskEntries/field_75782_a")) {
        Object action = Reflect.get(entry, "action/field_75733_a");
        values.add(
            JsonFiles.map(
                "class",
                action.getClass().getName(),
                "priority",
                Reflect.get(entry, "priority/field_75731_b"),
                "mutex",
                Reflect.call(action, "getMutexBits/func_75247_h"),
                "executing",
                running.contains(entry)));
      }
      return values;
    }

    private String label(Object entity) {
      return entity == null
          ? "none"
          : entity == player ? "combat_player" : Reflect.entityId(entity);
    }

    private List<Double> coordinates(Object entity) {
      return java.util.Arrays.asList(
          number(entity, "posX/field_70165_t"),
          number(entity, "posY/field_70163_u"),
          number(entity, "posZ/field_70161_v"));
    }

    private void check(boolean condition, String message) {
      assertions++;
      if (!condition) throw new AssertionError(message);
    }

    private void fail(Throwable failure) {
      try {
        diagnose("failure_stage_" + stage);
      } catch (Throwable diagnosticFailure) {
        diagnostics.add(JsonFiles.map("diagnosticError", diagnosticFailure.toString()));
      }
      error = failure.toString();
      failure.printStackTrace();
      finish();
    }

    private void finish() {
      if (done) return;
      done = true;
      MinecraftForge.EVENT_BUS.unregister(this);
      clearActors();
      Reflect.call(healthAttribute, "setBaseValue/func_111128_a", oldMaxHealth);
      Reflect.call(player, "setHealth/func_70606_j", oldHealth);
      Reflect.set(player, "hurtResistantTime/field_70172_ad", oldHurtTime);
      Reflect.set(
          Reflect.get(player, "capabilities/field_71075_bZ"),
          "disableDamage/field_75102_a",
          oldInvulnerable);
      Reflect.call(
          Reflect.call(world, "getGameRules/func_82736_K"),
          "setOrCreateGameRule/func_82764_b",
          "mobGriefing",
          oldMobGriefing);
      Reflect.call(
          Reflect.call(world, "getGameRules/func_82736_K"),
          "setOrCreateGameRule/func_82764_b",
          "doMobSpawning",
          oldMobSpawning);
      Reflect.call(runtime.server, "setCanSpawnAnimals/func_71251_e", oldAnimalsAllowed);
      position(player, oldX, oldY, oldZ);
      if (addedWorldPlayer) worldPlayers.remove(player);
      if (originalPlayerPresent && !worldPlayers.contains(originalPlayer))
        worldPlayers.add(originalPlayer);
      if (previousConnection == null) Reflect.set(player, "connection/field_71135_a", null);
    }
  }

  private static Object combatPlayer(LegacyRuntime runtime, Object world) {
    if (!Boolean.getBoolean("deadearth.testControls")
        || !Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test")))
      throw new IllegalStateException("Isolated combat player controls required");
    Object profile =
        Reflect.make(
            "com.mojang.authlib.GameProfile", java.util.UUID.randomUUID(), "DeadEarthCombat");
    Object manager =
        Reflect.make("net.minecraft.server.management.PlayerInteractionManager", world);
    Object player =
        Reflect.make(
            "net.minecraft.entity.player.EntityPlayerMP", runtime.server, world, profile, manager);
    Reflect.call(
        manager,
        "setGameType/func_73076_a",
        Reflect.get(Reflect.type("net.minecraft.world.GameType"), "SURVIVAL"));
    // This unconnected test player is not ticked by PlayerList; remove only initial login
    // shielding.
    Reflect.set(player, "respawnInvulnerabilityTicks/field_147101_bU", 0);
    Reflect.set(player, "invulnerableDimensionChange/field_184851_cj", false);
    return player;
  }

  private static Set<String> customTasks(Object entity) {
    Set<String> result = new LinkedHashSet<String>();
    for (String field : new String[] {"tasks/field_70714_bg", "targetTasks/field_70715_bh"})
      for (Object row :
          (Collection<?>) Reflect.get(Reflect.get(entity, field), "taskEntries/field_75782_a")) {
        String name = Reflect.get(row, "action/field_75733_a").getClass().getName();
        if (name.startsWith("com.dhanantry.scapeandrunparasites.")) result.add(name);
      }
    return result;
  }

  private static Object task(Object entity, String field, String type) {
    for (Object row :
        (Collection<?>) Reflect.get(Reflect.get(entity, field), "taskEntries/field_75782_a")) {
      Object action = Reflect.get(row, "action/field_75733_a");
      if (action.getClass().getName().equals(type)) return action;
    }
    return null;
  }

  private static void position(Object entity, double x, double y, double z) {
    Reflect.call(entity, "setPosition/func_70107_b", x, y, z);
  }

  private static double number(Object entity, String field) {
    return ((Number) Reflect.get(entity, field)).doubleValue();
  }

  private static double value(Map<Object, Double> map, Object key) {
    Double value = map.get(key);
    return value == null ? 0 : value;
  }

  private static double moved(Object entity, double x, double z) {
    double dx = number(entity, "posX/field_70165_t") - x,
        dz = number(entity, "posZ/field_70161_v") - z;
    return Math.sqrt(dx * dx + dz * dz);
  }
}
