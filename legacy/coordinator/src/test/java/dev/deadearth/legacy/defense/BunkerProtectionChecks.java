package dev.deadearth.legacy.defense;

import com.google.gson.*;
import dev.deadearth.legacy.*;
import dev.deadearth.legacy.kernel.CinematicEngine;
import java.nio.file.*;

/** Protection deadlines must survive reloads without changing the campaign clock. */
public final class BunkerProtectionChecks {
  private static int checks;

  private static void check(boolean ok, String reason) {
    checks++;
    if (!ok) throw new AssertionError(reason);
  }

  private static long policy(String method, Object... args) {
    return ((Number) Reflect.call(
        Reflect.type("dev.deadearth.legacy.defense.BunkerProtection"), method, args)).longValue();
  }

  public static void main(String[] args) throws Exception {
    Campaign original = new Campaign();
    original.cinematic = new CinematicEngine().snapshot();
    JsonObject raw = JsonFiles.JSON.toJsonTree(original).getAsJsonObject();
    check(raw.has("bunkerProtectionUntilTicks"), "Campaign must persist the protection deadline");
    check(policy("durationTicks", "1h") == 72000, "hour units");
    check(policy("durationTicks", "30m") == 36000, "minute units");
    check(policy("durationTicks", "60s") == 1200, "second units");
    check(policy("durationTicks", "1d") == 1728000, "day units");
    check(policy("extendFromNow", 300000L, 216000L, 0L, 72000L) == 372000L,
        "expired protection restarts for an hour from now");
    check(policy("extendFromNow", 200000L, 216000L, 0L, 72000L) == 272000L,
        "active protection extends to an hour from now");
    check(policy("extendFromNow", 100L, 216000L, 0L, 72000L) == 216000L,
        "command never shortens existing protection");
    check(policy("extendFromNow", 300000L, 216000L, 400000L, 72000L) == 400000L,
        "earlier repeated requests never shorten a saved extension");
    for (String invalid : new String[]{"", "0h", "-1h", "1.5h", "999999999999h", "31d", "hello"}) {
      boolean rejected = false;
      try { policy("durationTicks", invalid); } catch (RuntimeException expected) { rejected = true; }
      check(rejected, "reject malformed or unbounded duration " + invalid);
    }
    boolean overflow = false;
    try { policy("extendFromNow", Long.MAX_VALUE - 2, 0L, 0L, 72000L); }
    catch (RuntimeException expected) { overflow = true; }
    check(overflow, "reject deadline overflow");
    Path dir = Files.createTempDirectory("dead-earth-bunker-protection-");
    Path file = dir.resolve("campaign.json");
    try {
      raw.remove("bunkerProtectionUntilTicks");
      JsonFiles.write(file, raw);
      Campaign legacy = Campaign.open(file);
      check(JsonFiles.JSON.toJsonTree(legacy).getAsJsonObject().get("bunkerProtectionUntilTicks").getAsLong() == 0,
          "existing worlds load without the new field");
      raw.addProperty("bunkerProtectionUntilTicks", 372000L);
      raw.addProperty("activeTicks", 300000L);
      JsonFiles.write(file, raw);
      Campaign restored = Campaign.open(file);
      JsonFiles.write(file, restored);
      Campaign reopened = Campaign.open(file);
      JsonObject saved = JsonFiles.JSON.toJsonTree(reopened).getAsJsonObject();
      check(saved.get("bunkerProtectionUntilTicks").getAsLong() == 372000L,
          "extension survives two save/load cycles");
      check(reopened.activeTicks == 300000L, "extension does not rewind evolution or seasons");
      for (JsonElement invalid : new JsonElement[]{new JsonPrimitive(-1), new JsonPrimitive(1.5),
          new JsonPrimitive("372000"), JsonNull.INSTANCE}) {
        raw.add("bunkerProtectionUntilTicks", invalid);
        JsonFiles.write(file, raw);
        boolean rejected = false;
        try { Campaign.open(file); } catch (Exception expected) { rejected = true; }
        check(rejected, "invalid saved deadline rejected");
      }
    } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir); }
    System.out.println("BunkerProtectionChecks: " + checks + " passed");
  }
}
