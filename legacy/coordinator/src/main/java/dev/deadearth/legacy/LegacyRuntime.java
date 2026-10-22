package dev.deadearth.legacy;

import static dev.deadearth.legacy.JsonFiles.map;

import com.google.gson.*;
import dev.deadearth.legacy.kernel.*;
import java.nio.file.*;
import java.util.*;

/** Main-thread coordinator. HTTP/model work has no access to live world state. */
public final class LegacyRuntime {
  public final Object server;
  public Settings settings;
  public final Campaign campaign;
  public final NativeSrp srp;
  public final GameWorld world;
  public final dev.deadearth.legacy.data.PhaseRoster roster;
  private final Path configDirectory;
  public CinematicCatalog catalog;
  public final CinematicEngine engine;
  public final Path savePath, worldDirectory;
  private final Thread owner;
  private JsonObject catalogDocument;
  private LocalControl bridge;
  private dev.deadearth.legacy.config.LiveConfiguration configuration;
  private int observedOnline = 0;
  private double pressure = 1;
  private int entityCursor = 0;
  private long serverTicks = 0;
  private boolean closed = false;
  private final ScenarioAnalytics eventAnalytics;
  private final CombatLedger combat;

  public LegacyRuntime(Object server, Path config) throws Exception {
    this.server = server;
    configDirectory = config;
    owner = Thread.currentThread();
    Object w = Reflect.call(server, "getWorld/func_71218_a", 0);
    Object handler = Reflect.call(w, "getSaveHandler/func_72860_G");
    worldDirectory =
        ((java.io.File) Reflect.call(handler, "getWorldDirectory/func_75765_b")).toPath();
    savePath = worldDirectory.resolve("data/dead_earth_legacy.json");
    settings = Settings.load(config.resolve("dead-earth/coordinator.json"));
    campaign = Campaign.open(savePath);
    srp = new NativeSrp();
    catalogDocument = JsonFiles.read(config.resolve("dead-earth/director/catalog.json"), 1048576);
    catalog = CinematicCatalog.parse(catalogDocument.toString());
    engine =
        campaign.cinematic == null
            ? new CinematicEngine()
            : CinematicEngine.restore(campaign.cinematic);
    eventAnalytics = new ScenarioAnalytics(this);
    combat = new CombatLedger(this);
    roster =
        dev.deadearth.legacy.data.PhaseRoster.parse(
            JsonFiles.read(config.resolve("dead-earth/srp-entities.json"), 65536));
    world = new GameWorld(this, roster);
    world.validate(catalog);
    for (Object level : world.worlds())
      if (settings.controls(Reflect.dimension(level))) srp.lock(level);
    checkpoint();
    try {
      configuration =
          new dev.deadearth.legacy.config.LiveConfiguration(
              config,
              config.toAbsolutePath().getParent(),
              campaign.worldEpoch,
              campaign.campaignId,
              new dev.deadearth.legacy.config.RuntimeConfigurationAdapter(this));
    } catch (Exception unavailableEditor) {
      // An unreadable admin journal must not disable the running game or replace its evidence.
      configuration = null;
      System.err.println("[DEAD EARTH] Configuration editor unavailable; preserved original state");
    }
    bridge = new LocalControl(this);
  }

  public boolean onOwnerThread() {
    return Thread.currentThread() == owner;
  }

  public void requireThread() {
    if (Thread.currentThread() != owner)
      throw new IllegalStateException("World accessed off server thread");
  }

  public void tick() {
    requireThread();
    if (closed) return;
    serverTicks++;
    dev.deadearth.legacy.combat.LegacyPerception.tick(this);
    if (serverTicks % 100 == 0) ZoneRelay.Tickets.reconcile(this);
    combat.flush();
    bridge.drain();
    dev.deadearth.legacy.sieving.LegacySieving.tick(this);
    observedOnline = world.players().size();
    if (!settings.enabled || observedOnline == 0) {
      dev.deadearth.legacy.combat.RelaySiege.suspend();
      if (serverTicks % 200 == 0) bridge.publish();
      return;
    }
    campaign.activeTicks++;
    dev.deadearth.legacy.combat.RelaySiege.tick(this);
    if (campaign.activeTicks % 100 == 1) starter();
    updatePressure();
    if (campaign.activeTicks % 20 == 0) {
      advanceEvolution(20);
      for (Object level : world.worlds())
        if (settings.controls(Reflect.dimension(level)))
          srp.sync(level, campaign.phase, campaign.points);
    }
    dev.deadearth.legacy.climate.LegacyClimate.tick(this);
    world.scan();
    engine.tick(catalog, context(), world);
    if (campaign.activeTicks % 40 == 0) {
      scaleLoaded();
      cleanupTraces();
    }
    if (campaign.activeTicks % 200 == 0) {
      checkpoint();
      bridge.publish();
    }
  }

