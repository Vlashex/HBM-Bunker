package dev.deadearth.legacy.config;

import com.google.gson.*;
import dev.deadearth.legacy.*;
import dev.deadearth.legacy.combat.*;
import dev.deadearth.legacy.data.*;
import dev.deadearth.legacy.defense.*;
import dev.deadearth.legacy.farming.*;
import dev.deadearth.legacy.kernel.*;
import dev.deadearth.legacy.machinery.*;
import java.util.*;

/** Explicit prepared snapshot adapters; no generic field mutation or event re-registration. */
public final class RuntimeConfigurationAdapter implements LiveConfiguration.Adapter {
  private final LegacyRuntime runtime;

  public RuntimeConfigurationAdapter(LegacyRuntime runtime) {
    this.runtime = runtime;
  }

  private static final class Prepared {
    Settings coordinator;
    SightSettings siege;
    SightSweep<Object> sightWorklist;
    DefenseSettings defense;
    FarmingConfig farming;
    dev.deadearth.legacy.climate.ClimateConfig climate;
    MachinerySettings machinery;
    CinematicCatalog catalog;
    JsonObject catalogDocument;
    int draws;
  }

  public Object prepare(Map<String, JsonObject> documents) {
    runtime.requireThread();
    Prepared out = new Prepared();
    out.coordinator =
        GameDataFiles.decode(documents.get("coordinator"), Settings.class, "coordinator");
    out.coordinator.validate();
    out.coordinator.revision = JsonFiles.sha(documents.get("coordinator").toString());
    out.siege = SightSettings.parse(documents.get("siege"));
    out.sightWorklist = LegacyPerception.prepareConfiguration(runtime.world.worlds());
    out.defense = DefenseSettings.parse(documents.get("defense"));
    out.farming = GameDataFiles.decode(documents.get("farming"), FarmingConfig.class, "farming");
    out.farming.validate();
    out.climate =
        GameDataFiles.decode(
            documents.get("climate"), dev.deadearth.legacy.climate.ClimateConfig.class, "climate");
    out.climate.validate();
    out.machinery =
        GameDataFiles.decode(documents.get("machinery"), MachinerySettings.class, "machinery");
    out.machinery.validate();
    out.draws = GameDataFiles.integer(documents.get("sieving"), "nativeDrawsPerServerTick", 1, 256);
    out.catalogDocument = ConfigurationDocuments.copy(documents.get("catalog-rules"));
    out.catalog = CinematicCatalog.parse(out.catalogDocument.toString());
    runtime.world.validate(out.catalog);
    for (String entity : PhaseRoster.parse(documents.get("srp-entities")).entries().keySet())
      registered("ENTITIES", entity);
    for (JsonElement block : documents.get("siege").getAsJsonArray("transparentBlocks"))
      registered("BLOCKS", block.getAsString());
    registered("BLOCKS", out.farming.acceleratorBlock);
    for (JsonElement item : documents.get("progression").getAsJsonArray("requiredItems"))
      item(item.getAsString());
    for (JsonElement row : documents.get("progression").getAsJsonArray("sieve")) {
      item(row.getAsJsonObject().get("input").getAsString());
      item(row.getAsJsonObject().get("output").getAsString());
    }
    for (Map.Entry<String, JsonElement> row :
        documents.get("progression").getAsJsonObject("rawEmc").entrySet())
      item(row.getKey().replace('|', ':'));
    for (Map.Entry<String, JsonElement> row :
        documents.get("sieving").getAsJsonObject("materials").entrySet()) {
      String[] token = row.getValue().getAsString().split("#");
      registered("BLOCKS", token[0]);
      if (token.length == 2 && Integer.parseInt(token[1]) > 32767)
        throw new IllegalArgumentException("Invalid block metadata");
    }
    return out;
  }

  private static void item(String token) {
    String[] parts = token.split(":");
    registered("ITEMS", parts[0] + ":" + parts[1]);
  }

  private static void registered(String registry, String id) {
    Object entry = Reflect.registry(registry, id);
    if (entry == null || !id.equals(String.valueOf(Reflect.call(entry, "getRegistryName"))))
      throw new IllegalArgumentException("Unknown registered configuration identifier");
  }

  public void commit(Object snapshot) {
    Prepared next = (Prepared) snapshot;
    runtime.settings = next.coordinator;
    LegacySiege.applyConfiguration(next.siege, next.sightWorklist);
    LegacyDefense.applyConfiguration(next.defense);
    LegacyFarming.applyConfiguration(next.farming);
    dev.deadearth.legacy.climate.LegacyClimate.applyConfiguration(next.climate);
    LegacyMachinery.applyConfiguration(next.machinery);
    dev.deadearth.legacy.sieving.LegacySieving.applyDrawBudget(next.draws);
    runtime.applyCatalogConfiguration(next.catalog, next.catalogDocument);
  }
}
