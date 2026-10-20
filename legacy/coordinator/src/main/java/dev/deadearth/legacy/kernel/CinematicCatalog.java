package dev.deadearth.legacy.kernel;

import static dev.deadearth.legacy.kernel.KernelSupport.*;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A finite, typed vocabulary. Content files are data, never commands or executable expressions. */
public final class CinematicCatalog extends KernelValue {
  public final String revision;
  public final Rules rules;
  public final Map<String, Sound> sounds;
  public final Map<String, Trace> traces;
  public final Map<String, Wave> waves;
  public final Map<String, Event> events;

  public CinematicCatalog(
      String revision,
      Rules rules,
      Map<String, Sound> sounds,
      Map<String, Trace> traces,
      Map<String, Wave> waves,
      Map<String, Event> events) {
    this.revision = revision;
    this.rules = rules;
    this.sounds = immutableMap(sounds);
    this.traces = immutableMap(traces);
    this.waves = immutableMap(waves);
    this.events = immutableMap(events);
  }

  public String revision() {
    return revision;
  }

  public Rules rules() {
    return rules;
  }

  public Map<String, Sound> sounds() {
    return sounds;
  }

  public Map<String, Trace> traces() {
    return traces;
  }

  public Map<String, Wave> waves() {
    return waves;
  }

  public Map<String, Event> events() {
    return events;
  }

  @Override
  protected Object[] values() {
    return new Object[] {revision, rules, sounds, traces, waves, events};
  }

  public static final int MAX_BYTES = 1048576;
  public static final Set<String> ROLES = set("approach", "flank", "rear", "distraction");
  public static final Set<String> TRIGGERS =
      set("near", "far", "inspect", "break", "burn", "water", "mine", "repair", "clear");
  public static final Set<String> OUTCOMES =
      set("repelled", "contained", "avoided", "engaged", "inconclusive");

  public static final class Rules extends KernelValue {
    public final int decisionTicks;
    public final int goalTicks;
    public final int maxActive;
    public final int maxQueued;
    public final int historyLimit;
    public final int zoneCooldownTicks;
    public final int recoveryTicks;
    public final int heavyCooldownTicks;
    public final int maximumZoneAgeTicks;
    public final int maxWave;
    public final int maxEntities;
    public final int baseBudget;
    public final double scoreWeight;
    public final double playerWeight;
    public final double pressureWeight;
    public final double lossWeight;
    public final int signalRadius;
    public final int spawnMinDistance;
    public final int maxEffectsPerTick;
    public final Map<String, Double> coefficients;

    public Rules(
        int decisionTicks,
        int goalTicks,
        int maxActive,
        int maxQueued,
        int historyLimit,
        int zoneCooldownTicks,
        int recoveryTicks,
        int heavyCooldownTicks,
        int maximumZoneAgeTicks,
        int maxWave,
        int maxEntities,
        int baseBudget,
        double scoreWeight,
        double playerWeight,
        double pressureWeight,
        double lossWeight,
        int signalRadius,
        int spawnMinDistance,
        int maxEffectsPerTick,
        Map<String, Double> coefficients) {
      this.decisionTicks = decisionTicks;
      this.goalTicks = goalTicks;
      this.maxActive = maxActive;
      this.maxQueued = maxQueued;
      this.historyLimit = historyLimit;
      this.zoneCooldownTicks = zoneCooldownTicks;
      this.recoveryTicks = recoveryTicks;
      this.heavyCooldownTicks = heavyCooldownTicks;
      this.maximumZoneAgeTicks = maximumZoneAgeTicks;
      this.maxWave = maxWave;
      this.maxEntities = maxEntities;
      this.baseBudget = baseBudget;
      this.scoreWeight = scoreWeight;
      this.playerWeight = playerWeight;
      this.pressureWeight = pressureWeight;
      this.lossWeight = lossWeight;
      this.signalRadius = signalRadius;
      this.spawnMinDistance = spawnMinDistance;
      this.maxEffectsPerTick = maxEffectsPerTick;
      this.coefficients = immutableMap(coefficients);
    }

    public int decisionTicks() {
      return decisionTicks;
    }

