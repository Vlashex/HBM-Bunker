package dev.deadearth.legacy.config;

import com.google.gson.*;
import dev.deadearth.legacy.*;
import dev.deadearth.legacy.data.*;
import dev.deadearth.legacy.kernel.CinematicCatalog;
import java.nio.file.*;
import java.util.*;

/** Fixed native whitelist. JSON decoding validates types; no reflective live setter exists. */
public final class ConfigurationDocuments {
  public static final List<String> IDS =
      Collections.unmodifiableList(
          Arrays.asList(
              "coordinator",
              "siege",
              "relay-siege",
              "climate",
              "farming",
              "machinery",
              "defense",
              "tools",
              "sieving",
              "worldgen",
              "progression",
              "srp-entities",
              "catalog-rules"));

  private ConfigurationDocuments() {}

  public static Path path(Path config, String id) {
    if (!IDS.contains(id)) throw new IllegalArgumentException("Unknown configuration section");
    return config.resolve(
        id.equals("catalog-rules")
            ? "dead-earth/director/catalog.json"
            : "dead-earth/" + id + ".json");
  }

  public static int limit(String id) {
    if (id.equals("catalog-rules")) return 1048576;
    if (id.equals("progression")) return 524288;
    if (Arrays.asList("siege", "relay-siege", "farming", "machinery", "defense").contains(id))
      return 16384;
    if (Arrays.asList("tools", "sieving", "worldgen").contains(id)) return 32768;
    return 65536;
  }

  public static Map<String, JsonObject> read(Path config) throws Exception {
    Map<String, JsonObject> out = new LinkedHashMap<String, JsonObject>();
    for (String id : IDS) {
      Path file = path(config, id);
      LiveConfiguration.safe(file);
      out.put(id, normalize(id, JsonFiles.read(file, limit(id))));
    }
    return out;
  }

  public static JsonObject copy(JsonObject value) {
    return CinematicCatalog.strict(value.toString(), 1048576, true).getAsJsonObject();
  }

  public static Map<String, JsonObject> copy(Map<String, JsonObject> values) {
    Map<String, JsonObject> out = new LinkedHashMap<String, JsonObject>();
    for (Map.Entry<String, JsonObject> row : values.entrySet())
      out.put(row.getKey(), copy(row.getValue()));
    return out;
  }

  public static JsonObject normalize(String id, JsonObject raw) {
    Object decoded = null;
    if (id.equals("coordinator")) {
      Settings next = GameDataFiles.decode(raw, Settings.class, "coordinator");
      if (raw.has("revision")) throw new IllegalArgumentException("Reserved revision");
      next.validate();
      decoded = next;
    } else if (id.equals("siege")) {
      dev.deadearth.legacy.combat.SightSettings.parse(raw);
    } else if (id.equals("relay-siege")) {
      dev.deadearth.legacy.combat.RelaySiegeSettings next =
          GameDataFiles.decode(raw, dev.deadearth.legacy.combat.RelaySiegeSettings.class, id);
      next.validate();
      decoded = next;
    } else if (id.equals("climate")) {
      dev.deadearth.legacy.climate.ClimateConfig next =
          GameDataFiles.decode(raw, dev.deadearth.legacy.climate.ClimateConfig.class, id);
      next.validate();
      decoded = next;
    } else if (id.equals("farming")) {
      dev.deadearth.legacy.farming.FarmingConfig next =
          GameDataFiles.decode(raw, dev.deadearth.legacy.farming.FarmingConfig.class, id);
      next.validate();
      decoded = next;
    } else if (id.equals("machinery")) {
      dev.deadearth.legacy.machinery.MachinerySettings next =
          GameDataFiles.decode(raw, dev.deadearth.legacy.machinery.MachinerySettings.class, id);
      next.validate();
      decoded = next;
    } else if (id.equals("defense")) {
      dev.deadearth.legacy.defense.DefenseSettings.parse(raw);
    } else if (id.equals("tools")) {
      dev.deadearth.legacy.gameplay.NativeTools.Config next =
          GameDataFiles.decode(raw, dev.deadearth.legacy.gameplay.NativeTools.Config.class, id);
      next.validate();
      decoded = next;
    } else if (id.equals("worldgen")) {
      dev.deadearth.legacy.worldgen.WastelandSettings next =
          GameDataFiles.decode(raw, dev.deadearth.legacy.worldgen.WastelandSettings.class, id);
      next.validate();
      decoded = next;
    } else if (id.equals("srp-entities")) {
      PhaseRoster.parse(raw);
    } else if (id.equals("sieving")) {
      validateSieving(raw);
    } else if (id.equals("progression")) {
      validateProgression(raw);
    } else if (id.equals("catalog-rules")) {
      CinematicCatalog.parse(raw.toString());
    } else throw new IllegalArgumentException("Unknown configuration section");
    JsonObject normalized =
        decoded == null ? copy(raw) : JsonFiles.JSON.toJsonTree(decoded).getAsJsonObject();
    if (id.equals("coordinator")) normalized.remove("revision");
    return normalized;
  }

