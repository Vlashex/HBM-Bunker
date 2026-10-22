package dev.deadearth.legacyprobe;

import com.google.gson.JsonObject;
import dev.deadearth.legacy.Campaign;
import dev.deadearth.legacy.DeadEarthLegacy;
import dev.deadearth.legacy.InventoryValuation;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.LegacyRuntime;
import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.gameplay.HbmToolApi;
import dev.deadearth.legacy.kernel.CinematicCatalog;
import dev.deadearth.legacy.kernel.CinematicEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;

/** Runs only in a fresh, explicitly enabled native integration server. */
@Mod(
    modid = "deadearth_port_probe",
    name = "Dead Earth SRP Native Port Probe",
    version = "1.0.0",
    dependencies =
        "required-after:dead_earth;required-after:srparasites;required-after:epicsiegemod",
    acceptableRemoteVersions = "*")
public final class PortProbe {
  private final List<Map<String, Object>> checks = new ArrayList<Map<String, Object>>();
  private final Map<String, Object> report = new LinkedHashMap<String, Object>();
  private LegacyRuntime runtime;
  private Object world, player;
  private List<Object> playerList;
  private int baseX, baseY, baseZ;
  private int failures, fullGroupCount, fullAssertionCount;
  private final Map<String, Object> fullGroups = new LinkedHashMap<String, Object>();

  private dev.deadearth.legacyprobe.sieving.CompactSieveGameplayProbe.Session sieveGameplay;
  private dev.deadearth.legacyprobe.combat.SiegeProbe.Session siegeGameplay;
  private dev.deadearth.legacyprobe.combat.RelaySiegeProbe.Session relayGameplay;
  private boolean nativeFinished;
  private int nativeWarmupRemaining;
  private boolean nativeStarted;
  private Throwable starterPreparationFailure;
  private Object originalStarterBlocked, originalStarterSentinel;

