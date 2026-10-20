package dev.deadearth.legacy;

import com.google.gson.*;
import dev.deadearth.legacy.data.*;
import java.util.*;

/** Configuration corruption must fail before a running campaign is modified. */
public final class DataChecks {
  private static int checks;

  public static final class Nested {
    public int limit = 7;
  }

  public static final class Sample {
    public boolean enabled = true;
    public int budget = 16;
    public long tick = 0;
    public double rate = 1;
    public String label = "default";
    public Nested nested = new Nested();
    public Map<String, Integer> prices = new LinkedHashMap<String, Integer>();
    public List<Nested> rows = new ArrayList<Nested>();
    public int[] dimensions = {0};
  }

  private static JsonObject object(String text) {
    return dev.deadearth.legacy.kernel.CinematicCatalog.strict(text, 65536, true).getAsJsonObject();
  }

  private static void check(boolean condition) {
    checks++;
    if (!condition) throw new AssertionError("Data check " + checks);
  }

  private static void reject(String text) {
    boolean rejected = false;
    try {
      GameDataFiles.decode(object(text), Sample.class, "test");
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected);
  }

  private static void rejectRoster(String text) {
    boolean rejected = false;
    try {
      PhaseRoster.parse(object(text));
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected);
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) {
    Sample defaults = GameDataFiles.decode(object("{}"), Sample.class, "test");
    check(
        defaults.enabled
            && defaults.budget == 16
            && defaults.nested.limit == 7
            && defaults.dimensions[0] == 0);
    Sample parsed =
        GameDataFiles.decode(
            object(
                "{\"enabled\":false,\"budget\":3,\"tick\":9223372036854775807,\"rate\":0.25,\"label\":\"HBM\",\"nested\":{\"limit\":9},\"prices\":{\"hbm:ingot_copper\":128},\"rows\":[{\"limit\":2}],\"dimensions\":[0,-1]}"),
            Sample.class,
            "test");
    check(
        !parsed.enabled
            && parsed.budget == 3
            && parsed.tick == Long.MAX_VALUE
            && parsed.rate == .25
            && parsed.label.equals("HBM"));
    check(
        parsed.nested.limit == 9
            && parsed.prices.get("hbm:ingot_copper") == 128
            && parsed.rows.get(0).limit == 2
            && parsed.dimensions[1] == -1);
    for (String bad :
        Arrays.asList(
            "{\"typo\":1}",
            "{\"enabled\":\"true\"}",
            "{\"budget\":2.0}",
            "{\"budget\":\"2\"}",
            "{\"budget\":2147483648}",
            "{\"tick\":9223372036854775808}",
            "{\"rate\":1e999}",
            "{\"label\":3}",
            "{\"nested\":null}",
            "{\"nested\":{\"typo\":7}}",
            "{\"prices\":{\"a\":null}}",
            "{\"prices\":{\"a\":1.5}}",
            "{\"rows\":[{\"limit\":\"2\"}]}",
            "{\"rows\":[null]}",
            "{\"dimensions\":[1.5]}")) reject(bad);
    PhaseRoster roster =
        PhaseRoster.parse(
            object(
                "{\"schema\":1,\"entities\":{\"srparasites:buglin\":0,\"srparasites:rupter\":2}}"));
    check(roster.unlocked(0).equals(Collections.singletonList("srparasites:buglin")));
    check(
        !roster.waveAllowed("srparasites:rupter", 1)
            && roster.waveAllowed("srparasites:rupter", 2));
    check(
        !roster.waveAllowed("srparasites:unknown", 10)
            && roster.nativeSpawnAllowed("srparasites:unknown", 0));
    boolean immutable = false;
    try {
      roster.entries().put("minecraft:pig", 0);
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    check(immutable);
    for (String bad :
        Arrays.asList(
            "{}",
            "{\"schema\":1,\"entities\":{}}",
            "{\"schema\":2,\"entities\":{\"srparasites:a\":1}}",
            "{\"schema\":1,\"entities\":{\"bad-id\":1}}",
            "{\"schema\":1,\"entities\":{\"srparasites:a\":11}}",
            "{\"schema\":1,\"entities\":{\"srparasites:a\":1.0}}",
            "{\"schema\":1,\"entities\":{\"srparasites:a\":null}}",
            "{\"schema\":1,\"typo\":1,\"entities\":{\"srparasites:a\":1}}")) rejectRoster(bad);
    Map<String, Object> source =
        JsonFiles.map(
            "createdTick",
            2d,
            "participants",
            1d,
            "damage",
            JsonFiles.map("hp", 4d, "deaths", 1d, "pointsWeights", JsonFiles.map("hpWeight", 1)),
            "observations",
            JsonFiles.map("approaches", 2d),
            "proxy",
            JsonFiles.map("score", 3),
            "timeline",
            new ArrayList<Object>(
                Arrays.asList(JsonFiles.map("seq", 0d, "tick", 2d, "count", 1d))));
    Map<String, Object> wire = ScenarioAnalytics.wire(source);
    check(
        wire.get("createdTick") instanceof Long
            && ((Map<?, ?>) wire.get("damage")).get("deaths") instanceof Long);
    ((Map<String, Object>) wire.get("proxy")).put("score", 99);
    ((Map<String, Object>) ((Map<String, Object>) wire.get("damage")).get("pointsWeights"))
        .put("hpWeight", 99);
    ((Map<String, Object>) ((List<?>) wire.get("timeline")).get(0)).put("tick", 99L);
    check(((Number) ((Map<?, ?>) source.get("proxy")).get("score")).intValue() == 3);
    check(
        ((Number)
                    ((Map<?, ?>) ((Map<?, ?>) source.get("damage")).get("pointsWeights"))
                        .get("hpWeight"))
                .intValue()
            == 1);
    check(
        ((Number) ((Map<?, ?>) ((List<?>) source.get("timeline")).get(0)).get("tick")).intValue()
            == 2);
    check(ScenarioAnalytics.wire(null) == null);
    System.out.println("DataChecks: " + checks + " checks passed");
  }
}
