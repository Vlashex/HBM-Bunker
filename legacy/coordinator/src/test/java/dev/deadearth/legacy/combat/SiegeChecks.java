package dev.deadearth.legacy.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/** Admission contract only; native gameplay belongs to the isolated SiegeProbe session. */
public final class SiegeChecks {
  private static int checks;

  private static void require(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private static JsonObject policy() {
    return new JsonParser()
        .parse(
            "{\"default\":{\"Digging\":false,\"Animal Retaliate\":false,\"Attack"
                + " Everything\":false},\"entities\":{\"minecraft:cow\":{\"Animal"
                + " Retaliate\":true},\"srparasites:sim_human\":{\"Digging\":true}}}")
        .getAsJsonObject();
  }

  private static void reject(JsonObject document) {
    boolean rejected = false;
    try {
      SiegePolicy.configuredEntities(document);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    require(rejected, "unsafe or malformed admission rejected");
  }

  public static void main(String[] args) {
    Set<String> profiles = SiegePolicy.configuredEntities(policy());
    require(
        profiles.size() == 2
            && profiles.contains("minecraft:cow")
            && profiles.contains("srparasites:sim_human"),
        "only explicit profiles admitted");
    boolean immutable = false;
    try {
      profiles.add("minecraft:wither");
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    require(immutable, "admission snapshot immutable");
    for (String name : new String[] {"Digging", "Animal Retaliate", "Attack Everything"}) {
      JsonObject doc = policy();
      doc.getAsJsonObject("default").addProperty(name, true);
      reject(doc);
    }
    JsonObject doc = policy();
    doc.remove("default");
    reject(doc);
    doc = policy();
    doc.addProperty("entities", "bad");
    reject(doc);
    doc = policy();
    doc.getAsJsonObject("entities").add("Bad Entity", new JsonObject());
    reject(doc);
    doc = policy();
    doc.add("entities", new JsonObject());
    reject(doc);
    doc = policy();
    for (int n = 0; n < 256; n++)
      doc.getAsJsonObject("entities").add("test:mob_" + n, new JsonObject());
    reject(doc);
    transparentSight();
    settings();
    globalXray();
    System.out.println("Siege checks passed: " + checks);
  }

  private static void transparentSight() {
    final double[] from = {.5, 64.5, .5}, to = {10.5, 64.5, .5};
    require(
        TransparentSight.visible(from, to, 32, 128, 16, (x, y, z) -> TransparentSight.CLEAR),
        "unobstructed bounded ray");
    require(
        TransparentSight.visible(
            from,
            to,
            32,
            128,
            16,
            (x, y, z) -> x == 4 ? TransparentSight.TRANSPARENT : TransparentSight.CLEAR),
        "allowed glass cell transparent");
    require(
        !TransparentSight.visible(
            from,
            to,
            32,
            128,
            16,
            (x, y, z) -> x == 4 ? TransparentSight.OCCLUDED : TransparentSight.CLEAR),
        "stone remains opaque");
    require(
        !TransparentSight.visible(
            from,
            to,
            32,
            128,
            16,
            (x, y, z) -> x == 4 ? TransparentSight.UNKNOWN : TransparentSight.CLEAR),
        "unloaded cell fail closed");
    require(
        !TransparentSight.visible(from, to, 8, 128, 16, (x, y, z) -> TransparentSight.CLEAR),
        "bounded distance");
    require(
        !TransparentSight.visible(from, to, 32, 4, 16, (x, y, z) -> TransparentSight.CLEAR),
        "bounded ray cells");
    require(
        !TransparentSight.visible(from, to, 32, 128, 3, (x, y, z) -> TransparentSight.TRANSPARENT),
        "bounded glass thickness");
    require(
        !TransparentSight.visible(
            new double[] {Double.NaN, 0, 0}, to, 32, 128, 16, (x, y, z) -> TransparentSight.CLEAR),
        "nonfinite coordinates rejected");
    require(
        TransparentSight.visible(
            new double[] {-.5, 64.5, -.5},
            new double[] {-10.5, 64.5, -10.5},
            32,
            128,
            16,
            (x, y, z) -> TransparentSight.CLEAR),
        "negative diagonal traversal");
    require(
        !TransparentSight.visible(
            new double[] {-.5, 64.5, -.5},
            new double[] {-10.5, 64.5, -10.5},
            32,
            128,
            16,
            (x, y, z) -> x == -5 && z == -5 ? TransparentSight.OCCLUDED : TransparentSight.CLEAR),
        "negative diagonal obstruction");
    require(
        !TransparentSight.visible(
            new double[] {.5, 64.5, .5},
            new double[] {10.5, 64.5, 10.5},
            32,
            128,
            16,
            (x, y, z) -> x == 4 && z == 3 ? TransparentSight.OCCLUDED : TransparentSight.CLEAR),
        "opaque boundary corner cannot be skipped");
  }

  private static JsonObject sight() {
    return new JsonParser()
        .parse(
            "{\"schema\":1,\"mode\":\"xray\",\"targetCreativePlayers\":false,\"sightRadius\":64,\"maximumRayCells\":128,\"maximumTransparentCells\":16,\"maximumRaysPerTick\":64,\"maximumTargetsPerEntity\":4,\"transparentBlocks\":[\"minecraft:glass\"]}")
        .getAsJsonObject();
  }

  private static void badSight(JsonObject document) {
    boolean rejected = false;
    try {
      SightSettings.parse(document);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    require(rejected, "unsafe transparent sight configuration rejected");
  }

  private static void settings() {
    SightSettings valid = SightSettings.parse(sight());
    require(
        valid.radius == 64
            && valid.mode.equals("xray")
            && !valid.targetCreativePlayers
            && valid.raysPerTick == 64
            && valid.transparent.contains("minecraft:glass"),
        "strict restart sight settings");
    JsonObject doc = sight();
    doc.addProperty("sightRadius", 129);
    badSight(doc);
    doc = sight();
    doc.addProperty("maximumRayCells", 1.5);
    badSight(doc);
    doc = sight();
    doc.addProperty("targetCreativePlayers", "true");
    badSight(doc);
    doc = sight();
    doc.addProperty("extra", 0);
    badSight(doc);
    doc = sight();
    doc.remove("maximumRaysPerTick");
    badSight(doc);
    doc = sight();
    doc.getAsJsonArray("transparentBlocks").add("hbm:block_steel");
    badSight(doc);
    doc = sight();
    doc.getAsJsonArray("transparentBlocks").add("dead_earth:defense_wall_v3");
    badSight(doc);
    doc = sight();
    doc.getAsJsonArray("transparentBlocks").add("minecraft:glass");
    badSight(doc);
  }

  private static void globalXray() {
    require(
        PlayerVisibility.allowed(64, true, true, true, true, true, false, false, false, 4096),
        "inclusive configured xray radius64");
    require(
        !PlayerVisibility.allowed(64, true, true, true, true, true, false, false, false, 4096.1),
        "outside configured radius rejected");
    require(
        !PlayerVisibility.allowed(64, false, true, true, true, true, false, false, false, 1),
        "other dimension rejected");
    require(
        !PlayerVisibility.allowed(64, true, false, true, true, true, false, false, false, 1),
        "unloaded observer rejected");
    require(
        !PlayerVisibility.allowed(64, true, true, false, true, true, false, false, false, 1),
        "unloaded target rejected");
    require(
        !PlayerVisibility.allowed(64, true, true, true, false, true, false, false, false, 1),
        "dead observer rejected");
    require(
        !PlayerVisibility.allowed(64, true, true, true, true, false, false, false, false, 1),
        "dead player rejected");
    require(
        !PlayerVisibility.allowed(64, true, true, true, true, true, true, false, true, 1),
        "spectators always excluded");
    require(
        !PlayerVisibility.allowed(64, true, true, true, true, true, false, true, false, 1),
        "creative visual target disabled by default");
    require(
        PlayerVisibility.allowed(64, true, true, true, true, true, false, true, true, 1),
        "explicit creative sight policy allowed without changing attack predicates");
    require(
        !PlayerVisibility.allowed(
            64, true, true, true, true, true, false, false, false, Double.NaN),
        "nonfinite xray distance rejected");
    require(
        PlayerVisibility.allowed(128, true, true, true, true, true, false, false, false, 16384),
        "radius128 allowed");
    require(
        !PlayerVisibility.allowed(129, true, true, true, true, true, false, false, false, 1),
        "radius above128 rejected");
  }
}