  public void advanceEvolution(long ticks) {
    if (campaign.paused) return;
    double multiplier =
        Settings.clamp(pressure, .05, settings.evolutionMaximumMultiplier)
            * settings.seasonMultiplier(campaign.activeTicks);
    campaign.points =
        Math.min(
            Integer.MAX_VALUE, campaign.points + ticks * settings.evolutionBaseRate * multiplier);
    int next = srp.phaseFor(campaign.points);
    if (next != campaign.phase) changePhase(next, "automatic", null);
  }

  public void advanceForTest(long ticks, int online) {
    requireThread();
    if (!Boolean.getBoolean("deadearth.testControls") || ticks < 0 || ticks > 10000000)
      throw new IllegalStateException("Test control disabled");
    if (online == 0) return;
    observedOnline = online;
    for (long i = 0; i < ticks; i += 20) {
      long delta = Math.min(20, ticks - i);
      campaign.activeTicks += delta;
      updatePressure();
      advanceEvolution(delta);
    }
    for (Object level : world.worlds())
      if (settings.controls(Reflect.dimension(level)))
        srp.sync(level, campaign.phase, campaign.points);
    checkpoint();
    bridge.publish();
  }

  private void updatePressure() {
    double score = 0;
    for (Object p : world.players()) {
      String id = Reflect.call(p, "getUniqueID/func_110124_au").toString();
      Campaign.Player stats = campaign.players.get(id);
      int xp = ((Number) Reflect.get(p, "experienceLevel/field_71068_ca")).intValue();
      int armor = ((Number) Reflect.call(p, "getTotalArmorValue/func_70658_aO")).intValue();
      score +=
          xp * settings.xpWeight
              + armor * settings.armorWeight
              + (stats == null
                  ? 0
                  : Math.min(stats.kills, 200) * settings.killWeight
                      - Math.min(stats.deaths, 10) * settings.deathWeight
                      - Math.min(stats.damage, 200) * settings.damageWeight);
    }
    score /= Math.max(1, observedOnline);
    pressure =
        Settings.clamp(
                1 + score * settings.scoreCoefficient,
                Math.max(.05, 1 - settings.maximumDeviation),
                1 + settings.maximumDeviation)
            * settings.intro(campaign.activeTicks);
  }

  private void changePhase(int next, String source, String request) {
    int old = campaign.phase;
    campaign.phase = next;
    campaign.revision++;
    campaign.history.add(
        map(
            "atTick",
            campaign.activeTicks,
            "from",
            old,
            "to",
            next,
            "source",
            source,
            "requestId",
            request));
    while (campaign.history.size() > 128) campaign.history.remove(0);
  }

  public Map<String, Object> evolution() {
    double start = srp.thresholds[campaign.phase];
    Double next = campaign.phase == 10 ? null : (double) srp.thresholds[campaign.phase + 1];
    double progress =
        next == null ? 1 : Settings.clamp((campaign.points - start) / (next - start), 0, 1);
    return map(
        "status",
        observedOnline == 0 || campaign.paused ? "paused" : "ready",
        "enabled",
        settings.enabled,
        "campaignId",
        campaign.campaignId,
        "worldEpoch",
        campaign.worldEpoch,
        "revision",
        campaign.revision,
        "configRevision",
        settings.revision,
        "parasiteSystem",
        "srp",
        "phaseMaximum",
        10,
        "phase",
        campaign.phase,
        "name",
        "SRP \u2014 \u0444\u0430\u0437\u0430 " + campaign.phase,
        "progress",
        progress,
        "points",
        campaign.points,
        "phaseStartPoints",
        start,
        "nextPhasePoints",
        next,
        "rate",
        settings.evolutionBaseRate * pressure * settings.seasonMultiplier(campaign.activeTicks),
        "paused",
        observedOnline == 0 || campaign.paused,
        "migration",
        "new_campaign",
        "unlockedEntities",
        allowedEntities(),
        "abilities",
        Arrays.asList("native_srp_phase_" + campaign.phase, "native_siege_digging"),
        "history",
        campaign.history);
  }

