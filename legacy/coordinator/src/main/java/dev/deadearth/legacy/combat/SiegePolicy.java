package dev.deadearth.legacy.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** One admission source: Epic Siege's own per-entity profiles. */
public final class SiegePolicy {
  private SiegePolicy() {}

  public static Set<String> configuredEntities(JsonObject document) {
    if (document == null
        || !document.has("default")
        || !document.get("default").isJsonObject()
        || !document.has("entities")
        || !document.get("entities").isJsonObject()) {
      throw new IllegalArgumentException("Epic Siege requires default and entities objects");
    }
    JsonObject defaults = document.getAsJsonObject("default");
    for (String name : new String[] {"Digging", "Animal Retaliate", "Attack Everything"}) {
      if (!defaults.has(name)
          || !defaults.get(name).isJsonPrimitive()
          || !defaults.get(name).getAsJsonPrimitive().isBoolean()
          || defaults.get(name).getAsBoolean()) {
        throw new IllegalArgumentException("Epic Siege default " + name + " must remain false");
      }
    }
    Set<String> configured = new LinkedHashSet<String>();
    for (Map.Entry<String, JsonElement> row : document.getAsJsonObject("entities").entrySet()) {
      if (!row.getKey().matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || !row.getValue().isJsonObject()) {
        throw new IllegalArgumentException("Invalid Epic Siege entity profile: " + row.getKey());
      }
      if (!configured.add(row.getKey()) || configured.size() > 256) {
        throw new IllegalArgumentException("Epic Siege profile limit exceeded");
      }
    }
    if (configured.isEmpty())
      throw new IllegalArgumentException("Epic Siege entity profiles are empty");
    return Collections.unmodifiableSet(configured);
  }
}