    public int goalTicks() {
      return goalTicks;
    }

    public int maxActive() {
      return maxActive;
    }

    public int maxQueued() {
      return maxQueued;
    }

    public int historyLimit() {
      return historyLimit;
    }

    public int zoneCooldownTicks() {
      return zoneCooldownTicks;
    }

    public int recoveryTicks() {
      return recoveryTicks;
    }

    public int heavyCooldownTicks() {
      return heavyCooldownTicks;
    }

    public int maximumZoneAgeTicks() {
      return maximumZoneAgeTicks;
    }

    public int maxWave() {
      return maxWave;
    }

    public int maxEntities() {
      return maxEntities;
    }

    public int baseBudget() {
      return baseBudget;
    }

    public double scoreWeight() {
      return scoreWeight;
    }

    public double playerWeight() {
      return playerWeight;
    }

    public double pressureWeight() {
      return pressureWeight;
    }

    public double lossWeight() {
      return lossWeight;
    }

    public int signalRadius() {
      return signalRadius;
    }

    public int spawnMinDistance() {
      return spawnMinDistance;
    }

    public int maxEffectsPerTick() {
      return maxEffectsPerTick;
    }

    public Map<String, Double> coefficients() {
      return coefficients;
    }

    @Override
    protected Object[] values() {
      return new Object[] {
        decisionTicks,
        goalTicks,
        maxActive,
        maxQueued,
        historyLimit,
        zoneCooldownTicks,
        recoveryTicks,
        heavyCooldownTicks,
        maximumZoneAgeTicks,
        maxWave,
        maxEntities,
        baseBudget,
        scoreWeight,
        playerWeight,
        pressureWeight,
        lossWeight,
        signalRadius,
        spawnMinDistance,
        maxEffectsPerTick,
        coefficients
      };
    }

    public double coefficient(String key) {
      return coefficients.get(key);
    }

    public int count(String key) {
      return coefficients.get(key).intValue();
    }
  }

  public static final class Sound extends KernelValue {
    public final String sound;
    public final double volume;
    public final double pitch;

    public Sound(String sound, double volume, double pitch) {
      this.sound = sound;
      this.volume = volume;
      this.pitch = pitch;
    }

    public String sound() {
      return sound;
    }

    public double volume() {
      return volume;
    }

    public double pitch() {
      return pitch;
    }

    @Override
    protected Object[] values() {
      return new Object[] {sound, volume, pitch};
    }
  }

  public static final class Trace extends KernelValue {
    public final List<String> mask;
    public final int color;

    public Trace(List<String> mask, int color) {
      this.mask = immutableList(mask);
      this.color = color;
    }

    public List<String> mask() {
      return mask;
    }

    public int color() {
      return color;
    }

    @Override
    protected Object[] values() {
      return new Object[] {mask, color};
    }
  }

  public static final class Wave extends KernelValue {
    public final List<String> entities;
    public final int minimumPhase;

    public Wave(List<String> entities) {
      this(entities, 0);
    }

    public Wave(List<String> entities, int minimumPhase) {
      if (minimumPhase < 0 || minimumPhase > 10) throw bad("wave minimum phase");
      this.entities = immutableList(entities);
      this.minimumPhase = minimumPhase;
    }

    public List<String> entities() {
      return entities;
    }

    public int minimumPhase() {
      return minimumPhase;
    }

    @Override
    protected Object[] values() {
      return new Object[] {entities, minimumPhase};
    }
  }

  public static final class Action extends KernelValue {
    public final String type;
    public final String key;
    public final String role;
    public final double portion;

    public Action(String type, String key, String role, double portion) {
      this.type = type;
      this.key = key;
      this.role = role;
      this.portion = portion;
    }

    public String type() {
      return type;
    }

    public String key() {
      return key;
    }

    public String role() {
      return role;
    }

    public double portion() {
      return portion;
    }

    @Override
    protected Object[] values() {
      return new Object[] {type, key, role, portion};
    }
  }

  public static final class Branch extends KernelValue {
    public final String when;
    public final String next;

