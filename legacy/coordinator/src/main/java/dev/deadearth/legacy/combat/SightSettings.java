package dev.deadearth.legacy.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.deadearth.legacy.data.GameDataFiles;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Restart-only, strict and bounded additions to native cached entity senses. */
public final class SightSettings {
  public final String mode;
  public final boolean targetCreativePlayers;
  public final int radius, maximumCells, maximumTransparent, raysPerTick, targetsPerEntity;
  public final Set<String> transparent;

  private SightSettings(JsonObject value) {
    Set<String> keys =
        new LinkedHashSet<String>(
            Arrays.asList(
                "schema",
                "mode",
                "sightRadius",
                "targetCreativePlayers",
                "maximumRayCells",
                "maximumTransparentCells",
                "maximumRaysPerTick",
                "maximumTargetsPerEntity",
                "transparentBlocks"));
    for (java.util.Map.Entry<String, JsonElement> row : value.entrySet())
      if (!keys.remove(row.getKey()))
        throw new IllegalArgumentException("Unknown siege setting: " + row.getKey());
    if (!keys.isEmpty()) throw new IllegalArgumentException("Missing siege settings: " + keys);
    if (GameDataFiles.integer(value, "schema", 1, 1) != 1)
      throw new IllegalArgumentException("Unsupported siege settings schema");
    JsonElement name = value.get("mode");
    if (!name.isJsonPrimitive()
        || !name.getAsJsonPrimitive().isString()
        || !Arrays.asList("native", "glass", "xray").contains(name.getAsString()))
      throw new IllegalArgumentException("mode must be native, glass or xray");
    mode = name.getAsString();
    JsonElement creative = value.get("targetCreativePlayers");
    if (!creative.isJsonPrimitive() || !creative.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("targetCreativePlayers must be boolean");
    targetCreativePlayers = creative.getAsBoolean();
    radius = GameDataFiles.integer(value, "sightRadius", 1, 128);
    maximumCells = GameDataFiles.integer(value, "maximumRayCells", 1, 256);
    maximumTransparent = GameDataFiles.integer(value, "maximumTransparentCells", 1, 64);
    raysPerTick = GameDataFiles.integer(value, "maximumRaysPerTick", 1, 256);
    targetsPerEntity = GameDataFiles.integer(value, "maximumTargetsPerEntity", 1, 16);
    JsonElement rows = value.get("transparentBlocks");
    if (!rows.isJsonArray() || rows.getAsJsonArray().size() > 32)
      throw new IllegalArgumentException("Bounded transparentBlocks array required");
    Set<String> ids = new LinkedHashSet<String>();
    for (JsonElement row : rows.getAsJsonArray()) {
      if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString())
        throw new IllegalArgumentException("Transparent block ID required");
      String id = row.getAsString();
      if (!id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
          || id.startsWith("hbm:")
          || id.startsWith("dead_earth:defense_")
          || !ids.add(id))
        throw new IllegalArgumentException("Unsafe or duplicate transparent block: " + id);
    }
    transparent = Collections.unmodifiableSet(ids);
  }

  public static SightSettings parse(JsonObject value) {
    return new SightSettings(value);
  }
}