  private List<String> allowedEntities() {
    return roster.unlocked(campaign.phase);
  }

  public CinematicEngine.Context context() {
    List<CinematicEngine.Zone> zones = new ArrayList<CinematicEngine.Zone>();
    for (Campaign.Zone z : campaign.zones) {
      double losses = 0;
      for (Campaign.Player p : campaign.players.values())
        if (campaign.activeTicks - p.lastLoss < 6000) losses += p.deaths * .25;
      zones.add(
          new CinematicEngine.Zone(
              z.id,
              z.value,
              z.armedRank,
              z.emptyRank,
              Math.max(0, campaign.activeTicks - z.scannedAt),
              z.complete,
              false,
              world.players(z),
              Math.min(10, world.players(z) * 2),
              Math.min(4, losses)));
    }
    return new CinematicEngine.Context(
        campaign.activeTicks,
        observedOnline,
        settings.season(campaign.activeTicks),
        Settings.clamp(pressure, .05, 4),
        zones,
        campaign.phase);
  }

  public Map<String, Object> brief() {
    requireThread();
    CinematicEngine.Goal goal =
        engine.brief(UUID.fromString(campaign.worldEpoch), catalog, context(), settings.enabled);
    checkpoint();
    List<CinematicEngine.History> recent = engine.history();
    return map(
        "status",
        !settings.enabled
            ? "disabled"
            : observedOnline == 0 ? "no_players" : goal == null ? "waiting" : "ready",
        "catalogRevision",
        catalog.revision(),
        "activeTicks",
        campaign.activeTicks,
        "season",
        settings.season(campaign.activeTicks),
        "pressure",
        Settings.clamp(pressure, .05, 4),
        "parasiteSystem",
        "srp",
        "parasitePhaseMaximum",
        10,
        "parasitePhase",
        campaign.phase,
        "goal",
        goal,
        "recent",
        recent.subList(Math.max(0, recent.size() - 6), recent.size()),
        "active",
        engine.operations().size(),
        "queued",
        engine.operations().size());
  }

  public Object choose(JsonObject args) {
    requireThread();
    Object result =
        engine.admit(
            UUID.fromString(args.get("request_id").getAsString()),
            UUID.fromString(args.get("goal_id").getAsString()),
            args.get("event_id").getAsString(),
            args.get("zone_id").getAsString(),
            UUID.fromString(campaign.worldEpoch),
            catalog,
            context(),
            settings.enabled);
    checkpoint();
    return result;
  }

  public Map<String, Object> snapshot() {
    requireThread();
    Map<String, Object> climate =
        map(
            "phase",
            settings.season(campaign.activeTicks),
            "ticksRemaining",
            settings.seasonTicks - campaign.activeTicks % settings.seasonTicks);
    Map<String, Object> difficulty =
        map(
            "pressure",
            pressure,
            "parasitePhaseRate",
            settings.evolutionBaseRate * pressure,
            "parasiteHealth",
            healthMultiplier(),
            "parasiteDamage",
            damageMultiplier());
    return map(
        "enabled",
        settings.enabled,
        "campaignId",
        campaign.campaignId,
        "worldEpoch",
        campaign.worldEpoch,
        "activeTicks",
        campaign.activeTicks,
        "onlinePlayers",
        observedOnline,
        "parasiteSystem",
        "srp",
        "parasitePhaseMaximum",
        10,
        "parasitePhase",
        campaign.phase,
        "climate",
        climate,
        "climateEffectsEnabled",
        true,
        "difficulty",
        difficulty,
        "zones",
        campaign.zones,
        "observations",
        map("history", engine.history()),
        "evolution",
        evolution());
  }

  public Object queue() {
    requireThread();
    return eventAnalytics.page();
  }