  @Mod.EventHandler
  public void started(FMLServerStartedEvent event) {
    net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    nativeWarmupRemaining = 40;
    try {
      if (!Boolean.getBoolean("deadearth.testControls")
          || !Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test")))
        throw new IllegalStateException("Isolated native controls are absent");
      runtime = DeadEarthLegacy.runtime;
      if (runtime == null)
        throw new IllegalStateException("Coordinator did not start before probe");
      runtime.requireThread();
      world = runtime.world.world(0);
      Object initialSpawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
      originalStarterBlocked =
          Reflect.call(world, "getTopSolidOrLiquidBlock/func_175672_r", initialSpawn);
      placeAt(originalStarterBlocked, "minecraft:chest");
      originalStarterSentinel =
          Reflect.call(world, "getTileEntity/func_175625_s", originalStarterBlocked);
      Reflect.call(
          originalStarterSentinel,
          "setInventorySlotContents/func_70299_a",
          0,
          itemStack("minecraft:gold_ingot", 0));
    } catch (Throwable failed) {
      starterPreparationFailure = failed;
    }
  }

  @net.minecraftforge.fml.common.eventhandler.SubscribeEvent(
      priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)
  public void nativeWarmupTick(
      net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent event) {
    if (event.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END
        || nativeFinished) return;
    if (siegeGameplay != null) {
      if (siegeGameplay.poll()) {
        runFull(
            "native_epic_siege_live_gameplay",
            () -> {
              Map<String, Object> result = siegeGameplay.result();
              fullGroups.put("siege", result);
              require(
                  Boolean.TRUE.equals(result.get("passed")),
                  "actual native ESM/SRP acquisition, movement, retaliation and digging: "
                      + result.get("error"));
            });
        siegeGameplay = null;
        runFull("native_relay_gameplay_fixture", () -> startRelayGameplay());
        if (relayGameplay == null) {
          runFull("native_sieve_gameplay_fixture", () -> startSieveGameplay());
          if (sieveGameplay == null) finishNative();
        }
      }
      return;
    }
    if (relayGameplay != null) {
      if (relayGameplay.poll()) {
        runFull(
            "native_relay_objective_live_gameplay",
            () -> {
              Map<String, Object> result = relayGameplay.result();
              fullGroups.put("relaySiege", result);
              require(
                  Boolean.TRUE.equals(result.get("passed")),
                  "actual relay acquisition, terrain breach, finite HP and guards: "
                      + result.get("error"));
            });
        relayGameplay = null;
        runFull("native_sieve_gameplay_fixture", () -> startSieveGameplay());
        if (sieveGameplay == null) finishNative();
      }
      return;
    }
    if (sieveGameplay != null) {
      if (sieveGameplay.poll()) {
        runFull(
            "native_manual_batch_sieve_gameplay",
            () -> {
              Map<String, Object> result = sieveGameplay.result();
              fullGroups.put("compactSieveGameplay", result);
              require(
                  Boolean.TRUE.equals(result.get("passed")),
                  "actual equipment insertion and normal server ticks: " + result.get("error"));
            });
        sieveGameplay = null;
        finishNative();
      }
      return;
    }
    if (nativeStarted || nativeWarmupRemaining <= 0) return;
    if (--nativeWarmupRemaining > 0) return;
    nativeStarted = true;
    runNative();
  }

  private void runNative() {
    report.put("ordinaryWarmupTicks", 40);
    report.put("schema", 2);
    report.put("probe", "native_full_srp_forge_1_12_2");
    report.put("productVersion", "1.1.1");
    report.put("terrain", "DEFAULT");
    report.put("clientVisualsTested", false);
    report.put("realAiInferenceTested", false);
    try {
      if (!Boolean.getBoolean("deadearth.testControls"))
        throw new IllegalStateException("Test controls are not enabled");
      if (!Files.exists(Paths.get(".dead-earth-isolated-native-test")))
        throw new IllegalStateException("Isolated test marker is absent");
      runtime = DeadEarthLegacy.runtime;
      if (runtime == null)
        throw new IllegalStateException("Coordinator did not start before probe");
      runtime.requireThread();
      world = runtime.world.world(0);
      if (starterPreparationFailure != null)
        throw new IllegalStateException(
            "Original spawn sentinel preparation failed", starterPreparationFailure);
      run("native_dependencies", () -> dependencies());
      run("no_player_pause_and_native_lock", () -> noPlayers());
      runFull("starter_shelter_once_per_world", () -> starterShelter());
      run("transformed_world_write_guard", () -> writeGuard());
      run("survival_player_advances_active_clock", () -> survivalPlayer());
      run("phase_zero_to_ten_CAS_round_trip", () -> phaseRoundTrip());
      run("loaded_relays_six_tickets_no_overlap", () -> anchors());
      run("two_chests_and_unopened_loot", () -> valuation());
      run("native_health_scaling_is_idempotent", () -> health());
      run("phase_gated_native_wave_emission", () -> wave());
      run("typed_queue_replay_and_native_effect", () -> queue());
      run(
          "durable_campaign_checkpoint",
          () -> {
            runtime.checkpoint();
            require(Files.size(runtime.savePath) > 0, "campaign checkpoint exists");
            Campaign loaded = Campaign.open(runtime.savePath);
            require(
                loaded.campaignId.equals(runtime.campaign.campaignId),
                "campaign restores same identity");
            CinematicEngine.restore(loaded.cinematic);
          });
      runFull(
          "normal_terrain_wasteland",
          () -> {
            fullGroups.put(
                "worldgen",
                dev.deadearth.legacyprobe.worldgen.WastelandProbe.verify(
                    world, (baseX >> 4) + 128, (baseZ >> 4) + 128));
          });
      runFull(
          "native_full_progression_and_raw_emc",
          () -> {
            dev.deadearth.legacyprobe.progression.ProgressionProbe.run(runtime, world, player);
            fullGroups.put(
                "progression",
                JsonFiles.map(
                    "nativeRecipes",
                    true,
                    "previousBookNbtRequired",
                    true,
                    "rawOnlyEmc",
                    dev.deadearth.legacy.progression.LegacyProgression.rawOnly(),
                    "sieveProviderRuns",
                    dev.deadearth.legacy.progression.LegacyProgression.sieveProviderRuns()));
          });
      runFull(
          "hbm_material_canonicalization",
          () -> {
            fullGroups.put(
                "materials",
                dev.deadearth.legacyprobe.materials.MaterialsProbe.run(runtime, world, player));
          });
      runFull(
          "native_connected_sieves_5x5",
          () -> {
            int x = baseX + 40, z = baseZ + 80, y = fixtureY(x, z);
            fullGroups.put(
                "connectedSieves",
                dev.deadearth.legacyprobe.progression.GroupSieveProbe.run(world, player, x, y, z));
          });
      runFull(
          "native_compact_sieve_conservation_and_reload",
          () -> {
            int x = baseX + 40, z = baseZ + 96, y = fixtureY(x, z);
            fullGroups.put(
                "compactSieves",
                dev.deadearth.legacyprobe.sieving.SievingProbe.run(
                    runtime, world, player, x, y, z));
          });
      runFull("native_hbm_tools_and_high_anvil", () -> tools());
      runFull("native_hammer_walking_tunnels", () -> {
        int x = baseX + 96, z = baseZ + 144;
        load(x, z, 2);
        try {
          fullGroups.put("hammerTunnels",
              dev.deadearth.legacyprobe.gameplay.HammerMiningProbe.run(
                  world, player, x, fixtureY(x, z) + 12, z));
        } catch (Throwable failure) {
          throw new RuntimeException("Native hammer tunnel check", failure);
        }
      });
      runFull("physical_ae2_cell_zone_valuation", () -> ae2Valuation());
      runFull("physical_native_crop_trays", () -> farming());
      runFull(
          "native_bonsai_tree_farm",
          () -> {
            int x = baseX + 56, z = baseZ + 128;
            load(x, z, 1);
            fullGroups.put(
                "bonsai",
                dev.deadearth.legacyprobe.farming.BonsaiProbe.run(
                    world, player, x, fixtureY(x, z), z));
          });
      runFull(
          "finite_defense_native_siege_explosion_players",
          () -> {
            int y = fixtureY(baseX + 40, baseZ + 40);
            dev.deadearth.legacyprobe.defense.DefenseProbe.run(
                runtime, world, player, baseX + 40, y, baseZ + 40);
            fullGroups.put(
                "defense",
                JsonFiles.map(
                    "tiers",
                    3,
                    "doorSharedHp",
                    true,
                    "realEsmDig",
                    true,
                    "playerDrops",
                    true,
                    "realOrdinaryExplosion",
                    true));
          });
      runFull("physical_charger_and_fortune_processor", () -> machinery());
      runFull(
          "loaded_climate_top_side_thaw_and_quotas",
          () -> {
            int x = baseX + 40, z = baseZ + 64;
            load(x, z, 1);
            int y = fixtureY(x, z);
            fullGroups.put(
                "climate",
                dev.deadearth.legacy.climate.ClimateNativeChecks.verify(
                    runtime, world, player, x, y, z));
          });
      runFull("native_climate_clock_and_packet_codec", () -> climateClockAndPacket());
      runFull("native_crafttweaker_recipe_errors", () -> recipeLog());
      runFull(
          "durable_full_campaign",
          () -> {
            runtime.checkpoint();
            Campaign persisted = Campaign.open(runtime.savePath);
            require(
                persisted.campaignId.equals(runtime.campaign.campaignId)
                    && persisted.starterState.equals("DELIVERED"),
                "full campaign and shelter receipt persist");
          });
      report.put("activeTicks", runtime.campaign.activeTicks);
      report.put("phase", runtime.campaign.phase);
      report.put("nativePhase", runtime.srp.phase(world));
      report.put("zoneCount", runtime.campaign.zones.size());
      report.put("receiptCount", runtime.campaign.receipts.size());
      int siegeX = baseX + 96, siegeZ = baseZ + 112;
      load(siegeX, siegeZ, 3);
      int siegeY = Math.max(180, fixtureY(siegeX, siegeZ) + 8);
      siegeGameplay =
          dev.deadearth.legacyprobe.combat.SiegeProbe.begin(
              runtime, world, player, siegeX, siegeY, siegeZ);
    } catch (Throwable failed) {
      failures++;
      checks.add(result("probe_setup", false, failed));
      failed.printStackTrace();
    } finally {
      if (sieveGameplay == null && siegeGameplay == null && relayGameplay == null) finishNative();
    }
    // Normal ticking completes the gameplay session before the runner may stop this server.
  }

  private void startRelayGameplay() {
    int x = baseX + 160, z = baseZ + 112;
    load(x, z, 7);
    int y = Math.max(180, fixtureY(x, z) + 8);
    relayGameplay =
        dev.deadearth.legacyprobe.combat.RelaySiegeProbe.begin(runtime, world, player, x, y, z);
  }

  private void startSieveGameplay() {
    int sieveX = baseX + 40, sieveZ = baseZ + 112;
    load(sieveX, sieveZ, 2);
    int sieveY = fixtureY(sieveX, sieveZ);
    for (int n = 0; n < 7; n++)
      sieveY =
          Math.max(
              sieveY,
              Reflect.y(
                      Reflect.call(
                          world,
                          "getTopSolidOrLiquidBlock/func_175672_r",
                          Reflect.pos(sieveX + n * 3, 64, sieveZ)))
                  + 8);
    require(sieveY < 230, "bounded seven-frame air fixture");
    sieveGameplay =
        dev.deadearth.legacyprobe.sieving.CompactSieveGameplayProbe.begin(
            runtime, world, sieveX, sieveY, sieveZ);
  }

  private void finishNative() {
    if (nativeFinished) return;
    nativeFinished = true;
    boolean retained =
        Boolean.getBoolean("deadearth.bridgeProbe")
            && failures == 0
            && playerList != null
            && player != null;
    if (retained) {
      try {
        // Isolated fixture reset: the completed native queue test
        // would otherwise cool down Zone A for 18000 active ticks.
        // No production reset endpoint or setter is exposed.
        ((List<?>) Reflect.get(runtime.engine, "history")).clear();
        Reflect.set(runtime.engine, "nextDecision", 0L);
        Reflect.set(runtime.engine, "recoveryUntil", 0L);
        Reflect.set(runtime.engine, "heavyUntil", 0L);
        Reflect.set(runtime.engine, "pressuresSinceRest", 0);
        Reflect.set(runtime.engine, "goal", null);
        runtime.checkpoint();
        Reflect.call(Reflect.get(runtime, "bridge"), "publish");
        report.put("bridgeFixtureReset", true);
      } catch (Throwable failed) {
        retained = false;
        failures++;
        checks.add(result("bridge_fixture_preparation", false, failed));
        failed.printStackTrace();
      }
    }
    if (!retained && playerList != null && player != null) playerList.remove(player);
    report.put("bridgePlayerRetained", retained);
    report.put("checks", checks);
    report.put("baselineAssertionCount", checks.size() - fullAssertionCount);
    report.put("fullGroupCount", fullGroupCount);
    report.put("fullAssertionCount", fullAssertionCount);
    report.put("fullGroups", fullGroups);
    report.put("failures", failures);
    report.put("passed", failures == 0);
    try {
      Path output = Paths.get("port-probe.json"), temporary = Paths.get("port-probe.json.tmp");
      Files.write(temporary, JsonFiles.JSON.toJson(report).getBytes(StandardCharsets.UTF_8));
      Files.move(
          temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      System.out.println(
          "DEAD EARTH native port probe finished: " + (failures == 0 ? "passed" : "failed"));
    } catch (Exception failed) {
      failed.printStackTrace();
    }
    // The runner requests a normal stop only after reading the complete report.
  }

  private boolean sentinelsCreated;

  @net.minecraftforge.fml.common.eventhandler.SubscribeEvent(
      priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)
  public void persistenceTick(
      net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent event) {
    if (sentinelsCreated
        || event.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END
        || !Files.isRegularFile(Paths.get("create-restart-sentinels.request"))) return;
    sentinelsCreated = true;
    Map<String, Object> answer = new LinkedHashMap<String, Object>();
    try {
      if (!Boolean.getBoolean("deadearth.testControls")
          || !Files.isRegularFile(Paths.get(".dead-earth-isolated-native-test"))
          || failures != 0)
        throw new IllegalStateException("Not a successful isolated native fixture");
      runtime.requireThread();
      if (playerList != null && player != null) playerList.remove(player);
      int x = runtime.campaign.zones.get(0).x - 8, z = runtime.campaign.zones.get(0).z - 8, y = 200;
      answer.put("sentinels", RestartSentinels.create(runtime, world, player, x, y, z));
      runtime.checkpoint();
      answer.put("passed", true);
    } catch (Throwable failure) {
      answer.put("passed", false);
      answer.put("error", failure.toString());
      failure.printStackTrace();
    }
    JsonFiles.write(Paths.get("restart-sentinels-created.json"), answer);
  }

  private void dependencies() {
    require(Loader.isModLoaded("dead_earth"), "coordinator loaded");
    try {
      JsonObject manifest = JsonFiles.read(Paths.get("full-native-expected-mods.json"), 131072);
      Map<String, String> versions = new LinkedHashMap<String, String>();
      Object indexed = Reflect.call(Reflect.call(Loader.class, "instance"), "getIndexedModList");
      for (com.google.gson.JsonElement row : manifest.getAsJsonArray("server_mods")) {
        String id = row.getAsJsonObject().get("mod_id").getAsString();
        require(Loader.isModLoaded(id), "full native dependency loaded: " + id);
        Object mod = ((Map<?, ?>) indexed).get(id);
        versions.put(id, String.valueOf(Reflect.call(mod, "getVersion")));
      }
      report.put("fullLockCount", manifest.get("full_lock_count").getAsInt());
      report.put("serverNativeCount", versions.size());
      report.put("nativeVersions", versions);
      report.put("clientOnlyNotInstalled", manifest.get("client_only"));
    } catch (Exception ex) {
      throw new IllegalStateException("Full native manifest", ex);
    }
    for (String excluded :
        new String[] {"securitycraft", "mutationcraft", "overgrown_vegetation", "tconstruct"})
      require(!Loader.isModLoaded(excluded), "excluded mod absent: " + excluded);
    require(
        Reflect.call(
                Reflect.call(
                    Reflect.call(world, "getWorldInfo/func_72912_H"),
                    "getTerrainType/func_76067_t"),
                "getName/func_77127_a")
            .toString()
            .equals("default"),
        "native ordinary terrain, no flat fixture");
    require(Loader.isModLoaded("srparasites"), "native SRP loaded");
    require(Loader.isModLoaded("epicsiegemod"), "native Epic Siege loaded");
    require(Loader.isModLoaded("hbm"), "native HBM loaded");
    require(Reflect.registry("BLOCKS", "dead_earth:zone_relay") != null, "own relay registered");
    Object exemptions =
        Reflect.get(Reflect.type("funwayguy.epicsiegemod.config.EsmConfigGlobal"), "AIExempt");
    Collection<?> excluded = (Collection<?>) exemptions;
    for (String id :
        new String[] {
          "minecraft:zombie",
          "minecraft:cow",
          "srparasites:sim_human",
          "srparasites:pri_longarms",
          "srparasites:ada_longarms"
        })
      require(
          dev.deadearth.legacy.combat.LegacySiege.configuredEntities().contains(id)
              && !excluded.contains(Reflect.make("net.minecraft.util.ResourceLocation", id)),
          "configured native Epic Siege entity admitted: " + id);
    for (String id :
        new String[] {"srparasites:buglin", "srparasites:anc_overlord", "srparasites:beckon_siv"})
      require(
          excluded.contains(Reflect.make("net.minecraft.util.ResourceLocation", id)),
          "special native parasite AI remains excluded: " + id);
    require(runtime.settings.maximumZones == 6, "six-anchor configuration");
  }

  private void noPlayers() {
    require(runtime.world.players().isEmpty(), "fresh test has no survival players");
    long ticks = runtime.campaign.activeTicks;
    double points = runtime.campaign.points;
    runtime.tick();
    runtime.advanceForTest(200, 0);
    require(
        runtime.campaign.activeTicks == ticks && runtime.campaign.points == points,
        "offline active clock and evolution pause");
    Object data = runtime.srp.data(world);
    int dim = Reflect.dimension(world);
    require(!(Boolean) Reflect.call(data, "getCanGain", dim), "native gain is locked");
    int before = ((Number) Reflect.call(data, "getTotalKills", dim)).intValue();
    Reflect.call(data, "setTotalKills", dim, 100, true, world, false);
    require(
        ((Number) Reflect.call(data, "getTotalKills", dim)).intValue() == before,
        "native kill gain cannot bypass lock");
  }

  private void writeGuard() {
    Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
    int x = Reflect.x(spawn), y = Reflect.y(spawn), z = Reflect.z(spawn);
    Object inside = Reflect.pos(x + 1, y + 1, z + 1),
        edge = Reflect.pos(x + 16, y + 32, z),
        outside = Reflect.pos(x + 17, y + 1, z + 1);
    load(x, z, 2);
    require(
        runtime.protectedAt(world, inside) && runtime.protectedAt(world, edge),
        "spawn grace includes 16 by 32 boundary");
    require(
        !runtime.protectedAt(world, outside)
            && !runtime.protectedAt(world, Reflect.pos(x, y + 33, z)),
        "spawn grace has finite boundary");
    Object original = Reflect.state(world, inside);
    placeAt(inside, "minecraft:stone");
    require(
        (Boolean)
            Reflect.call(world, "setBlockState/func_180501_a", inside, state("minecraft:glass"), 3),
        "player-side world mutation remains available");
    boolean denied =
        (Boolean)
            Reflect.call(
                Reflect.type("com.dhanantry.scapeandrunparasites.legacyprobe.NativeWriter"),
                "write",
                world,
                inside,
                state("minecraft:obsidian"));
    require(
        !denied && Reflect.blockId(world, inside).equals("minecraft:glass"),
        "actual World write blocks native parasite caller");
    placeAt(outside, "minecraft:air");
    require(
        (Boolean)
            Reflect.call(
                Reflect.type("com.dhanantry.scapeandrunparasites.legacyprobe.NativeWriter"),
                "write",
                world,
                outside,
                state("minecraft:stone")),
        "native writer allowed outside grace");
    Reflect.call(world, "setBlockState/func_180501_a", inside, original, 3);
  }

  @SuppressWarnings("unchecked")
  private void survivalPlayer() {
    Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
    Object profile =
        Reflect.make(
            "com.mojang.authlib.GameProfile",
            UUID.fromString("44a86b84-0d55-44de-b7af-e5986274846c"),
            "DeadEarthProbe");
    player =
        Reflect.call(
            Reflect.type("net.minecraftforge.common.util.FakePlayerFactory"),
            "get",
            world,
            profile);
    Object survival = Reflect.get(Reflect.type("net.minecraft.world.GameType"), "SURVIVAL");
    Reflect.call(
        Reflect.get(player, "interactionManager/field_71134_c"),
        "setGameType/func_73076_a",
        survival);
    Reflect.call(
        player,
        "setPosition/func_70107_b",
        Reflect.x(spawn) + .5,
        (double) Reflect.y(spawn) + 1,
        Reflect.z(spawn) + .5);
    Reflect.call(player, "setHealth/func_70606_j", 20f);
    playerList = (List<Object>) Reflect.get(world, "playerEntities/field_73010_i");
    if (!playerList.contains(player)) playerList.add(player);
    require(runtime.world.players().contains(player), "in-world fake player is survival");
    long ticks = runtime.campaign.activeTicks;
    double points = runtime.campaign.points;
    runtime.advanceForTest(200, 1);
    require(
        runtime.campaign.activeTicks == ticks + 200,
        "online active clock advances exact virtual ticks");
    require(
        runtime.campaign.points > points,
        "online survival pressure advances native-backed evolution points");
    require(
        runtime.srp.phase(world) == runtime.campaign.phase,
        "native phase synchronized after advancement");
  }

  private void phaseRoundTrip() {
    while (runtime.campaign.phase > 0) shift(-1);
    for (int phase = 1; phase <= 10; phase++) {
      shift(1);
      require(
          runtime.campaign.phase == phase && runtime.srp.phase(world) == phase,
          "native upward phase " + phase);
    }
    JsonObject status = asObject(runtime.command(new String[] {"evolution", "status"}));
    require(
        status.get("phaseMaximum").getAsInt() == 10 && status.get("phase").getAsInt() == 10,
        "operator status exposes phase ten");
    long revision = runtime.campaign.revision;
    JsonObject staleEpoch =
        shiftRequest(
            runtime.campaign.campaignId,
            UUID.randomUUID().toString(),
            revision,
            UUID.randomUUID().toString(),
            -1);
    require(
        !staleEpoch.get("accepted").getAsBoolean()
            && "stale_epoch".equals(staleEpoch.get("code").getAsString()),
        "stale epoch rejected");
    JsonObject staleCampaign =
        shiftRequest(
            UUID.randomUUID().toString(),
            runtime.campaign.worldEpoch,
            revision,
            UUID.randomUUID().toString(),
            -1);
    require(
        !staleCampaign.get("accepted").getAsBoolean()
            && "stale_campaign".equals(staleCampaign.get("code").getAsString()),
        "stale campaign rejected");
    JsonObject staleRevision =
        shiftRequest(
            runtime.campaign.campaignId,
            runtime.campaign.worldEpoch,
            revision - 1,
            UUID.randomUUID().toString(),
            -1);
    require(
        !staleRevision.get("accepted").getAsBoolean()
            && "stale_revision".equals(staleRevision.get("code").getAsString()),
        "stale revision rejected");
    require(
        runtime.campaign.revision == revision && runtime.campaign.phase == 10,
        "stale commands have no phase effect");
    for (int phase = 9; phase >= 0; phase--) {
      shift(-1);
      require(
          runtime.campaign.phase == phase && runtime.srp.phase(world) == phase,
          "native downward phase " + phase);
    }
  }

  private void shift(int delta) {
    long revision = runtime.campaign.revision;
    String request = UUID.randomUUID().toString();
    JsonObject first =
        shiftRequest(
            runtime.campaign.campaignId, runtime.campaign.worldEpoch, revision, request, delta);
    require(first.get("accepted").getAsBoolean(), "phase CAS accepted");
    JsonObject replay =
        shiftRequest(
            runtime.campaign.campaignId, runtime.campaign.worldEpoch, revision, request, delta);
    require(
        first.equals(replay) && runtime.campaign.revision == revision + 1,
        "phase receipt replay is idempotent");
    JsonObject conflict =
        shiftRequest(
            runtime.campaign.campaignId, runtime.campaign.worldEpoch, revision, request, -delta);
    require(
        !conflict.get("accepted").getAsBoolean() && runtime.campaign.revision == revision + 1,
        "phase receipt rejects conflicting payload");
  }

  private JsonObject shiftRequest(
      String campaign, String epoch, long revision, String request, int delta) {
    return asObject(
        runtime.command(
            new String[] {
              "evolution",
              "shift",
              campaign,
              epoch,
              Long.toString(revision),
              request,
              Integer.toString(delta)
            }));
  }

  private void anchors() {
    Object far = Reflect.pos(1000000, 64, 1000000);
    require(!Reflect.loaded(world, far), "unloaded anchor fixture");
    int before = runtime.campaign.zones.size();
    boolean accepted = runtime.anchor(world, far);
    if (accepted) {
      for (int i = runtime.campaign.zones.size() - 1; i >= before; i--)
        runtime.campaign.zones.remove(i);
    }
    require(
        !accepted && runtime.campaign.zones.size() == before,
        "unloaded anchor cannot allocate zone");
    Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
    baseX = Reflect.x(spawn) + 160;
    baseZ = Reflect.z(spawn) + 160;
    load(baseX, baseZ, 3);
    Object surface =
        Reflect.call(
            world, "getTopSolidOrLiquidBlock/func_175672_r", Reflect.pos(baseX, 64, baseZ));
    baseY = Math.max(4, Reflect.y(surface));
    for (int i = 0; i < 6; i++) {
      int x = baseX + i * 80;
      load(x, baseZ, 2);
      Object pos = Reflect.pos(x, baseY, baseZ);
      placeAt(Reflect.pos(x, baseY - 1, baseZ), "minecraft:stone");
      placeAt(pos, "dead_earth:zone_relay");
      require(runtime.anchor(world, pos), "physical relay accepted " + i);
      Object tile = Reflect.call(world, "getTileEntity/func_175625_s", pos);
      require(tile != null, "physical relay tile exists " + i);
      Reflect.call(tile, "update/func_73660_a");
      Object ticket = Reflect.get(tile, "ticket");
      require(ticket != null, "real Forge chunk ticket allocated " + i);
      require(
          ((Collection<?>) Reflect.call(ticket, "getChunkList")).size() == 9,
          "ticket forces exactly 3 by 3 chunks " + i);
      if (i == 0) {
        Object overlap = Reflect.pos(baseX + 16, baseY, baseZ);
        placeAt(overlap, "dead_earth:zone_relay");
        require(
            !runtime.anchor(world, overlap) && runtime.campaign.zones.size() == 1,
            "overlapping anchor rejected before reaching cap");
      }
    }
    require(runtime.campaign.zones.size() == 6, "six distinct physical zones");
    int x = baseX + 6 * 80;
    load(x, baseZ, 1);
    Object seventh = Reflect.pos(x, baseY, baseZ);
    placeAt(seventh, "dead_earth:zone_relay");
    require(
        !runtime.anchor(world, seventh) && runtime.campaign.zones.size() == 6,
        "seventh anchor cannot exceed cap");
    Object tile = Reflect.call(world, "getTileEntity/func_175625_s", seventh);
    Reflect.call(tile, "update/func_73660_a");
    require(Reflect.get(tile, "ticket") == null, "rejected anchor owns no chunk ticket");
    Reflect.call(player, "setPosition/func_70107_b", baseX + .5, (double) baseY, baseZ + .5);
  }

  private void valuation() {
    require(runtime.campaign.zones.size() == 6, "anchor prerequisite");
    Campaign.Zone zone = runtime.campaign.zones.get(0);
    drain(zone);
    long baseline = zone.resourceEmc;
    int ignoredBaseline = zone.ignoredLootContainers;
    Object first = place(baseX + 2, baseY, baseZ, "minecraft:chest"),
        second = place(baseX + 5, baseY, baseZ, "minecraft:chest"),
        unopened = place(baseX + 8, baseY, baseZ, "minecraft:chest");
    Object iron = Reflect.registry("ITEMS", "minecraft:iron_ingot");
    Reflect.call(
        first,
        "setInventorySlotContents/func_70299_a",
        0,
        Reflect.make("net.minecraft.item.ItemStack", iron, 19, 0));
    Reflect.call(
        second,
        "setInventorySlotContents/func_70299_a",
        0,
        Reflect.make("net.minecraft.item.ItemStack", iron, 13, 0));
    Reflect.call(
        unopened,
        "setLootTable/func_189404_a",
        Reflect.make("net.minecraft.util.ResourceLocation", "minecraft:chests/simple_dungeon"),
        123L);
    Object loot = Reflect.get(unopened, "lootTable/field_184284_m");
    require(loot != null, "unopened chest retains deferred loot");
    drain(zone);
    Long price = runtime.settings.resourcePrices.get("minecraft:iron_ingot");
    require(price != null, "iron resource price configured");
    require(
        zone.complete && zone.resourceEmc == baseline + 32L * price.longValue(),
        "bounded zone scan sums two real chests: complete="
            + zone.complete
            + ", emc="
            + zone.resourceEmc
            + ", error="
            + zone.scanError);
    require(
        zone.ignoredLootContainers == ignoredBaseline + 1,
        "deferred loot chest counted separately");
    require(
        Reflect.get(unopened, "lootTable/field_184284_m") != null,
        "valuation does not generate deferred loot");
  }

  private void health() {
    Object zombie = entity("minecraft:zombie");
    runtime.scale(zombie);
    Object attribute =
        Reflect.get(
            Reflect.type("net.minecraft.entity.SharedMonsterAttributes"),
            "MAX_HEALTH/field_111267_a");
    Object instance = Reflect.call(zombie, "getEntityAttribute/func_110148_a", attribute);
    UUID id = UUID.fromString("fc254a51-aa7e-4df4-b2fe-963155acfa31");
    Object modifier = Reflect.call(instance, "getModifier/func_111127_a", id);
    double maximum = ((Number) Reflect.call(zombie, "getMaxHealth/func_110138_aP")).doubleValue();
    Reflect.call(zombie, "setHealth/func_70606_j", (float) (maximum * .6));
    Object before = modifier;
    runtime.scale(zombie);
    runtime.scale(zombie);
    require(
        Math.abs(
                ((Number) Reflect.call(zombie, "getMaxHealth/func_110138_aP")).doubleValue()
                    - maximum)
            < .001,
        "repeated scale preserves native max health");
    require(
        Math.abs(
                ((Number) Reflect.call(zombie, "getHealth/func_110143_aJ")).doubleValue() / maximum
                    - .6)
            < .001,
        "repeated scale preserves health fraction");
    require(
        before != null && Reflect.call(instance, "getModifier/func_111127_a", id) == before,
        "health modifier is reused without stacking");
  }

  private void wave() {
    require(!runtime.campaign.zones.isEmpty(), "wave anchor prerequisite");
    Campaign.Zone zone = runtime.campaign.zones.get(0);
    runtime.campaign.phase = 0;
    runtime.campaign.points = 0;
    runtime.srp.sync(world, 0, 0);
    Object high = entity("srparasites:rupter"), low = entity("srparasites:buglin");
    require(
        !runtime.world.phaseAllowed(high) && runtime.world.phaseAllowed(low),
        "phase zero rejects locked form and allows scout");
    Map<String, CinematicCatalog.Wave> waves =
        new LinkedHashMap<String, CinematicCatalog.Wave>(runtime.catalog.waves());
    waves.put(
        "probe_high", new CinematicCatalog.Wave(Collections.singletonList("srparasites:rupter")));
    CinematicCatalog frozen =
        new CinematicCatalog(
            runtime.catalog.revision(),
            runtime.catalog.rules(),
            runtime.catalog.sounds(),
            runtime.catalog.traces(),
            waves,
            runtime.catalog.events());
    CinematicCatalog.Event event = runtime.catalog.events().values().iterator().next();
    CinematicEngine.Operation operation =
        new CinematicEngine.Operation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            CinematicEngine.freeze(runtime.catalog, event.id()),
            event.id(),
            zone.id,
            CinematicEngine.State.APPLYING,
            event.start(),
            0,
            runtime.campaign.activeTicks,
            runtime.campaign.activeTicks + 600,
            runtime.campaign.activeTicks,
            3,
            0,
            "",
            Collections.<String>emptySet());
    CinematicCatalog.Action action =
        new CinematicCatalog.Action("wave", "probe_high", "approach", 1);
    Set<String> before = new HashSet<String>(runtime.campaign.owned);
    require(
        runtime.world.point(zone, "approach", true) != null,
        "native wave has safe loaded approach");
    runtime.world.emit(operation, action, 3, frozen);
    require(runtime.campaign.owned.equals(before), "phase-gated wave emits no locked entities");
    runtime.campaign.phase = 10;
    runtime.campaign.points = runtime.srp.thresholds[10];
    runtime.srp.sync(world, 10, runtime.campaign.points);
    require(runtime.world.phaseAllowed(high), "phase ten allows configured form");
    runtime.world.emit(operation, action, 1, frozen);
    require(
        runtime.campaign.owned.size() == before.size() + 1,
        "real native allowed wave emits exactly once");
    runtime.campaign.phase = 0;
    runtime.campaign.points = 0;
    runtime.srp.sync(world, 0, 0);
    boolean retained = false;
    for (Object level : runtime.world.worlds())
      for (Object value :
          new ArrayList<Object>(
              (Collection<?>) Reflect.get(level, "loadedEntityList/field_72996_f"))) {
        String id = Reflect.call(value, "getUniqueID/func_110124_au").toString();
        if (!before.contains(id) && runtime.campaign.owned.contains(id)) {
          retained = (Boolean) Reflect.call(value, "isEntityAlive/func_70089_S");
          Reflect.call(value, "setDead/func_70106_y");
          runtime.campaign.owned.remove(id);
          runtime.campaign.ownership.remove(id);
        }
      }
    require(retained, "phase downgrade does not erase already emitted entity");
  }

  private void queue() {
    runtime.advanceForTest(20, 1);
    List<CinematicEngine.Option> options =
        runtime.engine.options(runtime.catalog, runtime.context());
    require(!options.isEmpty(), "physical zone has typed catalogue options");
    CinematicEngine.Option choice = options.get(0);
    UUID request = UUID.randomUUID();
    CinematicEngine.Verdict first =
        runtime.engine.admitManual(
            request, choice.eventId(), choice.zoneId(), runtime.catalog, runtime.context(), true);
    require(first.accepted(), "finite typed operation admitted");
    runtime.checkpoint();
    CinematicEngine.Verdict replay =
        runtime.engine.admitManual(
            request, choice.eventId(), choice.zoneId(), runtime.catalog, runtime.context(), true);
    require(
        first.operationId().equals(replay.operationId()) && runtime.engine.operations().size() == 1,
        "native queue request replay shares operation");
    CinematicEngine.Operation operation = runtime.engine.operations().get(0);
    require(
        operation.budget() >= 1 && operation.budget() <= runtime.catalog.rules().maxWave(),
        "native queue finite head count");
    runtime.engine.tick(runtime.catalog, runtime.context(), runtime.world);
    boolean effect =
        runtime.engine.operations().isEmpty() || runtime.engine.operations().get(0).cursor() > 0;
    require(effect, "native world adapter executes first typed effect");
    if (!runtime.engine.operations().isEmpty())
      runtime.engine.cancel(
          first.operationId(),
          runtime.campaign.activeTicks,
          runtime.catalog.rules(),
          runtime.world);
    require(
        runtime.engine.history().get(runtime.engine.history().size() - 1).spent()
            <= operation.budget(),
        "native operation never exceeds allocation");
  }

  private void starterShelter() {
    require(runtime.campaign.starterState.equals("NONE"), "fresh full world has no starter claim");
    Object blocked = originalStarterBlocked, sentinel = originalStarterSentinel;
    require(
        blocked != null
            && sentinel != null
            && Reflect.call(world, "getTileEntity/func_175625_s", blocked) == sentinel,
        "original spawn container remains stable through ordinary startup ticks");
    Object provider = Reflect.call(world, "getChunkProvider/func_72863_F");
    int loadedBefore =
        ((Collection<?>) Reflect.call(provider, "getLoadedChunks/func_189548_a")).size();
    require(
        dev.deadearth.legacy.defense.LegacyDefense.placeStarterShelter(runtime),
        "shared starter shelter finds loaded safe site beside blocked initial spawn");
    Object relocated = Reflect.call(world, "getSpawnPoint/func_175694_M");
    require(
        Reflect.x(relocated) != Reflect.x(blocked) || Reflect.z(relocated) != Reflect.z(blocked),
        "blocked initial seven-by-seven triggers deterministic nearby search");
    require(
        Math.abs(Reflect.x(relocated) - Reflect.x(blocked)) <= 64
            && Math.abs(Reflect.z(relocated) - Reflect.z(blocked)) <= 64,
        "shelter search remains within fixed radius64");
    require(
        Reflect.call(world, "getTileEntity/func_175625_s", blocked) == sentinel
            && !(Boolean)
                Reflect.call(
                    Reflect.call(sentinel, "getStackInSlot/func_70301_a", 0),
                    "isEmpty/func_190926_b"),
        "shelter search preserves blocked original container and loot");
    require(
        ((Collection<?>) Reflect.call(provider, "getLoadedChunks/func_189548_a")).size()
            == loadedBefore,
        "shelter search and placement never load chunks");
    Object spawn = Reflect.call(world, "getSpawnPoint/func_175694_M");
    int x = Reflect.x(spawn), y = Reflect.y(spawn), z = Reflect.z(spawn);
    Object floor = Reflect.pos(x, y - 1, z), chestPos = Reflect.pos(x - 2, y, z - 2);
    require(
        Reflect.blockId(world, floor).equals("dead_earth:defense_wall_v1"),
        "physical protected spawn floor");
    require(runtime.protectedAt(world, floor), "original shelter uses active-clock spawn grace");
    require(
        Reflect.blockId(world, Reflect.pos(x, y, z + 3)).equals("dead_earth:defense_door_v1")
            && Reflect.blockId(world, Reflect.pos(x, y + 1, z + 3))
                .equals("dead_earth:defense_door_v1"),
        "paired native starter door");
    require(
        Reflect.blockId(world, Reflect.pos(x + 2, y + 3, z)).equals("dead_earth:defense_hatch_v1"),
        "native roof hatch");
    Object chest = Reflect.call(world, "getTileEntity/func_175625_s", chestPos);
    require(chest != null, "shared starter chest exists");
    String[] ids = {"dead_earth:zone_relay", "minecraft:dirt", "minecraft:sapling"};
    int[] amounts = {6, 16, 8};
    for (int i = 0; i < ids.length; i++) {
      Object stack = Reflect.call(chest, "getStackInSlot/func_70301_a", i);
      require(
          Reflect.call(Reflect.call(stack, "getItem/func_77973_b"), "getRegistryName")
                  .toString()
                  .equals(ids[i])
              && ((Number) Reflect.call(stack, "getCount/func_190916_E")).intValue() == amounts[i],
          "one shared starter allotment " + ids[i]);
    }
    Object empty =
        Reflect.get(Reflect.type("net.minecraft.item.ItemStack"), "EMPTY/field_190927_a");
    Reflect.call(chest, "setInventorySlotContents/func_70299_a", 0, empty);
    require(
        dev.deadearth.legacy.defense.LegacyDefense.placeStarterShelter(runtime),
        "delivered shelter helper is idempotent");
    require(
        (Boolean)
            Reflect.call(
                Reflect.call(chest, "getStackInSlot/func_70301_a", 0), "isEmpty/func_190926_b"),
        "starter relays cannot be reissued");
    Reflect.call(
        chest,
        "setInventorySlotContents/func_70299_a",
        0,
        Reflect.make(
            "net.minecraft.item.ItemStack", Reflect.registry("ITEMS", ids[0]), amounts[0], 0));
    fullGroups.put(
        "starter",
        JsonFiles.map(
            "dimensions",
            "7x7x5",
            "receipt",
            runtime.campaign.starterState,
            "relayCount",
            6,
            "graceTicks",
            runtime.settings.bunkerGraceTicks));
  }

  private int fixtureY(int x, int z) {
    load(x, z, 1);
    int top = 0;
    for (int dx : new int[] {-12, 0, 12})
      for (int dz : new int[] {-12, 0, 12})
        top =
            Math.max(
                top,
                Reflect.y(
                    Reflect.call(
                        world,
                        "getTopSolidOrLiquidBlock/func_175672_r",
                        Reflect.pos(x + dx, 64, z + dz))));
    int y = Math.max(90, top + 8);
    require(y < 210, "bounded air fixture above ordinary terrain");
    return y;
  }

  private void drain(Campaign.Zone zone) {
    zone.complete = false;
    zone.scannedAt = runtime.campaign.activeTicks - runtime.settings.scanTicks;
    for (int n = 0; n < 4096; n++) {
      runtime.world.scan();
      if (zone.scannedAt == runtime.campaign.activeTicks) break;
    }
    require(
        zone.complete && zone.scannedAt == runtime.campaign.activeTicks,
        "bounded incremental inventory scan finishes: " + zone.scanError);
  }

  private Object itemStack(String id, int meta) {
    return Reflect.make("net.minecraft.item.ItemStack", Reflect.registry("ITEMS", id), 1, meta);
  }

  private Object enchantedBook(String id, int level) {
    Object book = itemStack("minecraft:enchanted_book", 0);
    Object enchantment = Reflect.registry("ENCHANTMENTS", id);
    Reflect.call(
        Reflect.type("net.minecraft.item.ItemEnchantedBook"),
        "addEnchantment/func_92115_a",
        book,
        Reflect.make("net.minecraft.enchantment.EnchantmentData", enchantment, level));
    return book;
  }

  private void tools() {
    Object flat =
        Reflect.get(Reflect.type("com.hbm.handler.ability.IToolAreaAbility"), "HAMMER_FLAT");
    int toolCount = 0;
    for (int tier = 1; tier <= 3; tier++)
      for (String kind : new String[] {"hammer_v", "mining_gadget_v"}) {
        String id = "dead_earth:" + kind + tier;
        Object stack = itemStack(id, 0), item = Reflect.call(stack, "getItem/func_77973_b");
        require(
            id.equals(String.valueOf(Reflect.call(item, "getRegistryName"))),
            "own HBM-backed tool registered " + id);
        dev.deadearth.legacy.gameplay.NativeTools.initialize(stack);
        Object cfg = HbmToolApi.configuration(item, stack);
        List<?> presets = (List<?>) Reflect.get(cfg, "presets");
        int current = ((Number) Reflect.get(cfg, "currentPreset")).intValue();
        Object preset = presets.get(current);
        require(
            Reflect.get(preset, "areaAbility") == flat
                && ((Number) Reflect.get(preset, "areaAbilityLevel")).intValue() == tier - 1,
            "native initial area preset " + (2 * tier + 1) + " by " + (2 * tier + 1));
        if (kind.startsWith("mining")) {
          require(
              Reflect.is(item, "com.hbm.api.energymk2.IBatteryItem"),
              "native HBM battery interface " + id);
          long maximum = HbmToolApi.maximum(item, stack);
          require(
              maximum
                  == dev.deadearth.legacy.gameplay.NativeTools.configuration()
                      .electricCapacity[tier - 1],
              "native battery capacity " + id);
          HbmToolApi.setCharge(item, stack, 0L);
          HbmToolApi.accept(item, stack, 1024L);
          require(
              HbmToolApi.charge(item, stack) == 1024L, "native charge method stores energy " + id);
        }
        toolCount++;
      }
    for (String id :
        new String[] {"minecraft:efficiency", "minecraft:fortune", "minecraft:unbreaking"}) {
      int cap = id.endsWith("efficiency") ? 10 : 5;
      Object left = itemStack("dead_earth:hammer_v3", 0), right = enchantedBook(id, cap);
      Object event = Reflect.make("net.minecraftforge.event.AnvilUpdateEvent", left, right, "", 0);
      Reflect.call(
          Reflect.get(Reflect.type("net.minecraftforge.common.MinecraftForge"), "EVENT_BUS"),
          "post",
          event);
      Object result = Reflect.call(event, "getOutput");
      require(
          !(Boolean) Reflect.call(result, "isEmpty/func_190926_b"),
          "native anvil event returns high book application " + id);
      Map<?, ?> levels =
          (Map<?, ?>)
              Reflect.call(
                  Reflect.type("net.minecraft.enchantment.EnchantmentHelper"),
                  "getEnchantments/func_82781_a",
                  result);
      require(
          ((Number) levels.get(Reflect.registry("ENCHANTMENTS", id))).intValue() == cap,
          "actual high native enchantment output " + id);
      require(
          ((Number) Reflect.call(event, "getCost")).intValue() <= 39
              && ((Number) Reflect.call(event, "getMaterialCost")).intValue() == 1,
          "survival anvil cost and one book " + id);
    }
    fullGroups.put(
        "tools",
        JsonFiles.map(
            "ownNativeTools",
            toolCount,
            "presetAreas",
            Arrays.asList(3, 5, 7),
            "nativeBatteries",
            3,
            "highAnvilEventOutputs",
            3,
            "anvilUiPickupTested",
            false));
  }

  private void ae2Valuation() {
    Campaign.Zone zone = runtime.campaign.zones.get(0);
    drain(zone);
    long initial = zone.resourceEmc;
    Object drive = place(baseX + 10, baseY, baseZ + 4, "appliedenergistics2:drive");
    require(drive != null, "physical native ME drive");
    Object cellStack = itemStack("appliedenergistics2:storage_cell_1k", 0),
        api = Reflect.call(Reflect.type("appeng.api.AEApi"), "instance"),
        storage = Reflect.call(api, "storage"),
        channel =
            Reflect.call(
                storage,
                "getStorageChannel",
                Reflect.type("appeng.api.storage.channels.IItemStorageChannel"));
    Object cells = Reflect.call(Reflect.call(api, "registries"), "cell"),
        handler = Reflect.call(cells, "getCellInventory", cellStack, null, channel);
    require(handler != null, "native ME item cell inventory");
    Object nativeStack =
        Reflect.call(
            Reflect.type("appeng.util.item.AEItemStack"),
            "fromItemStack",
            itemStack("minecraft:iron_ingot", 0));
    Reflect.call(nativeStack, "setStackSize", 1234L);
    Object actionable = Reflect.get(Reflect.type("appeng.api.config.Actionable"), "MODULATE");
    require(
        Reflect.call(handler, "injectItems", nativeStack, actionable, null) == null,
        "native cell accepts long quantity");
    Reflect.call(Reflect.call(handler, "getCellInv"), "persist");
    Reflect.call(Reflect.call(drive, "getInternalInventory"), "setStackInSlot", 0, cellStack);
    InventoryValuation.Cursor cursor = InventoryValuation.open(drive, runtime.settings);
    long raw = 0;
    int steps = 0;
    while (!cursor.done() && steps++ < 1024) {
      cursor.verify();
      InventoryValuation.Entry entry = cursor.next();
      if (entry != null
          && entry.stack != null
          && entry.count > 0
          && Reflect.call(Reflect.call(entry.stack, "getItem/func_77973_b"), "getRegistryName")
              .toString()
              .equals("minecraft:iron_ingot")) raw += entry.count;
    }
    require(
        cursor.done() && raw == 1234L,
        "incremental native ME cursor counts cell content exactly once");
    drain(zone);
    require(
        zone.resourceEmc
            == initial + 1234L * runtime.settings.resourcePrices.get("minecraft:iron_ingot"),
        "physical ME drive contents belong to their relay zone");
    fullGroups.put(
        "ae2",
        JsonFiles.map(
            "physicalDrive",
            true,
            "storedIron",
            raw,
            "cursorSteps",
            steps,
            "zoneDelta",
            zone.resourceEmc - initial));
  }

  private void farming() {
    int x = baseX + 40, z = baseZ + 40, y = fixtureY(x, z);
    int tested = 0;
    for (String seed :
        new String[] {
          "minecraft:wheat_seeds", "mysticalagriculture:iron_seeds", "xlfoodmod:corn_seeds"
        }) {
      Object at = Reflect.pos(x + 6 + tested, y, z);
      dev.deadearth.legacy.farming.FarmingNativeChecks.nativeCrop(world, at, seed);
      dev.deadearth.legacy.farming.FarmingNativeChecks.persistedTray(world, at, player, seed);
      tested++;
    }
    fullGroups.put(
        "farming",
        JsonFiles.map(
            "nativeCropFamilies",
            tested,
            "actualHarvest",
            true,
            "saveReload",
            true,
            "sidedAutomation",
            true));
  }

  private void machinery() throws Exception {
    int x = baseX + 40, z = baseZ + 44, y = fixtureY(x, z);
    Object charger = place(x, y, z, "dead_earth:charger"),
        processor = place(x + 2, y, z, "dead_earth:fortune_processor");
    fullGroups.put(
        "machinery", dev.deadearth.legacyprobe.machinery.MachineryProbe.verify(charger, processor));
  }

  private void climateClockAndPacket() {
    long saved = runtime.campaign.activeTicks;
    Object provider = Reflect.call(world, "getChunkProvider/func_72863_F"),
        info = Reflect.call(world, "getWorldInfo/func_72912_H");
    int loaded = ((Collection<?>) Reflect.call(provider, "getLoadedChunks/func_189548_a")).size();
    try {
      runtime.campaign.activeTicks = runtime.settings.seasonTicks * 2 + 1;
      dev.deadearth.legacy.climate.LegacyClimate.tick(runtime);
      require(
          (Boolean) Reflect.call(info, "isRaining/func_76059_o"),
          "accelerated canonical winter drives native weather");
      require(
          runtime.settings.seasonMultiplier(runtime.campaign.activeTicks) == 1.8,
          "winter native parasite multiplier1.8");
      runtime.campaign.activeTicks = runtime.settings.seasonTicks + 1;
      dev.deadearth.legacy.climate.LegacyClimate.tick(runtime);
      require(
          !(Boolean) Reflect.call(info, "isRaining/func_76059_o"),
          "accelerated canonical summer clears native rain");
      require(
          runtime.settings.seasonMultiplier(runtime.campaign.activeTicks) == .5,
          "summer native parasite multiplier0.5");
    } finally {
      runtime.campaign.activeTicks = saved;
      dev.deadearth.legacy.climate.LegacyClimate.tick(runtime);
    }
    require(
        ((Collection<?>) Reflect.call(provider, "getLoadedChunks/func_189548_a")).size() == loaded,
        "production seasonal tick has no forced chunk loads");
    Object packet = Reflect.make("dev.deadearth.legacy.climate.ClimatePacket");
    Reflect.set(packet, "season", 2);
    Reflect.set(packet, "enabled", true);
    Reflect.set(packet, "particles", 6);
    Reflect.set(packet, "radius", 24);
    Reflect.set(packet, "height", 12);
    Reflect.set(packet, "fog", .035f);
    Reflect.set(packet, "red", .6f);
    Reflect.set(packet, "green", .6f);
    Reflect.set(packet, "blue", .6f);
    Object bytes = Reflect.call(Reflect.type("io.netty.buffer.Unpooled"), "buffer");
    try {
      Reflect.call(packet, "toBytes", bytes);
      require(
          ((Number) Reflect.call(bytes, "readableBytes")).intValue() == 21,
          "native climate presentation payload stays21bytes");
      Object decoded = Reflect.make("dev.deadearth.legacy.climate.ClimatePacket");
      Reflect.call(decoded, "fromBytes", bytes);
      require(
          (Boolean) Reflect.get(decoded, "enabled")
              && ((Number) Reflect.get(decoded, "season")).intValue() == 2
              && ((Number) Reflect.get(decoded, "particles")).intValue() == 6,
          "native presentation packet round trip");
      Reflect.call(bytes, "clear");
      Reflect.set(packet, "fog", Float.NaN);
      Reflect.call(packet, "toBytes", bytes);
      Reflect.call(decoded, "fromBytes", bytes);
      require(
          !(Boolean) Reflect.get(decoded, "enabled"),
          "malformed presentation packet disables client effect snapshot");
    } finally {
      Reflect.call(bytes, "release");
    }
    fullGroups.put(
        "climateClockAndPackets",
        JsonFiles.map(
            "acceleratedNativeWeather",
            true,
            "seasonMultipliers",
            Arrays.asList(.5, 1.8),
            "nativeCodecBytes",
            21,
            "malformedPacketDisabled",
            true,
            "clientNetworkDeliveryTested",
            false,
            "clientVisualsTested",
            false));
  }

  private void recipeLog() throws Exception {
    int files = 0;
    List<String> errors = new ArrayList<String>();
    for (String relative : new String[] {"crafttweaker.log", "logs/crafttweaker.log"}) {
      Path path = Paths.get(relative);
      if (!Files.isRegularFile(path)) continue;
      files++;
      require(Files.size(path) <= 16 * 1024 * 1024, "bounded CraftTweaker diagnostic log");
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
        if (line.matches(
            "(?i).*(\\[(?:CT )?ERROR(?:\\]|[ :])|Error executing|Could not resolve item|Cannot"
                + " cast).*")) {
          if (errors.size() < 20) errors.add(line.substring(0, Math.min(line.length(), 600)));
        }
    }
    require(files > 0, "native CraftTweaker log present");
    require(errors.isEmpty(), "native scripts have no recipe/compiler errors: " + errors);
    fullGroups.put("recipes", JsonFiles.map("nativeLogFiles", files, "errors", errors));
  }

  private void runFull(String name, Checked action) {
    int before = checks.size();
    run(name, action);
    fullAssertionCount += checks.size() - before;
    fullGroupCount++;
  }

  private Object entity(String id) {
    Object value =
        Reflect.call(
            Reflect.type("net.minecraft.entity.EntityList"),
            "createEntityByIDFromName/func_188429_b",
            Reflect.make("net.minecraft.util.ResourceLocation", id),
            world);
    if (value == null) throw new IllegalStateException("Native entity is absent: " + id);
    return value;
  }

  private void load(int x, int z, int radius) {
    for (int dx = -radius; dx <= radius; dx++)
      for (int dz = -radius; dz <= radius; dz++)
        Reflect.call(world, "getChunkFromChunkCoords/func_72964_e", (x >> 4) + dx, (z >> 4) + dz);
  }

  private Object state(String id) {
    Object block = Reflect.registry("BLOCKS", id);
    if (block == null) throw new IllegalStateException("Missing block " + id);
    return Reflect.call(block, "getDefaultState/func_176223_P");
  }

  private void placeAt(Object position, String id) {
    Reflect.call(world, "setBlockState/func_180501_a", position, state(id), 3);
  }

  private Object place(int x, int y, int z, String id) {
    Object position = Reflect.pos(x, y, z);
    placeAt(position, id);
    return Reflect.call(world, "getTileEntity/func_175625_s", position);
  }

  private static JsonObject asObject(Object value) {
    return JsonFiles.JSON.toJsonTree(value).getAsJsonObject();
  }

  private void require(boolean value, String name) {
    checks.add(result(name, value, null));
    if (!value) throw new IllegalStateException(name);
  }

  private void run(String name, Checked action) {
    try {
      action.run();
      checks.add(result(name, true, null));
    } catch (Throwable failed) {
      failures++;
      checks.add(result(name, false, failed));
      failed.printStackTrace();
    }
  }

  private static Map<String, Object> result(String name, boolean passed, Throwable failed) {
    Map<String, Object> value = new LinkedHashMap<String, Object>();
    value.put("name", name);
    value.put("passed", passed);
    if (failed != null) value.put("error", failed.toString());
    return value;
  }

  private interface Checked {
    void run() throws Exception;
  }
}
