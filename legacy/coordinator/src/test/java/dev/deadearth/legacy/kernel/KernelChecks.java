package dev.deadearth.legacy.kernel;

import static dev.deadearth.legacy.kernel.CinematicCatalog.*;
import static dev.deadearth.legacy.kernel.CinematicEngine.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** Standalone Java 8 checks: requires Gson only, no Minecraft or test framework. */
public final class KernelChecks {
  private static final String ZONE = "00000000-0000-0000-0000-000000000001";
  private static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static int checks;

  private KernelChecks() {}

  public static void main(String[] args) throws Exception {
    String source = fixture().toString();
    CinematicCatalog catalog = CinematicCatalog.parse(source);
    phasesAndEligibility(catalog);
    waveMinimumPhaseEligibility();
    idempotenceAndCooldowns(catalog);
    finiteAllocation(catalog);
    checkpointAndRestart(catalog);
    offlineAndUnloaded(catalog);
    signalMemory(catalog);
    frozenAndImmutable(catalog);
    strictCatalogs(source);
    invalidRestoredSignals(catalog);
    if (args.length > 0) {
      CinematicCatalog deployed =
          CinematicCatalog.parse(
              new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
      for (Event event : deployed.events().values()) {
        CinematicCatalog frozen =
            CinematicCatalog.parse(CinematicEngine.freeze(deployed, event.id()));
        check(event.equals(frozen.events().get(event.id())), "deployed frozen graph " + event.id());
        allPathsTerminate(frozen, event);
      }
      check(!deployed.events().isEmpty(), "deployed catalogue has events");
    }
    System.out.println("KernelChecks: " + checks + " checks passed");
  }

  private static Context context(long now, int online, int phase) {
    return new Context(
        now,
        online,
        "CALM",
        1.2,
        Collections.singletonList(new Zone(ZONE, 100, 0, 0, 0, true, false, 2, 3, 0)),
        phase);
  }

  private static Port admitted(CinematicEngine engine, CinematicCatalog catalog) {
    Goal goal = engine.brief(EPOCH, catalog, context(0, 2, 10), true);
    check(goal != null, "goal exists");
    Option selected = goal.options().get(0);
    Verdict admitted =
        engine.admit(
            UUID.randomUUID(),
            goal.id(),
            selected.eventId(),
            selected.zoneId(),
            EPOCH,
            catalog,
            context(0, 2, 10),
            true);
    check(admitted.accepted(), "admission accepted");
    return new Port(engine);
  }

  private static void phasesAndEligibility(CinematicCatalog catalog) {
    CinematicEngine engine = new CinematicEngine();
    check(!engine.options(catalog, context(0, 2, 10)).isEmpty(), "SRP phase ten allowed");
    check(!engine.options(catalog, context(0, 2, 0)).isEmpty(), "phase zero allows scouting");
    check(
        new Context(0, 2, "CALM", 1, Collections.<Zone>emptyList()).parasitePhase() == 10,
        "legacy constructor defaults to phase ten");
    reject(() -> context(0, 2, -1), "negative phase");
    reject(() -> context(0, 2, 11), "phase eleven");
    reject(
        () -> new Context(0, 1, "CALM", Double.NaN, Collections.<Zone>emptyList(), 0),
        "nonfinite pressure");
    check(engine.brief(EPOCH, catalog, context(0, 0, 10), true) == null, "offline has no goal");
    for (Zone zone :
        Arrays.asList(
            new Zone(ZONE, 1e8, 0, 0, 0, false, false, 2, 3, 0),
            new Zone(ZONE, 1e8, 0, 0, 0, true, true, 2, 3, 0),
            new Zone(ZONE, 1e8, 0, 0, 2401, true, false, 2, 3, 0),
            new Zone(ZONE, Double.NaN, 0, 0, 0, true, false, 2, 3, 0))) {
      check(
          engine
              .options(catalog, new Context(0, 2, "CALM", 1, Collections.singletonList(zone), 10))
              .isEmpty(),
          "unknown zone not eligible");
    }
  }

  private static void idempotenceAndCooldowns(CinematicCatalog catalog) {
    CinematicEngine engine = new CinematicEngine();
    UUID request = UUID.randomUUID();
    Goal goal = engine.brief(EPOCH, catalog, context(0, 2, 10), true);
    Option choice = goal.options().get(0);
    Verdict first =
        engine.admit(
            request,
            goal.id(),
            choice.eventId(),
            choice.zoneId(),
            EPOCH,
            catalog,
            context(0, 2, 10),
            true);
    Verdict repeated =
        engine.admit(
            request,
            goal.id(),
            choice.eventId(),
            choice.zoneId(),
            EPOCH,
            catalog,
            context(0, 2, 10),
            true);
    check(
        first.accepted() && first.operationId().equals(repeated.operationId()),
        "repeated UUID shares operation");
    check(
        !engine
            .admit(request, goal.id(), "different", ZONE, EPOCH, catalog, context(0, 2, 10), true)
            .accepted(),
        "conflicting UUID rejected");
    check(engine.operations().size() == 1, "one operation after replay");
    Port port = new Port(engine);
    check(engine.cancel(first.operationId(), 0, catalog.rules(), port), "cancellation recorded");
    Verdict recorded =
        engine.admitManual(request, choice.eventId(), ZONE, catalog, context(0, 2, 10), true);
    check(
        recorded.accepted() && "already_recorded".equals(recorded.code()),
        "history preserves idempotence");
    check(
        !engine
            .previewManual(catalog, context(199, 2, 10), true, choice.eventId(), ZONE)
            .accepted(),
        "zone/event cooldown enforced");
    check(
        engine.previewManual(catalog, context(200, 2, 10), true, choice.eventId(), ZONE).accepted(),
        "cooldown expires");
  }

  private static void waveMinimumPhaseEligibility() {
    JsonObject legacy = fixture();
    CinematicCatalog original = CinematicCatalog.parse(legacy.toString());
    check(
        original.waves().get("scouts").minimumPhase() == 0,
        "missing wave minimum phase defaults to zero");
    check(
        new Wave(Collections.singletonList("srparasites:buglin")).minimumPhase() == 0,
        "compatible wave constructor defaults to zero");
    JsonObject gated = fixture();
    gated.getAsJsonObject("waves").getAsJsonObject("scouts").addProperty("minimumPhase", 3);
    CinematicCatalog catalog = CinematicCatalog.parse(gated.toString());
    CinematicEngine engine = new CinematicEngine();
    check(
        engine.options(catalog, context(0, 2, 0)).isEmpty(),
        "phase zero hides event with locked wave pool");
    check(
        engine.options(catalog, context(0, 2, 2)).isEmpty(),
        "wave pool stays locked below its boundary");
    check(!engine.options(catalog, context(0, 2, 3)).isEmpty(), "wave pool unlocks at phase three");
    check(
        !engine.options(catalog, context(0, 2, 10)).isEmpty(),
        "wave pool remains unlocked at phase ten");
    check(
        !engine.previewManual(catalog, context(0, 2, 0), true, "scout", ZONE).accepted(),
        "manual preview respects wave phase floor");
    check(
        !engine
            .admitManual(UUID.randomUUID(), "scout", ZONE, catalog, context(0, 2, 0), true)
            .accepted(),
        "manual admission rejects locked wave pool");
    Goal goal = engine.brief(EPOCH, catalog, context(0, 2, 3), true);
    check(goal != null, "unlocked phase offers goal");
    Verdict downgraded =
        engine.admit(
            UUID.randomUUID(), goal.id(), "scout", ZONE, EPOCH, catalog, context(0, 2, 0), true);
    check(
        !downgraded.accepted() && engine.operations().isEmpty(),
        "admission rechecks wave phase after downgrade");
    CinematicCatalog frozen = CinematicCatalog.parse(CinematicEngine.freeze(catalog, "scout"));
    check(
        frozen.waves().get("scouts").minimumPhase() == 3
            && frozen.waves().get("scouts").equals(catalog.waves().get("scouts")),
        "frozen catalogue retains wave phase floor");
    check(
        engine
            .admitManual(UUID.randomUUID(), "scout", ZONE, catalog, context(0, 2, 3), true)
            .accepted(),
        "phase three admits unlocked wave pool");

    JsonObject any = fixture();
    JsonObject high = new JsonObject();
    JsonArray entities = new JsonArray();
    entities.add("srparasites:pri_reeker");
    high.add("entities", entities);
    high.addProperty("minimumPhase", 5);
    any.getAsJsonObject("waves").add("higher", high);
    node(any, 1).getAsJsonArray("actions").get(1).getAsJsonObject().addProperty("key", "higher");
    CinematicCatalog both = CinematicCatalog.parse(any.toString());
    check(
        new CinematicEngine().options(both, context(0, 2, 3)).isEmpty(),
        "any locked pool blocks an event even with another unlocked wave");
    check(
        !new CinematicEngine().options(both, context(0, 2, 5)).isEmpty(),
        "all wave pools unlock at maximum required floor");
    for (double minimum : new double[] {-1, 11, 1.5}) {
      JsonObject invalid = fixture();
      invalid
          .getAsJsonObject("waves")
          .getAsJsonObject("scouts")
          .addProperty("minimumPhase", minimum);
      reject(
          () -> CinematicCatalog.parse(invalid.toString()),
          "invalid wave minimum phase " + minimum);
    }
    JsonObject stringFloor = fixture();
    stringFloor.getAsJsonObject("waves").getAsJsonObject("scouts").addProperty("minimumPhase", "3");
    reject(
        () -> CinematicCatalog.parse(stringFloor.toString()),
        "wave minimum phase is typed numeric integer");
    reject(
        () -> new Wave(Collections.singletonList("srparasites:buglin"), -1),
        "negative constructor wave phase");
    reject(
        () -> new Wave(Collections.singletonList("srparasites:buglin"), 11),
        "constructor wave phase eleven");
  }

  private static void finiteAllocation(CinematicCatalog catalog) {
    CinematicEngine engine = new CinematicEngine();
    Port port = admitted(engine, catalog);
    int budget = engine.operations().get(0).budget();
    for (int tick = 0; tick < 200 && !engine.operations().isEmpty(); tick++) {
      engine.tick(catalog, context(tick, 2, 10), port);
      port.living = 0; // Casualties cannot replenish an already spent allocation.
    }
    check(engine.operations().isEmpty(), "finite graph terminates");
    check(port.spawned > 0 && port.spawned <= budget, "total wave head count bounded");
    check(
        engine.history().get(0).spent() == port.spawned,
        "spent allocation equals requested emissions");
    int before = port.spawned;
    for (int tick = 200; tick < 400; tick++) engine.tick(catalog, context(tick, 2, 10), port);
    check(before == port.spawned, "casualties never trigger replacement waves");

    CinematicEngine capped = new CinematicEngine();
    Port occupied = admitted(capped, catalog);
    occupied.living = catalog.rules().maxEntities();
    for (int tick = 0; tick < 200 && !capped.operations().isEmpty(); tick++)
      capped.tick(catalog, context(tick, 2, 10), occupied);
    check(occupied.spawned == 0, "living owned entity cap prevents allocation");
  }

  private static void checkpointAndRestart(CinematicCatalog catalog) {
    CinematicEngine engine = new CinematicEngine();
    Port port = admitted(engine, catalog);
    engine.tick(catalog, context(0, 2, 10), port);
    check(port.emissions == 1, "one physical effect");
    Snapshot applying = null;
    for (Snapshot barrier : port.barriers)
      if (!barrier.operations().isEmpty()
          && barrier.operations().get(0).state() == State.APPLYING) {
        applying = barrier;
        break;
      }
    check(applying != null, "APPLYING was checkpointed");
    CinematicEngine restored = CinematicEngine.restore(applying);
    Port after = new Port(restored);
    restored.tick(catalog, context(20, 2, 10), after);
    check(
        after.emissions == 0 && restored.operations().isEmpty(),
        "APPLYING never replays after restart");
    check(
        "uncertain".equals(restored.history().get(0).outcome()),
        "restart preserves uncertain outcome");
    Snapshot decoded = new Gson().fromJson(new Gson().toJson(engine.snapshot()), Snapshot.class);
    check(
        CinematicEngine.restore(decoded).operations().get(0).cursor() == 1,
        "Gson 2.8 snapshot resumes next cursor");

    CinematicEngine failing = new CinematicEngine();
    admitted(failing, catalog);
    Port failure =
        new Port(failing) {
          @Override
          public void checkpoint() {
            super.checkpoint();
            if (!engine.operations().isEmpty()
                && engine.operations().get(0).state() == State.APPLYING)
              throw new IllegalStateException("disk failure");
          }
        };
    rejectState(
        () -> failing.tick(catalog, context(0, 2, 10), failure), "failed barrier propagates");
    check(failure.emissions == 0, "failed barrier prevents effect");
    Port recovered = new Port(failing);
    failing.tick(catalog, context(1, 2, 10), recovered);
    check(
        recovered.emissions == 0 && "uncertain".equals(failing.history().get(0).outcome()),
        "failed checkpoint never bypassed on next tick");
  }

  private static void offlineAndUnloaded(CinematicCatalog catalog) {
    CinematicEngine engine = new CinematicEngine();
    Port port = admitted(engine, catalog);
    engine.tick(catalog, context(1000, 0, 10), port);
    check(
        port.emissions == 0 && engine.operations().get(0).state() == State.QUEUED,
        "no players pauses queue and expiry");
    port.ready = false;
    engine.tick(catalog, context(1, 2, 10), port);
    check(port.emissions == 0 && engine.operations().size() == 1, "unloaded target pauses effects");
    engine.tick(catalog, context(1000, 2, 10), port);
    check(
        engine.operations().isEmpty() && "expired".equals(engine.history().get(0).outcome()),
        "online virtual clock bounds pause");
  }

  private static void signalMemory(CinematicCatalog catalog) {
    CinematicEngine engine = new CinematicEngine();
    Port port = admitted(engine, catalog);
    port.signals = Collections.singleton("burn");
    engine.tick(catalog, context(0, 2, 10), port);
    port.signals = Collections.emptySet();
    for (int tick = 1; tick <= 4; tick++) engine.tick(catalog, context(tick, 2, 10), port);
    check(
        "retreat".equals(engine.operations().get(0).node()),
        "one shot interaction remembered during cues");
    engine.tick(catalog, context(5, 2, 10), port);
    check(
        "avoided".equals(engine.history().get(0).outcome()), "remembered interaction takes branch");

    CinematicEngine waiting = new CinematicEngine();
    Port quiet = admitted(waiting, catalog);
    waiting.tick(catalog, context(0, 2, 10), quiet);
    waiting.tick(catalog, context(100, 2, 10), quiet);
    waiting.tick(catalog, context(200, 2, 10), quiet);
    waiting.tick(catalog, context(300, 2, 10), quiet);
    waiting.tick(catalog, context(310, 2, 10), quiet);
    check(
        "cue".equals(waiting.operations().get(0).node()), "reaction window starts after final cue");
    waiting.tick(catalog, context(320, 2, 10), quiet);
    check(
        "assault".equals(waiting.operations().get(0).node()),
        "reaction timeout measured from last cue");
  }

  private static void frozenAndImmutable(CinematicCatalog catalog) {
    CinematicCatalog frozen = CinematicCatalog.parse(CinematicEngine.freeze(catalog, "scout"));
    check(
        catalog.events().get("scout").equals(frozen.events().get("scout")),
        "frozen graph retains structural equality");
    unsupported(() -> catalog.events().clear(), "immutable catalogue events");
    unsupported(() -> catalog.events().get("scout").nodes().clear(), "immutable graph");
    unsupported(
        () -> catalog.waves().get("scouts").entities().add("srparasites:unknown"),
        "immutable wave data");
    CinematicEngine engine = new CinematicEngine();
    admitted(engine, catalog);
    String original = engine.operations().get(0).frozenCatalog();
    JsonObject changed = fixture();
    changed.getAsJsonObject("sounds").getAsJsonObject("cue").addProperty("volume", 1.9);
    CinematicCatalog replacement = CinematicCatalog.parse(changed.toString());
    Port port = new Port(engine);
    engine.tick(replacement, context(0, 2, 10), port);
    check(
        original.equals(engine.operations().get(0).frozenCatalog()),
        "active operation retains original catalogue");
    check(
        port.effectCatalog.equals(engine.frozen(engine.operations().get(0)).revision()),
        "effects receive frozen catalogue");
    Snapshot decoded = new Gson().fromJson(new Gson().toJson(engine.snapshot()), Snapshot.class);
    CinematicEngine restored = CinematicEngine.restore(decoded);
    unsupported(
        () -> restored.operations().get(0).latched().add("burn"),
        "decoded signals normalized immutable");
  }

  private static void strictCatalogs(String source) {
    reject(() -> CinematicCatalog.strict("{\"x\":1,\"x\":2}"), "duplicate object key");
    reject(() -> CinematicCatalog.strict("{x:1}"), "unquoted key");
    reject(() -> CinematicCatalog.strict("{} {}"), "trailing document");
    reject(() -> CinematicCatalog.strict("{\"x\":null}"), "null not in typed vocabulary");
    reject(() -> CinematicCatalog.strict("[NaN]"), "nonfinite JSON");
    reject(() -> CinematicCatalog.strict("{}", 1), "byte limit");
    JsonObject extra = object(source);
    extra.addProperty("run_command", "op player");
    reject(() -> CinematicCatalog.parse(extra.toString()), "command field");
    JsonObject badFaction = object(source);
    event(badFaction).addProperty("faction", "forest");
    reject(() -> CinematicCatalog.parse(badFaction.toString()), "removed forest faction");
    for (String faction : Arrays.asList("parasites", "siege", "mixed")) {
      JsonObject valid = object(source);
      event(valid).addProperty("faction", faction);
      check(
          CinematicCatalog.parse(valid.toString()).events().get("scout").faction().equals(faction),
          "fork faction " + faction);
    }
    JsonObject cycle = object(source);
    node(cycle, 0).addProperty("timeout", "cue");
    reject(() -> CinematicCatalog.parse(cycle.toString()), "cyclic graph");
    JsonObject dangling = object(source);
    node(dangling, 0).addProperty("timeout", "missing");
    reject(() -> CinematicCatalog.parse(dangling.toString()), "missing node");
    JsonObject command = object(source);
    node(command, 0)
        .getAsJsonArray("actions")
        .get(0)
        .getAsJsonObject()
        .addProperty("type", "command");
    reject(() -> CinematicCatalog.parse(command.toString()), "executable action type");
    JsonObject typed = object(source);
    typed.getAsJsonObject("rules").addProperty("maxWave", "8");
    reject(() -> CinematicCatalog.parse(typed.toString()), "numeric string");
    JsonObject old = object(source);
    old.getAsJsonObject("rules")
        .getAsJsonObject("coefficients")
        .remove("maximumApproachHeightDifference");
    check(
        CinematicCatalog.parse(old.toString()).rules().count("maximumApproachHeightDifference")
            == 12,
        "old elevation default");
    check(
        !CinematicCatalog.validTrigger(null) && !CinematicCatalog.validTrigger("near:invalid"),
        "strict role signals");
  }

  private static void invalidRestoredSignals(CinematicCatalog catalog) {
    CinematicEngine engine = new CinematicEngine();
    admitted(engine, catalog);
    JsonObject json = object(new Gson().toJson(engine.snapshot()));
    json.getAsJsonArray("operations").get(0).getAsJsonObject().add("latched", JsonNull.INSTANCE);
    Snapshot invalid = new Gson().fromJson(json, Snapshot.class);
    reject(() -> CinematicEngine.restore(invalid), "null decoded signals rejected");
    json = object(new Gson().toJson(engine.snapshot()));
    JsonArray signals = new JsonArray();
    signals.add("command");
    json.getAsJsonArray("operations").get(0).getAsJsonObject().add("latched", signals);
    Snapshot invalidSignal = new Gson().fromJson(json, Snapshot.class);
    reject(() -> CinematicEngine.restore(invalidSignal), "untyped decoded signal rejected");

    Operation pending = engine.operations().get(0);
    Operation applying =
        new Operation(
            pending.id(),
            pending.requestId(),
            pending.frozenCatalog(),
            pending.eventId(),
            pending.zoneId(),
            State.APPLYING,
            pending.node(),
            pending.cursor(),
            pending.created(),
            pending.expires(),
            pending.entered(),
            pending.budget(),
            pending.spent(),
            pending.outcome(),
            pending.latched());
    List<History> full = new ArrayList<History>();
    for (int i = 0; i < 512; i++)
      full.add(new History(UUID.randomUUID(), UUID.randomUUID(), "scout", ZONE, 0, "engaged", 0));
    CinematicEngine bounded =
        CinematicEngine.restore(
            new Snapshot(Collections.singletonList(applying), full, 0, 0, 0, 0));
    check(
        bounded.history().size() == 512
            && bounded.history().get(511).requestId().equals(applying.requestId()),
        "recent uncertain receipt survives full history");
    Snapshot duplicateOperation =
        new Snapshot(
            Arrays.asList(
                applying,
                new Operation(
                    applying.id(),
                    UUID.randomUUID(),
                    applying.frozenCatalog(),
                    applying.eventId(),
                    applying.zoneId(),
                    State.APPLYING,
                    applying.node(),
                    applying.cursor(),
                    applying.created(),
                    applying.expires(),
                    applying.entered(),
                    applying.budget(),
                    applying.spent(),
                    applying.outcome(),
                    applying.latched())),
            Collections.<History>emptyList(),
            0,
            0,
            0,
            0);
    reject(() -> CinematicEngine.restore(duplicateOperation), "duplicate interrupted operation id");
  }

  private static void allPathsTerminate(CinematicCatalog catalog, Event event) {
    String season = event.seasons().iterator().next();
    for (String signal :
        Arrays.asList(
            "",
            "near",
            "far",
            "inspect",
            "break",
            "burn",
            "water",
            "mine",
            "repair",
            "clear",
            "near:rear",
            "inspect:flank")) {
      CinematicEngine engine = new CinematicEngine();
      Context initial =
          new Context(
              0,
              6,
              season,
              1.5,
              Collections.singletonList(new Zone(ZONE, 1e12, 0, 0, 0, true, false, 6, 10, 0)),
              10);
      Verdict verdict =
          engine.admitManual(UUID.randomUUID(), event.id(), ZONE, catalog, initial, true);
      check(verdict.accepted(), "deployed manual admission " + event.id());
      Port port = new Port(engine);
      port.signals =
          signal.length() == 0 ? Collections.<String>emptySet() : Collections.singleton(signal);
      int budget = engine.operations().get(0).budget();
      long last = (long) event.lifetimeTicks() + 20;
      for (long tick = 0; tick <= last && !engine.operations().isEmpty(); tick += 20) {
        engine.tick(catalog, new Context(tick, 6, season, 1.5, initial.zones(), 10), port);
        port.living = 0;
      }
      check(engine.operations().isEmpty(), "deployed bounded graph " + event.id() + "/" + signal);
      check(port.spawned <= budget, "deployed finite allocation " + event.id());
      check(
          !"uncertain".equals(engine.history().get(0).outcome()),
          "deployed physical API " + event.id());
    }
  }

  private static class Port implements World {
    final CinematicEngine engine;
    boolean ready = true;
    int living, emissions, spawned;
    String effectCatalog;
    Set<String> signals = Collections.emptySet();
    final List<Snapshot> barriers = new ArrayList<Snapshot>();

    Port(CinematicEngine engine) {
      this.engine = engine;
    }

    public boolean ready(Operation operation) {
      return ready;
    }

    public Set<String> signals(Operation operation) {
      return signals;
    }

    public int livingOwned() {
      return living;
    }

    public void emit(Operation operation, Action action, int count, CinematicCatalog frozen) {
      check(operation.state() == State.APPLYING, "effect observes APPLYING");
      check(
          !barriers.isEmpty()
              && barriers.get(barriers.size() - 1).operations().get(0).state() == State.APPLYING,
          "durable barrier before effect");
      check(count >= 0 && operation.spent() <= operation.budget(), "finite nonnegative allocation");
      emissions++;
      spawned += count;
      living += count;
      effectCatalog = frozen.revision();
    }

    public void checkpoint() {
      barriers.add(engine.snapshot());
    }
  }

  private static JsonObject fixture() {
    JsonObject root = new JsonObject();
    root.addProperty("schema", 1);
    JsonObject rules = new JsonObject();
    String[] names = {
      "decisionTicks",
      "goalTicks",
      "maxActive",
      "maxQueued",
      "historyLimit",
      "zoneCooldownTicks",
      "recoveryTicks",
      "heavyCooldownTicks",
      "maximumZoneAgeTicks",
      "maxWave",
      "maxEntities",
      "baseBudget",
      "scoreWeight",
      "playerWeight",
      "pressureWeight",
      "lossWeight",
      "signalRadius",
      "spawnMinDistance",
      "maxEffectsPerTick"
    };
    double[] values = {200, 200, 1, 2, 16, 200, 200, 200, 2400, 8, 6, 8, 0, 0, 0, 1, 32, 16, 2};
    for (int i = 0; i < names.length; i++) rules.addProperty(names[i], values[i]);
    JsonObject coefficients = new JsonObject();
    String[] keys = {
      "valueNormalization",
      "defenseNormalization",
      "defenseBudgetWeight",
      "playerLevelWeight",
      "playerArmorWeight",
      "deathMemoryTicks",
      "playerRadius",
      "nearRadius",
      "farRadius",
      "restAfter",
      "minimumTraceDistance",
      "maximumLoadedTraces",
      "entityLifetimeTailTicks",
      "approachAttempts",
      "modelOptionLimit",
      "optionsPerZone",
      "affinityLimit",
      "playerScoreMaximum",
      "lossMaximum",
      "retryTicks",
      "maximumApproachHeightDifference"
    };
    double[] weights = {10, 10, 1, 1, 1, 200, 64, 4, 32, 2, 4, 8, 20, 4, 8, 2, 4, 10, 10, 20, 12};
    for (int i = 0; i < keys.length; i++) coefficients.addProperty(keys[i], weights[i]);
    rules.add("coefficients", coefficients);
    root.add("rules", rules);
    JsonObject sounds = new JsonObject();
    JsonObject sound = new JsonObject();
    sound.addProperty("sound", "minecraft:entity.zombie.ambient");
    sound.addProperty("volume", 1);
    sound.addProperty("pitch", 1);
    sounds.add("cue", sound);
    root.add("sounds", sounds);
    JsonObject traces = new JsonObject();
    JsonObject trace = new JsonObject();
    JsonArray mask = new JsonArray();
    for (String row : Arrays.asList(".11.", "1221", "1221", ".11.")) mask.add(row);
    trace.add("mask", mask);
    trace.addProperty("color", 12345);
    traces.add("trail", trace);
    root.add("traces", traces);
    JsonObject waves = new JsonObject();
    JsonObject wave = new JsonObject();
    JsonArray entities = new JsonArray();
    entities.add("srparasites:sim_human");
    wave.add("entities", entities);
    waves.add("scouts", wave);
    root.add("waves", waves);
    JsonObject event = new JsonObject();
    event.addProperty("id", "scout");
    event.addProperty("title", "Scouting echoes");
    event.addProperty("tactic", "lure");
    event.addProperty("faction", "parasites");
    event.addProperty("weight", 1);
    event.addProperty("minScore", 0);
    event.addProperty("minPlayers", 1);
    JsonArray seasons = new JsonArray();
    seasons.add("CALM");
    seasons.add("HEAT");
    seasons.add("ASH_WINTER");
    event.add("seasons", seasons);
    event.addProperty("cooldownTicks", 200);
    event.addProperty("lifetimeTicks", 600);
    event.addProperty("heavy", false);
    event.add("affinity", new JsonObject());
    event.addProperty("start", "cue");
    JsonArray nodes = new JsonArray();
    JsonArray cues = new JsonArray();
    cues.add(action("sound", "cue", 0));
    cues.add(action("trace", "trail", 0));
    cues.add(action("particles", "spores", 0));
    cues.add(action("move", "flank", 0));
    JsonArray branches = new JsonArray();
    JsonObject branch = new JsonObject();
    branch.addProperty("when", "burn");
    branch.addProperty("next", "retreat");
    branches.add(branch);
    nodes.add(node("cue", cues, 20, branches, "assault"));
    JsonArray attack = new JsonArray();
    attack.add(action("wave", "scouts", .6));
    attack.add(action("wave", "scouts", .6));
    nodes.add(node("assault", attack, 1, new JsonArray(), "done"));
    JsonArray retreat = new JsonArray();
    retreat.add(action("finish", "avoided", 0));
    nodes.add(node("retreat", retreat, 0, new JsonArray(), ""));
    JsonArray finish = new JsonArray();
    finish.add(action("finish", "engaged", 0));
    nodes.add(node("done", finish, 0, new JsonArray(), ""));
    event.add("nodes", nodes);
    JsonArray events = new JsonArray();
    events.add(event);
    root.add("events", events);
    return root;
  }

  private static JsonObject action(String type, String key, double portion) {
    JsonObject action = new JsonObject();
    action.addProperty("type", type);
    action.addProperty("key", key);
    action.addProperty("role", "approach");
    action.addProperty("portion", portion);
    return action;
  }

  private static JsonObject node(
      String id, JsonArray actions, int wait, JsonArray branches, String timeout) {
    JsonObject node = new JsonObject();
    node.addProperty("id", id);
    node.add("actions", actions);
    node.addProperty("waitTicks", wait);
    node.add("branches", branches);
    node.addProperty("timeout", timeout);
    return node;
  }

  private static JsonObject object(String source) {
    return new JsonParser().parse(source).getAsJsonObject();
  }

  private static JsonObject event(JsonObject root) {
    return root.getAsJsonArray("events").get(0).getAsJsonObject();
  }

  private static JsonObject node(JsonObject root, int index) {
    return event(root).getAsJsonArray("nodes").get(index).getAsJsonObject();
  }

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private static void reject(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      checks++;
      return;
    }
    throw new AssertionError("Expected rejection: " + message);
  }

  private static void rejectState(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalStateException expected) {
      checks++;
      return;
    }
    throw new AssertionError("Expected barrier failure: " + message);
  }

  private static void unsupported(Runnable action, String message) {
    try {
      action.run();
    } catch (UnsupportedOperationException expected) {
      checks++;
      return;
    }
    throw new AssertionError("Expected immutable value: " + message);
  }
}