  public Object dispatch(String operation, JsonObject a) {
    requireThread();
    switch (operation) {
      case "get_snapshot":
        return map("snapshot", snapshot(), "cache_age_millis", 0);
      case "get_cinematic_brief":
        return brief();
      case "choose_cinematic":
        return choose(a);
      case "get_cinematic_queue":
        return queue();
      case "get_event_analytics":
        return eventAnalytics.view(a.get("operation_id").getAsString());
      case "list_catalog":
        return JsonFiles.JSON.fromJson(catalogDocument.toString(), JsonObject.class);
      case "get_history":
        return engine
            .history()
            .subList(
                Math.max(0, engine.history().size() - a.get("limit").getAsInt()),
                engine.history().size());
      case "cancel_scenario":
        if (a.get("revision").getAsLong() != campaign.revision)
          return map("accepted", false, "code", "stale_revision");
        boolean cancelled =
            engine.cancel(
                UUID.fromString(a.get("scenario_id").getAsString()),
                campaign.activeTicks,
                catalog.rules(),
                world);
        if (cancelled) {
          campaign.revision++;
          checkpoint();
        }
        return map("accepted", cancelled, "revision", campaign.revision);
      default:
        throw new IllegalArgumentException("unknown_operation");
    }
  }

  public void checkpoint() {
    requireThread();
    campaign.cinematic = engine.snapshot();
    JsonFiles.write(savePath, campaign);
  }

  public void close() {
    requireThread();
    if (closed) return;
    dev.deadearth.legacy.sieving.LegacySieving.close();
    dev.deadearth.legacy.defense.LegacyDefense.close();
    dev.deadearth.legacy.climate.LegacyClimate.close();
    closed = true;
    checkpoint();
    if (bridge != null) bridge.close();
  }

  public boolean anchor(Object w, Object pos) {
    requireThread();
    if (!settings.controls(Reflect.dimension(w))
        || !Reflect.loaded(w, pos)
        || !Reflect.blockId(w, pos).equals("dead_earth:zone_relay")) return false;
    int dim = Reflect.dimension(w), x = Reflect.x(pos), y = Reflect.y(pos), z = Reflect.z(pos);
    for (Campaign.Zone v : campaign.zones)
      if (v.dimension == dim && v.x == x && v.y == y && v.z == z) return true;
    Iterator<Campaign.Zone> iter = campaign.zones.iterator();
    while (iter.hasNext()) {
      Campaign.Zone v = iter.next();
      Object level = world.world(v.dimension);
      if (level != null
          && Reflect.loaded(level, Reflect.pos(v.x, v.y, v.z))
          && !Reflect.blockId(level, Reflect.pos(v.x, v.y, v.z)).equals("dead_earth:zone_relay"))
        iter.remove();
    }
    if (campaign.zones.size() >= settings.maximumZones) return false;
    for (Campaign.Zone v : campaign.zones)
      if (v.dimension == dim
          && Math.abs((v.x >> 4) - (x >> 4)) <= 2 * settings.zoneRadiusChunks
          && Math.abs((v.z >> 4) - (z >> 4)) <= 2 * settings.zoneRadiusChunks) return false;
    Campaign.Zone v = new Campaign.Zone();
    v.dimension = dim;
    v.x = x;
    v.y = y;
    v.z = z;
    v.name = String.valueOf((char) ('A' + campaign.zones.size()));
    campaign.zones.add(v);
    checkpoint();
    return true;
  }

  public double healthMultiplier() {
    return Settings.clamp(
        settings.intro(campaign.activeTicks)
            * (1 + (pressure - 1) * settings.healthScoreCoefficient)
            * settings.seasonMultiplier(campaign.activeTicks),
        .05,
        8);
  }

  public double damageMultiplier() {
    return Settings.clamp(
        settings.intro(campaign.activeTicks)
            * (1 + (pressure - 1) * settings.damageScoreCoefficient)
            * settings.seasonMultiplier(campaign.activeTicks),
        .05,
        8);
  }

  public void scale(Object entity) {
    if (!Reflect.is(entity, "net.minecraft.entity.EntityLivingBase")) return;
    String id = Reflect.entityId(entity);
    if (!id.startsWith("srparasites:")
        && !id.equals("minecraft:zombie")
        && !id.equals("minecraft:husk")) return;
    attribute(
        entity,
        "MAX_HEALTH/field_111267_a",
        UUID.fromString("fc254a51-aa7e-4df4-b2fe-963155acfa31"),
        healthMultiplier(),
        true);
    attribute(
        entity,
        "ATTACK_DAMAGE/field_111264_e",
        UUID.fromString("fc254a51-aa7e-4df4-b2fe-963155acfa32"),
        damageMultiplier(),
        false);
  }