  private static void string(JsonElement value, String expression) {
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isString()
        || value.getAsString().length() > 160
        || !value.getAsString().matches(expression))
      throw new IllegalArgumentException("Invalid configuration identifier");
  }

  public static void validateSieving(JsonObject raw) {
    GameDataFiles.onlyFields(
        raw, "schema", "nativeDrawsPerServerTick", "materials", "ownFirstCompression");
    GameDataFiles.integer(raw, "schema", 1, 1);
    GameDataFiles.integer(raw, "nativeDrawsPerServerTick", 1, 256);
    JsonObject materials = raw.getAsJsonObject("materials");
    if (materials == null || materials.entrySet().size() < 4 || materials.entrySet().size() > 32)
      throw new IllegalArgumentException("Sieving materials bounds");
    for (Map.Entry<String, JsonElement> row : materials.entrySet()) {
      if (!row.getKey().matches("[a-z_]{1,64}"))
        throw new IllegalArgumentException("Material name");
      string(row.getValue(), "[a-z0-9_]+:[a-z0-9_./]+(?:#[0-9]{1,5})?");
    }
    JsonArray own = raw.getAsJsonArray("ownFirstCompression");
    if (own == null || own.size() > 32) throw new IllegalArgumentException("Compression bounds");
    Set<String> seen = new HashSet<String>();
    for (JsonElement name : own) {
      string(name, "[a-z_]{1,64}");
      if (!materials.has(name.getAsString()) || !seen.add(name.getAsString()))
        throw new IllegalArgumentException("Unknown or duplicate compression");
    }
  }

  public static void validateProgression(JsonObject raw) {
    GameDataFiles.onlyFields(raw, "schema", "requiredItems", "sieve", "rawEmc");
    GameDataFiles.integer(raw, "schema", 1, 1);
    JsonArray items = raw.getAsJsonArray("requiredItems"), sieve = raw.getAsJsonArray("sieve");
    JsonObject emc = raw.getAsJsonObject("rawEmc");
    if (items == null
        || items.size() > 1024
        || sieve == null
        || sieve.size() > 512
        || emc == null
        || emc.entrySet().size() > 512) throw new IllegalArgumentException("Progression bounds");
    for (JsonElement item : items) item(item);
    for (JsonElement element : sieve) {
      JsonObject row = element.getAsJsonObject();
      GameDataFiles.onlyFields(row, "input", "output", "chance", "mesh");
      item(row.get("input"));
      item(row.get("output"));
      GameDataFiles.integer(row, "mesh", 1, 4);
      JsonElement chance = row.get("chance");
      if (chance == null
          || !chance.isJsonPrimitive()
          || !chance.getAsJsonPrimitive().isNumber()
          || !Double.isFinite(chance.getAsDouble())
          || chance.getAsDouble() <= 0
          || chance.getAsDouble() > 1) throw new IllegalArgumentException("Sieve chance");
    }
    for (Map.Entry<String, JsonElement> row : emc.entrySet()) {
      if (!row.getKey().matches("[a-z0-9_]+:[a-z0-9_./]+\\|[0-9]{1,5}"))
        throw new IllegalArgumentException("Raw EMC identifier");
      GameDataFiles.integer(emc, row.getKey(), 1, Integer.MAX_VALUE);
    }
  }

  private static void item(JsonElement token) {
    string(token, "[a-z0-9_]+:[a-z0-9_./]+(?::[0-9]{1,5})?");
    String[] parts = token.getAsString().split(":");
    if (parts.length == 3 && Integer.parseInt(parts[2]) > 32767)
      throw new IllegalArgumentException("Item metadata");
  }

  public static JsonObject fields(String id, JsonObject document) {
    return id.equals("catalog-rules") ? document.getAsJsonObject("rules") : document;
  }

  public static JsonElement get(JsonObject document, String key) {
    if (key.startsWith("coefficients."))
      return document.getAsJsonObject("coefficients").get(key.substring(13));
    return document.get(key);
  }

  public static void set(JsonObject document, String key, JsonElement value) {
    if (key.startsWith("coefficients."))
      document.getAsJsonObject("coefficients").add(key.substring(13), value);
    else document.add(key, value);
  }

  public static String revision(Map<String, JsonObject> documents) {
    JsonObject all = new JsonObject();
    for (String id : IDS) all.add(id, documents.get(id));
    return JsonFiles.sha(canonical(all).toString());
  }

  public static JsonElement canonical(JsonElement value) {
    if (value.isJsonObject()) {
      JsonObject out = new JsonObject();
      TreeMap<String, JsonElement> sorted = new TreeMap<String, JsonElement>();
      for (Map.Entry<String, JsonElement> row : value.getAsJsonObject().entrySet())
        sorted.put(row.getKey(), row.getValue());
      for (Map.Entry<String, JsonElement> row : sorted.entrySet())
        out.add(row.getKey(), canonical(row.getValue()));
      return out;
    }
    if (value.isJsonArray()) {
      JsonArray out = new JsonArray();
      for (JsonElement row : value.getAsJsonArray()) out.add(canonical(row));
      return out;
    }
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
      return new JsonPrimitive(new java.math.BigDecimal(value.getAsString()).stripTrailingZeros());
    return value;
  }
}
