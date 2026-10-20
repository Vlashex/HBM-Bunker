package dev.deadearth.legacy.gameplay;

import dev.deadearth.legacy.*;
import java.nio.file.*;
import java.util.*;

/** Compatibility is kept out of models and never grants console authority. */
public final class LegacyCompat {
  private LegacyCompat() {}

  @SuppressWarnings("unchecked")
  public static void postInit(Path directory) {
    try {
      Object sorter =
          Reflect.get(Reflect.type("cpw.mods.inventorysorter.InventorySorter"), "INSTANCE");
      Collection<String> blacklist = (Collection<String>) Reflect.get(sorter, "containerblacklist");
      com.google.gson.JsonArray rows =
          JsonFiles.read(directory.resolve("dead-earth/sort-blacklist.json"), 131072)
              .getAsJsonArray("containers");
      for (com.google.gson.JsonElement row : rows) blacklist.add(row.getAsString());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot protect native HBM machine inventories from sorter", e);
    }
  }
}