  private void attribute(Object e, String key, UUID id, double multiplier, boolean health) {
    Object attr = Reflect.get(Reflect.type("net.minecraft.entity.SharedMonsterAttributes"), key);
    Object instance = Reflect.call(e, "getEntityAttribute/func_110148_a", attr);
    if (instance == null) return;
    Object old = Reflect.call(instance, "getModifier/func_111127_a", id);
    if (old != null
        && Math.abs(
                ((Number) Reflect.call(old, "getAmount/func_111164_d")).doubleValue()
                    - (multiplier - 1))
            < .001) return;
    double fraction =
        health
            ? ((Number) Reflect.call(e, "getHealth/func_110143_aJ")).doubleValue()
                / Math.max(
                    .01, ((Number) Reflect.call(e, "getMaxHealth/func_110138_aP")).doubleValue())
            : 1;
    if (old != null) Reflect.call(instance, "removeModifier/func_111124_b", old);
    Object mod =
        Reflect.make(
            "net.minecraft.entity.ai.attributes.AttributeModifier",
            id,
            "dead_earth_srp_balance",
            multiplier - 1,
            2);
    Reflect.call(instance, "applyModifier/func_111121_a", mod);
    if (health)
      Reflect.call(
          e,
          "setHealth/func_70606_j",
          (float)
              (Math.max(0, fraction)
                  * ((Number) Reflect.call(e, "getMaxHealth/func_110138_aP")).doubleValue()));
  }

  private void scaleLoaded() {
    int budget = 64;
    for (Object w : world.worlds()) {
      List<?> all = (List<?>) Reflect.get(w, "loadedEntityList/field_72996_f");
      if (all.isEmpty()) continue;
      for (int i = 0; i < Math.min(budget, all.size()); i++) {
        Object e = all.get(Math.floorMod(entityCursor++, all.size()));
        scale(e);
        Object tag = Reflect.nbt(e);
        String owner = (String) Reflect.call(tag, "getString/func_74779_i", "dead_earth_operation");
        if (!owner.isEmpty()
            && ((Number) Reflect.call(tag, "getLong/func_74763_f", "dead_earth_deadline"))
                    .longValue()
                < campaign.activeTicks) {
          Reflect.call(e, "setDead/func_70106_y");
          String removed = Reflect.call(e, "getUniqueID/func_110124_au").toString();
          campaign.owned.remove(removed);
          campaign.ownership.remove(removed);
        }
      }
      budget -= Math.min(budget, all.size());
      if (budget <= 0) break;
    }
  }

  private void weather() {
    for (Object w : world.worlds())
      if (Reflect.dimension(w) == 0) {
        Object info = Reflect.call(w, "getWorldInfo/func_72912_H");
        Reflect.call(
            info,
            "setRaining/func_76084_b",
            settings.season(campaign.activeTicks).equals("ASH_WINTER"));
        Reflect.call(info, "setThundering/func_76069_a", false);
        Reflect.call(info, "setRainTime/func_76080_g", 200);
      }
  }

  public boolean protectedAt(Object w, Object pos) {
    if (campaign.activeTicks >= dev.deadearth.legacy.defense.BunkerProtection.deadline(
            settings.bunkerGraceTicks, campaign.bunkerProtectionUntilTicks)
        || Reflect.dimension(w) != 0)
      return false;
    Object spawn = Reflect.call(w, "getSpawnPoint/func_175694_M");
    return Math.abs(Reflect.x(pos) - Reflect.x(spawn)) <= 16
        && Math.abs(Reflect.z(pos) - Reflect.z(spawn)) <= 16
        && Math.abs(Reflect.y(pos) - Reflect.y(spawn)) <= 32;
  }

