package dev.deadearth.legacy.data;

import com.google.gson.*;
import java.util.*;

/** One immutable phase snapshot is shared by waves, spawn admission and admin views. */
public final class PhaseRoster {
  private final Map<String, Integer> phases;

  private PhaseRoster(Map<String, Integer> phases) {
    this.phases = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(phases));
  }

  public static PhaseRoster parse(JsonObject document) {
    GameDataFiles.onlyFields(document, "schema", "description", "entities");
    GameDataFiles.integer(document, "schema", 1, 1);
    JsonElement entities = document.get("entities");
    if (entities == null
        || !entities.isJsonObject()
        || entities.getAsJsonObject().entrySet().isEmpty()
        || entities.getAsJsonObject().entrySet().size() > 256)
      throw new IllegalArgumentException("Entity roster bounds");
    if (document.has("description")
        && (!document.get("description").isJsonPrimitive()
            || !document.getAsJsonPrimitive("description").isString()))
      throw new IllegalArgumentException("Entity roster description");
    Map<String, Integer> phases = new LinkedHashMap<String, Integer>();
    JsonObject rows = entities.getAsJsonObject();
    for (Map.Entry<String, JsonElement> row : rows.entrySet()) {
      if (!row.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
        throw new IllegalArgumentException("Entity roster identifier " + row.getKey());
      phases.put(row.getKey(), GameDataFiles.integer(rows, row.getKey(), 0, 10));
    }
    return new PhaseRoster(phases);
  }

  public Map<String, Integer> entries() {
    return phases;
  }

  public boolean contains(String entity) {
    return phases.containsKey(entity);
  }

  public boolean waveAllowed(String entity, int phase) {
    Integer minimum = phases.get(entity);
    return minimum != null && phase >= minimum;
  }

  /** Unlisted native entities keep SRP's own phase rules; they cannot enter a director wave. */
  public boolean nativeSpawnAllowed(String entity, int phase) {
    Integer minimum = phases.get(entity);
    return minimum == null || phase >= minimum;
  }

  public List<String> unlocked(int phase) {
    if (phase < 0 || phase > 10) throw new IllegalArgumentException("Phase outside 0..10");
    List<String> out = new ArrayList<String>();
    for (Map.Entry<String, Integer> entry : phases.entrySet())
      if (entry.getValue() <= phase) out.add(entry.getKey());
    return Collections.unmodifiableList(out);
  }
}