    public Branch(String when, String next) {
      this.when = when;
      this.next = next;
    }

    public String when() {
      return when;
    }

    public String next() {
      return next;
    }

    @Override
    protected Object[] values() {
      return new Object[] {when, next};
    }
  }

  public static final class Node extends KernelValue {
    public final String id;
    public final List<Action> actions;
    public final int waitTicks;
    public final List<Branch> branches;
    public final String timeout;

    public Node(
        String id, List<Action> actions, int waitTicks, List<Branch> branches, String timeout) {
      this.id = id;
      this.actions = immutableList(actions);
      this.waitTicks = waitTicks;
      this.branches = immutableList(branches);
      this.timeout = timeout;
    }

    public String id() {
      return id;
    }

    public List<Action> actions() {
      return actions;
    }

    public int waitTicks() {
      return waitTicks;
    }

    public List<Branch> branches() {
      return branches;
    }

    public String timeout() {
      return timeout;
    }

    @Override
    protected Object[] values() {
      return new Object[] {id, actions, waitTicks, branches, timeout};
    }
  }

  public static final class Event extends KernelValue {
    public final String id;
    public final String title;
    public final String tactic;
    public final String faction;
    public final double weight;
    public final double minScore;
    public final int minPlayers;
    public final Set<String> seasons;
    public final int cooldownTicks;
    public final int lifetimeTicks;
    public final boolean heavy;
    public final Map<String, Double> affinity;
    public final String start;
    public final Map<String, Node> nodes;

    public Event(
        String id,
        String title,
        String tactic,
        String faction,
        double weight,
        double minScore,
        int minPlayers,
        Set<String> seasons,
        int cooldownTicks,
        int lifetimeTicks,
        boolean heavy,
        Map<String, Double> affinity,
        String start,
        Map<String, Node> nodes) {
      this.id = id;
      this.title = title;
      this.tactic = tactic;
      this.faction = faction;
      this.weight = weight;
      this.minScore = minScore;
      this.minPlayers = minPlayers;
      this.seasons = immutableSet(seasons);
      this.cooldownTicks = cooldownTicks;
      this.lifetimeTicks = lifetimeTicks;
      this.heavy = heavy;
      this.affinity = immutableMap(affinity);
      this.start = start;
      this.nodes = immutableMap(nodes);
    }

    public String id() {
      return id;
    }

    public String title() {
      return title;
    }

    public String tactic() {
      return tactic;
    }

    public String faction() {
      return faction;
    }

    public double weight() {
      return weight;
    }

    public double minScore() {
      return minScore;
    }

    public int minPlayers() {
      return minPlayers;
    }

    public Set<String> seasons() {
      return seasons;
    }

    public int cooldownTicks() {
      return cooldownTicks;
    }

    public int lifetimeTicks() {
      return lifetimeTicks;
    }

    public boolean heavy() {
      return heavy;
    }

    public Map<String, Double> affinity() {
      return affinity;
    }

    public String start() {
      return start;
    }

    public Map<String, Node> nodes() {
      return nodes;
    }

    @Override
    protected Object[] values() {
      return new Object[] {
        id,
        title,
        tactic,
        faction,
        weight,
        minScore,
        minPlayers,
        seasons,
        cooldownTicks,
        lifetimeTicks,
        heavy,
        affinity,
        start,
        nodes
      };
    }
  }