  public void event(Object event) {
    requireThread();
    String name = event.getClass().getName();
    if (name.endsWith("EntityJoinWorldEvent")) {
      Object e = Reflect.call(event, "getEntity");
      scale(e);
      String owner =
          (String) Reflect.call(Reflect.nbt(e), "getString/func_74779_i", "dead_earth_operation");
      if (!owner.isEmpty()) {
        UUID.fromString(owner);
        String id = Reflect.call(e, "getUniqueID/func_110124_au").toString();
        if (campaign.owned.size() < 64 || campaign.owned.contains(id)) {
          campaign.owned.add(id);
          campaign.ownership.put(id, owner);
        }
      }
    } else if (name.endsWith("LivingDamageEvent") || name.endsWith("LivingDeathEvent"))
      combat.record(event, name.endsWith("LivingDeathEvent"));
    else if (name.contains("PlayerInteractEvent") && name.endsWith("RightClickBlock")) {
      Object p = Reflect.call(event, "getEntityPlayer"), at = Reflect.call(event, "getPos");
      if (Reflect.blockId(Reflect.world(p), at).startsWith("dead_earth:trace_"))
        world.signal(p, "inspect");
    } else if (name.endsWith("BlockEvent$BreakEvent"))
      world.signal(Reflect.call(event, "getPlayer"), "break");
  }

  public Map<String, Object> analytics(String id) {
    return eventAnalytics.record(id);
  }

  public void metric(String id, String key, int count) {
    eventAnalytics.metric(id, key, count);
  }

  public void timeline(String id, String kind, String role, String detail, int count) {
    eventAnalytics.timeline(id, kind, role, detail, count);
  }

  int onlinePlayers() {
    return observedOnline;
  }

  private void starter() {
    dev.deadearth.legacy.defense.LegacyDefense.placeStarterShelter(this);
  }

  private void cleanupTraces() {
    Iterator<Map<String, Object>> it = campaign.traces.iterator();
    while (it.hasNext()) {
      Map<String, Object> trace = it.next();
      if (((Number) trace.get("expires")).longValue() > campaign.activeTicks) continue;
      Object level = world.world(((Number) trace.get("dimension")).intValue());
      Object p =
          Reflect.pos(
              ((Number) trace.get("x")).intValue(),
              ((Number) trace.get("y")).intValue(),
              ((Number) trace.get("z")).intValue());
      if (level == null || !Reflect.loaded(level, p)) continue;
      if (Reflect.blockId(level, p).equals(trace.get("block")))
        Reflect.call(level, "setBlockToAir/func_175698_g", p);
      it.remove();
    }
  }

  private String sourceHash() {
    try {
      return JsonFiles.sha(
          new String(
              Files.readAllBytes(configDirectory.resolve("dead-earth/director/catalog.json")),
              java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void applyCatalogConfiguration(CinematicCatalog prepared, JsonObject document) {
    catalog = prepared;
    catalogDocument = document;
  }

  private Object configurationCommand(String[] args) {
    if (configuration == null)
      return map(
          "schema",
          1,
          "status",
          "unknown",
          "accepted",
          false,
          "state",
          "rejected",
          "errors",
          java.util.Collections.singletonList(
              map(
                  "section",
                  "",
                  "key",
                  "",
                  "code",
                  "unavailable",
                  "message",
                  "Configuration editor unavailable")));
    if (args.length == 2 && args[1].equals("view")) return configuration.view();
    if (args.length == 3 && args[1].equals("receipt")) return configuration.receipt(args[2]);
    if (args.length == 5 && (args[1].equals("validate") || args[1].equals("apply")))
      return configuration.command(args[1], args[2], args[3], args[4]);
    return map(
        "schema",
        1,
        "accepted",
        false,
        "state",
        "rejected",
        "errors",
        java.util.Collections.emptyList());
  }

  public Object operator(String[] args) {
    String action = args[1];
    if (action.equals("status") && args.length == 2)
      return map(
          "protocol",
          1,
          "campaignId",
          campaign.campaignId,
          "worldEpoch",
          campaign.worldEpoch,
          "catalogRevision",
          catalog.revision(),
          "sourceHash",
          sourceHash(),
          "enabled",
          settings.enabled,
          "activeTicks",
          campaign.activeTicks,
          "zones",
          context().zones());
    if (args.length < 3 || !campaign.worldEpoch.equals(args[2]))
      return map("accepted", false, "code", "stale_epoch");
    if (action.equals("cancel") && args.length == 4) {
      boolean ok =
          engine.cancel(UUID.fromString(args[3]), campaign.activeTicks, catalog.rules(), world);
      checkpoint();
      return map("accepted", ok, "code", ok ? "cancelled" : "unknown_operation");
    }
    if (action.equals("preview") && args.length == 6) {
      if (!catalog.revision().equals(args[3]))
        return map("accepted", false, "code", "stale_catalog");
      CinematicEngine.ManualPreview preview =
          engine.previewManual(catalog, context(), settings.enabled, args[4], args[5]);
      return map(
          "accepted",
          preview.accepted(),
          "code",
          preview.code(),
          "revision",
          catalog.revision(),
          "sourceHash",
          sourceHash(),
          "option",
          preview.option());
    }
    if (action.equals("run") && args.length == 7) {
      if (!catalog.revision().equals(args[3]))
        return map("accepted", false, "code", "stale_catalog");
      CinematicEngine.Verdict verdict =
          engine.admitManual(
              UUID.fromString(args[4]), args[5], args[6], catalog, context(), settings.enabled);
      checkpoint();
      return map(
          "accepted",
          verdict.accepted(),
          "code",
          verdict.code(),
          "revision",
          catalog.revision(),
          "sourceHash",
          sourceHash(),
          "operationId",
          verdict.operationId());
    }
    if ((action.equals("validate") && args.length == 4)
        || (action.equals("apply") && args.length == 5)) {
      String request = args[args.length - 1];
      if (!UUID.fromString(request).toString().equals(request))
        throw new IllegalArgumentException("request");
      if (action.equals("apply") && configuration != null && !configuration.allowsExternalWrite())
        return map("accepted", false, "code", "restart_pending");
      if (action.equals("apply") && !catalog.revision().equals(args[3]))
        return map("accepted", false, "code", "stale_catalog");
      try {
        Path stage =
            configDirectory.resolve("dead-earth/director/staging").resolve(request + ".json");
        if (Files.isSymbolicLink(stage) || Files.isSymbolicLink(stage.getParent()))
          throw new IllegalArgumentException("stage");
        JsonObject document = JsonFiles.read(stage, 1048576);
        CinematicCatalog next = CinematicCatalog.parse(document.toString());
        world.validate(next);
        if (action.equals("apply")) {
          JsonFiles.write(configDirectory.resolve("dead-earth/director/catalog.json"), document);
          catalog = next;
          catalogDocument = document;
          if (configuration != null) configuration.catalogChanged(document);
          checkpoint();
          Files.delete(stage);
        }
        return map(
            "accepted",
            true,
            "code",
            action.equals("apply") ? "applied" : "validated",
            "revision",
            next.revision(),
            "sourceHash",
            action.equals("apply") ? sourceHash() : JsonFiles.sha(document.toString()));
      } catch (Exception invalid) {
        return map("accepted", false, "code", "invalid_catalog");
      }
    }
    return map("accepted", false, "code", "invalid_arguments");
  }

  public Object command(String[] args) {
    requireThread();
    if (args.length == 5 && args[0].equals("blockhp")) {
      Object level = world.world(Integer.parseInt(args[1]));
      Object pos = Reflect.pos(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
      if (level == null || !Reflect.loaded(level, pos)) return map("error", "unloaded");
      Object block = Reflect.call(Reflect.state(level, pos), "getBlock/func_177230_c");
      if (dev.deadearth.legacy.defense.LegacyDefense.isDefense(block)) {
        int tier = ((dev.deadearth.legacy.defense.DefenseMaterial) block).tier();
        return map("block", Reflect.blockId(level, pos), "remaining",
            dev.deadearth.legacy.defense.LegacyDefense.remaining(level, pos), "maximum",
            dev.deadearth.legacy.defense.DefenseDamageStore.hp(tier));
      }
      return map("block", Reflect.blockId(level, pos), "remaining",
          dev.deadearth.legacy.defense.MaterialHp.remaining(level, pos), "maximum",
          dev.deadearth.legacy.defense.MaterialHp.maximum(level, pos));
    }
    if (args.length >= 1 && args[0].equals("bunker")) return bunkerCommand(args);
    if (args.length >= 1 && args[0].equals("config")) return configurationCommand(args);
    if (args.length == 2 && args[0].equals("evolution") && args[1].equals("status"))
      return evolution();
    if (args.length == 7 && args[0].equals("evolution") && args[1].equals("shift")) {
      String campaignId = args[2], epoch = args[3], revision = args[4], request = args[5];
      int delta = Integer.parseInt(args[6]);
      UUID.fromString(request);
      String fingerprint = String.join(" ", args);
      Campaign.Receipt receipt = campaign.receipts.get(request);
      if (receipt != null)
        return fingerprint.equals(receipt.fingerprint)
            ? receipt.result
            : map("accepted", false, "code", "conflicting_request", "evolution", evolution());
      String code =
          !campaign.campaignId.equals(campaignId)
              ? "stale_campaign"
              : !campaign.worldEpoch.equals(epoch)
                  ? "stale_epoch"
                  : campaign.revision != Long.parseLong(revision)
                      ? "stale_revision"
                      : Math.abs(delta) != 1
                          ? "invalid_delta"
                          : campaign.phase + delta < 0 || campaign.phase + delta > 10
                              ? "phase_limit"
                              : campaign.receipts.size() >= 2048 ? "journal_full" : "shifted";
      if (code.equals("shifted")) {
        changePhase(campaign.phase + delta, "operator", request);
        campaign.points = srp.thresholds[campaign.phase];
        checkpoint();
        for (Object w : world.worlds())
          if (settings.controls(Reflect.dimension(w))) srp.sync(w, campaign.phase, campaign.points);
      }
      Object result =
          map("accepted", code.equals("shifted"), "code", code, "evolution", evolution());
      if (campaign.receipts.size() < 2048) {
        campaign.receipts.put(request, new Campaign.Receipt(fingerprint, result));
        checkpoint();
      }
      return result;
    }
    if (args.length >= 2 && args[0].equals("operator")) return operator(args);
    if (args.length == 1 && args[0].equals("status")) return snapshot();
    if (args.length == 4 && args[0].equals("event")) {
      Object verdict =
          engine.admitManual(
              UUID.fromString(args[1]), args[2], args[3], catalog, context(), settings.enabled);
      checkpoint();
      return verdict;
    }
    return map(
        "error",
        "usage",
        "usage",
        "deadearth status | bunker status | bunker extend [1h|30m|60s|1d] | evolution status | evolution shift <campaign> <epoch> <revision>"
            + " <request> <-1|1> | event <request> <event> <zone>");
  }

  private Object bunkerCommand(String[] args) {
    requireThread();
    boolean extend = args.length >= 2 && args[1].equals("extend");
    if (!(args.length == 1 || (args.length == 2 && args[1].equals("status"))
        || (extend && (args.length == 2 || args.length == 3))))
      return map("accepted", false, "message", "Команды: /deadearth bunker status; /deadearth bunker extend 1h (также 30m, 60s, 1d; максимум 30 дней).");
    long before = campaign.bunkerProtectionUntilTicks;
    if (extend) {
      final long until;
      try {
        long duration = dev.deadearth.legacy.defense.BunkerProtection.durationTicks(
            args.length == 3 ? args[2] : "1h");
        until = dev.deadearth.legacy.defense.BunkerProtection.extendFromNow(
            campaign.activeTicks, settings.bunkerGraceTicks, before, duration);
      } catch (RuntimeException invalid) {
        return map("accepted", false, "message", "Неверный срок. Пример: /deadearth bunker extend 1h; доступны s, m, h, d, максимум 30 дней.");
      }
      campaign.bunkerProtectionUntilTicks = until;
      try { checkpoint(); }
      catch (RuntimeException failedSave) {
        campaign.bunkerProtectionUntilTicks = before;
        return map("accepted", false, "message", "Не удалось сохранить продление защиты; срок не изменён.");
      }
    }
    long remaining = dev.deadearth.legacy.defense.BunkerProtection.remaining(
        campaign.activeTicks, settings.bunkerGraceTicks, campaign.bunkerProtectionUntilTicks);
    return map("accepted", true, "protected", remaining > 0,
        "untilTicks", dev.deadearth.legacy.defense.BunkerProtection.deadline(
            settings.bunkerGraceTicks, campaign.bunkerProtectionUntilTicks),
        "remainingTicks", remaining,
        "message", remaining == 0 ? "Защита бункера неактивна. Продлить: /deadearth bunker extend 1h"
            : "Защита бункера активна. Осталось "
                + dev.deadearth.legacy.defense.BunkerProtection.describe(remaining)
                + " игрового времени. Без игроков таймер остановлен.");
  }
}