  public static CinematicCatalog parse(String source) {
    JsonObject root = object(strict(source), "catalog");
    fields(root, "schema", "rules", "sounds", "traces", "waves", "events");
    if (integer(root, "schema", 1, 1) != 1) throw bad("schema");
    JsonObject r = object(root.get("rules"), "rules");
    fields(
        r,
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
        "maxEffectsPerTick",
        "coefficients");
    Rules rules =
        new Rules(
            integer(r, "decisionTicks", 200, 144000),
            integer(r, "goalTicks", 200, 72000),
            integer(r, "maxActive", 1, 3),
            integer(r, "maxQueued", 1, 16),
            integer(r, "historyLimit", 16, 512),
            integer(r, "zoneCooldownTicks", 200, 1728000),
            integer(r, "recoveryTicks", 200, 1728000),
            integer(r, "heavyCooldownTicks", 200, 1728000),
            integer(r, "maximumZoneAgeTicks", 20, 72000),
            integer(r, "maxWave", 1, 32),
            integer(r, "maxEntities", 1, 64),
            integer(r, "baseBudget", 1, 16),
            number(r, "scoreWeight", 0, 10),
            number(r, "playerWeight", 0, 10),
            number(r, "pressureWeight", 0, 10),
            number(r, "lossWeight", 0, 10),
            integer(r, "signalRadius", 8, 96),
            integer(r, "spawnMinDistance", 12, 64),
            integer(r, "maxEffectsPerTick", 1, 8),
            coefficients(r));
    TreeMap<String, Sound> sounds = new TreeMap<String, Sound>();
    for (Map.Entry<String, JsonElement> e : entries(root, "sounds", 1, 160)) {
      JsonObject o = object(e.getValue(), "sound");
      fields(o, "sound", "volume", "pitch");
      sounds.put(
          id(e.getKey()),
          new Sound(
              resource(text(o, "sound", 128)),
              number(o, "volume", .05, 2),
              number(o, "pitch", .5, 2)));
    }
    TreeMap<String, Trace> traces = new TreeMap<String, Trace>();
    for (Map.Entry<String, JsonElement> e : entries(root, "traces", 1, 96)) {
      JsonObject o = object(e.getValue(), "trace");
      fields(o, "mask", "color");
      ArrayList<String> rows = new ArrayList<String>();
      for (JsonElement row : array(o, "mask", 4, 16)) {
        String s = string(row, 16);
        if (!s.matches("[.123]{4,16}")) throw bad("trace pixels");
        rows.add(s);
      }
      int width = rows.get(0).length();
      if (rows.stream().anyMatch(s -> s.length() != width)
          || rows.stream().allMatch(s -> s.chars().allMatch(c -> c == '.')))
        throw bad("trace shape");
      traces.put(id(e.getKey()), new Trace(immutableList(rows), integer(o, "color", 0, 0xffffff)));
    }
    TreeMap<String, Wave> waves = new TreeMap<String, Wave>();
    for (Map.Entry<String, JsonElement> e : entries(root, "waves", 1, 32)) {
      JsonObject o = object(e.getValue(), "wave");
      if (o.has("minimumPhase")) fields(o, "entities", "minimumPhase");
      else fields(o, "entities");
      int minimumPhase = o.has("minimumPhase") ? integer(o, "minimumPhase", 0, 10) : 0;
      ArrayList<String> entities = new ArrayList<String>();
      for (JsonElement v : array(o, "entities", 1, 8)) entities.add(resource(string(v, 128)));
      waves.put(id(e.getKey()), new Wave(immutableList(entities), minimumPhase));
    }
    TreeMap<String, Event> events = new TreeMap<String, Event>();
    for (JsonElement v : array(root, "events", 1, 64)) {
      JsonObject o = object(v, "event");
      fields(
          o,
          "id",
          "title",
          "tactic",
          "faction",
          "weight",
          "minScore",
          "minPlayers",
          "seasons",
          "cooldownTicks",
          "lifetimeTicks",
          "heavy",
          "affinity",
          "start",
          "nodes");
      String name = id(text(o, "id", 64));
      TreeSet<String> seasons = new TreeSet<String>();
      for (JsonElement s : array(o, "seasons", 1, 3)) {
        String phase = string(s, 32);
        if (!set("CALM", "ASH_WINTER", "HEAT").contains(phase)) throw bad("season");
        seasons.add(phase);
      }
      TreeMap<String, Double> affinity = new TreeMap<String, Double>();
      for (Map.Entry<String, JsonElement> a : entries(o, "affinity", 0, 6)) {
        if (!set("value", "defense", "empty_defense", "pressure", "players", "losses")
            .contains(a.getKey())) throw bad("affinity");
        affinity.put(a.getKey(), numeric(a.getValue(), -4, 4));
      }
      TreeMap<String, Node> nodes = new TreeMap<String, Node>();
      int waveActions = 0;
      for (JsonElement n : array(o, "nodes", 1, 24)) {
        JsonObject node = object(n, "node");
        fields(node, "id", "actions", "waitTicks", "branches", "timeout");
        String nodeId = id(text(node, "id", 64));
        ArrayList<Action> actions = new ArrayList<Action>();
        for (JsonElement a : array(node, "actions", 0, 8)) {
          JsonObject action = object(a, "action");
          fields(action, "type", "key", "role", "portion");
          String type = text(action, "type", 32),
              key = text(action, "key", 128),
              role = text(action, "role", 32);
          if (!ROLES.contains(role)) throw bad("role");
          double portion = number(action, "portion", 0, 1);
          boolean valid;
          switch (type) {
            case "sound":
              valid = sounds.containsKey(key);
              break;
            case "trace":
              valid = traces.containsKey(key);
              break;
            case "wave":
              valid = waves.containsKey(key) && portion > 0;
              break;
            case "particles":
              valid = set("spores", "ash", "frost", "dust").contains(key);
              break;
            case "move":
              valid = ROLES.contains(key);
              break;
            case "finish":
              valid = OUTCOMES.contains(key);
              break;
            default:
              valid = false;
          }
          if (!valid) throw bad("action reference: " + type + "/" + key);
          if (type.equals("wave")) waveActions++;
          actions.add(new Action(type, key, role, portion));
        }
        if (waveActions > 8) throw bad("wave actions");
        ArrayList<Branch> branches = new ArrayList<Branch>();
        HashSet<String> seen = new HashSet<String>();
        for (JsonElement b : array(node, "branches", 0, 9)) {
          JsonObject branch = object(b, "branch");
          fields(branch, "when", "next");
          String when = text(branch, "when", 32);
          if (!validTrigger(when) || !seen.add(when)) throw bad("branch");
          branches.add(new Branch(when, id(text(branch, "next", 64))));
        }
        String timeout = text(node, "timeout", 64);
        if (!timeout.isEmpty()) id(timeout);
        int wait = integer(node, "waitTicks", 0, 72000);
        boolean terminal = actions.stream().anyMatch(a -> a.type.equals("finish"));
        if (terminal) {
          if (actions.size() != 1 || !branches.isEmpty() || !timeout.isEmpty() || wait != 0)
            throw bad("terminal");
        } else if (timeout.isEmpty() || wait < 1) throw bad("missing timeout");
        if (nodes.put(
                nodeId,
                new Node(nodeId, immutableList(actions), wait, immutableList(branches), timeout))
            != null) throw bad("duplicate node");
      }
      String start = id(text(o, "start", 64));
      validateGraph(nodes, start);
      Event event =
          new Event(
              name,
              text(o, "title", 120),
              id(text(o, "tactic", 64)),
              text(o, "faction", 32),
              number(o, "weight", .01, 100),
              number(o, "minScore", -1e12, 1e12),
              integer(o, "minPlayers", 1, 6),
              immutableSet(seasons),
              integer(o, "cooldownTicks", 200, 1728000),
              integer(o, "lifetimeTicks", 200, 1728000),
              bool(o, "heavy"),
              immutableMap(affinity),
              start,
              immutableMap(nodes));
      if (!set("parasites", "siege", "mixed").contains(event.faction)) throw bad("faction");
      if (events.put(name, event) != null) throw bad("duplicate event");
    }
    try {
      String hash =
          hex(
              MessageDigest.getInstance("SHA-256")
                  .digest(new Gson().toJson(root).getBytes(StandardCharsets.UTF_8)));
      return new CinematicCatalog(
          hash,
          rules,
          immutableMap(sounds),
          immutableMap(traces),
          immutableMap(waves),
          immutableMap(events));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Map<String, Double> coefficients(JsonObject rules) {
    JsonObject raw =
        new JsonParser()
            .parse(object(rules.get("coefficients"), "coefficients").toString())
            .getAsJsonObject();
    if (!raw.has("maximumApproachHeightDifference"))
      raw.addProperty("maximumApproachHeightDifference", 12);
    fields(
        raw,
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
        "maximumApproachHeightDifference");
    TreeMap<String, Double> values = new TreeMap<String, Double>();
    values.put("valueNormalization", (double) number(raw, "valueNormalization", 0.1, 1000));
    values.put("defenseNormalization", (double) number(raw, "defenseNormalization", 0.1, 1000));
    values.put("defenseBudgetWeight", (double) number(raw, "defenseBudgetWeight", 0, 10));
    values.put("playerLevelWeight", (double) number(raw, "playerLevelWeight", 0, 10));
    values.put("playerArmorWeight", (double) number(raw, "playerArmorWeight", 0, 10));
    values.put("deathMemoryTicks", (double) integer(raw, "deathMemoryTicks", 200, 72000));
    values.put("playerRadius", (double) integer(raw, "playerRadius", 16, 256));
    values.put("nearRadius", (double) integer(raw, "nearRadius", 1, 16));
    values.put("farRadius", (double) integer(raw, "farRadius", 16, 256));
    values.put("restAfter", (double) integer(raw, "restAfter", 1, 10));
    values.put("minimumTraceDistance", (double) integer(raw, "minimumTraceDistance", 2, 32));
    values.put("maximumLoadedTraces", (double) integer(raw, "maximumLoadedTraces", 1, 128));
    values.put(
        "entityLifetimeTailTicks", (double) integer(raw, "entityLifetimeTailTicks", 0, 72000));
    values.put("approachAttempts", (double) integer(raw, "approachAttempts", 1, 32));
    values.put(
        "maximumApproachHeightDifference",
        (double) integer(raw, "maximumApproachHeightDifference", 1, 64));
    values.put("modelOptionLimit", (double) integer(raw, "modelOptionLimit", 1, 8));
    values.put("optionsPerZone", (double) integer(raw, "optionsPerZone", 1, 8));
    values.put("affinityLimit", (double) number(raw, "affinityLimit", 0.1, 4));
    values.put("playerScoreMaximum", (double) number(raw, "playerScoreMaximum", 1, 100));
    values.put("lossMaximum", (double) number(raw, "lossMaximum", 1, 100));
    values.put("retryTicks", (double) integer(raw, "retryTicks", 20, 72000));
    return immutableMap(values);
  }

  public static boolean validTrigger(String value) {
    if (value == null) return false;
    String[] parts = value.split(":", -1);
    return parts.length <= 2
        && TRIGGERS.contains(parts[0])
        && (parts.length == 1 || ROLES.contains(parts[1]));
  }

  private static void validateGraph(Map<String, Node> nodes, String start) {
    HashSet<String> complete = new HashSet<String>();
    visit(nodes, start, new HashSet<>(), complete);
    if (complete.size() != nodes.size()) throw bad("unreachable node");
  }

  private static void visit(
      Map<String, Node> nodes, String id, Set<String> path, Set<String> complete) {
    if (complete.contains(id)) return;
    if (!nodes.containsKey(id) || !path.add(id)) throw bad("missing node or cycle");
    Node n = nodes.get(id);
    for (Branch b : n.branches) visit(nodes, b.next, path, complete);
    if (!n.timeout.isEmpty()) visit(nodes, n.timeout, path, complete);
    path.remove(id);
    complete.add(id);
  }

  public static JsonElement strict(String source) {
    return strict(source, MAX_BYTES);
  }

  /** The catalogue keeps its own limit; bounded SavedData may use a larger envelope. */
  public static JsonElement strict(String source, int maximumBytes) {
    return strict(source, maximumBytes, false);
  }

  public static JsonElement strict(String source, int maximumBytes, boolean allowNull) {
    if (maximumBytes < 1
        || maximumBytes > 16 * 1024 * 1024
        || source == null
        || source.getBytes(StandardCharsets.UTF_8).length > maximumBytes) throw bad("file size");
    try (JsonReader reader = new JsonReader(new StringReader(source))) {
      reader.setLenient(false);
      JsonElement value = read(reader, 0, allowNull);
      if (reader.peek() != JsonToken.END_DOCUMENT) throw bad("trailing data");
      return value;
    } catch (IOException | IllegalStateException error) {
      throw bad("invalid JSON");
    }
  }

  private static JsonElement read(JsonReader in, int depth, boolean allowNull) throws IOException {
    if (depth > 20) throw bad("JSON depth");
    switch (in.peek()) {
      case BEGIN_OBJECT:
        JsonObject object = new JsonObject();
        in.beginObject();
        while (in.hasNext()) {
          String key = in.nextName();
          if (object.has(key)) throw bad("duplicate key");
          object.add(key, read(in, depth + 1, allowNull));
        }
        in.endObject();
        return object;
      case BEGIN_ARRAY:
        JsonArray array = new JsonArray();
        in.beginArray();
        while (in.hasNext()) {
          if (array.size() >= 2048) throw bad("array size");
          array.add(read(in, depth + 1, allowNull));
        }
        in.endArray();
        return array;
      case NULL:
        if (!allowNull) throw bad("null");
        in.nextNull();
        return JsonNull.INSTANCE;
      case STRING:
        return new JsonPrimitive(in.nextString());
      case NUMBER:
        String number = in.nextString();
        if (number.length() > 64) throw bad("number size");
        try {
          return new JsonPrimitive(new java.math.BigDecimal(number));
        } catch (NumberFormatException error) {
          throw bad("number");
        }
      case BOOLEAN:
        return new JsonPrimitive(in.nextBoolean());
      default:
        throw bad("unexpected token");
    }
  }

  private static JsonObject object(JsonElement v, String where) {
    if (v == null || !v.isJsonObject()) throw bad(where);
    return v.getAsJsonObject();
  }

  private static void fields(JsonObject o, String... keys) {
    Set<String> actual = new HashSet<String>();
    for (Map.Entry<String, JsonElement> entry : o.entrySet()) actual.add(entry.getKey());
    if (!actual.equals(set(keys))) throw bad("unknown or missing fields");
  }

  private static Set<Map.Entry<String, JsonElement>> entries(
      JsonObject o, String key, int min, int max) {
    JsonObject map = object(o.get(key), key);
    if (map.size() < min || map.size() > max) throw bad(key);
    return map.entrySet();
  }

  private static JsonArray array(JsonObject o, String key, int min, int max) {
    JsonElement v = o.get(key);
    if (v == null
        || !v.isJsonArray()
        || v.getAsJsonArray().size() < min
        || v.getAsJsonArray().size() > max) throw bad(key);
    return v.getAsJsonArray();
  }

  private static String string(JsonElement v, int max) {
    if (v == null
        || !v.isJsonPrimitive()
        || !v.getAsJsonPrimitive().isString()
        || v.getAsString().length() > max) throw bad("string");
    String s = v.getAsString();
    if (s.codePoints()
        .anyMatch(
            c ->
                Character.isISOControl(c)
                    || c == 0x00a7
                    || c >= 0x202a && c <= 0x202e
                    || c >= 0x2066 && c <= 0x2069)) throw bad("text controls");
    return s;
  }

  private static String text(JsonObject o, String k, int max) {
    return string(o.get(k), max);
  }

  private static String id(String s) {
    if (!s.matches("[a-z][a-z0-9_]{0,63}")) throw bad("id");
    return s;
  }

  private static String resource(String s) {
    if (!s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw bad("resource");
    return s;
  }

  private static double numeric(JsonElement v, double min, double max) {
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
      throw bad("number");
    double n = v.getAsDouble();
    if (!Double.isFinite(n) || n < min || n > max) throw bad("number range");
    return n;
  }

  private static double number(JsonObject o, String k, double min, double max) {
    return numeric(o.get(k), min, max);
  }

  private static int integer(JsonObject o, String k, int min, int max) {
    double n = number(o, k, min, max);
    if (n != Math.rint(n)) throw bad("integer");
    return (int) n;
  }

  private static boolean bool(JsonObject o, String k) {
    JsonElement v = o.get(k);
    if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw bad(k);
    return v.getAsBoolean();
  }

  private static IllegalArgumentException bad(String reason) {
    return new IllegalArgumentException("Director catalog: " + reason);
  }
}
