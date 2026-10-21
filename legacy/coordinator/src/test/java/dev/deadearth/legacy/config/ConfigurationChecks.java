package dev.deadearth.legacy.config;

import com.google.gson.*;
import dev.deadearth.legacy.JsonFiles;
import java.nio.file.*;
import java.util.*;

/** Pure transactions: invalid editor data cannot alter live state or partially persist. */
public final class ConfigurationChecks {
  private static int checks;
  private static final String EPOCH = "9a42d6bb-0fee-4e39-b6e1-4514676bea91";

  private static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("Configuration check " + checks);
  }

  private static JsonObject json(String text) {
    return dev.deadearth.legacy.kernel.CinematicCatalog.strict(text, 1048576, true)
        .getAsJsonObject();
  }

  private static final class Memory implements LiveConfiguration.Adapter {
    int commits;

    public Object prepare(Map<String, JsonObject> documents) {
      return documents;
    }

    public void commit(Object prepared) {
      commits++;
    }
  }

  private static void stage(Path config, String revision, String mode, String changes, String id) {
    JsonObject value =
        json(
            "{\"schema\":1,\"requestId\":\""
                + id
                + "\",\"worldEpoch\":\""
                + EPOCH
                + "\",\"baseRevision\":\""
                + revision
                + "\",\"mode\":\""
                + mode
                + "\",\"changes\":"
                + changes
                + "}");
    JsonFiles.write(config.resolve("dead-earth/config-staging/" + id + ".json"), value);
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static boolean accepted(JsonObject result) {
    return result.get("accepted").getAsBoolean();
  }

  public static void main(String[] args) throws Exception {
    check(
        ConfigurationDocuments.canonical(json("{\"value\":1.0}"))
            .toString()
            .equals(ConfigurationDocuments.canonical(json("{\"value\":1}")).toString()));
    java.util.List<Integer> loaded = new java.util.ArrayList<Integer>();
    for (int n = 0; n < 130; n++) loaded.add(n);
    dev.deadearth.legacy.combat.SightSweep<Integer> sweep =
        new dev.deadearth.legacy.combat.SightSweep<Integer>(loaded);
    java.util.Set<Integer> visited = new java.util.HashSet<Integer>();
    for (int n = 0; n < 64; n++) visited.add(sweep.next());
    loaded.remove(0);
    while (sweep.hasNext()) visited.add(sweep.next());
    check(visited.size() == 130 && visited.contains(64) && visited.contains(129));
    double[] range = dev.deadearth.legacy.combat.SightRange.update(null, 16, true, 96);
    check(range[0] == 16 && range[1] == 96);
    range = dev.deadearth.legacy.combat.SightRange.update(range, 96, true, 32);
    check(range[0] == 16 && range[1] == 32);
    range = dev.deadearth.legacy.combat.SightRange.update(range, 48, true, 32);
    check(range[0] == 48 && range[1] == 48);
    range = dev.deadearth.legacy.combat.SightRange.update(range, 48, false, 32);
    check(range[1] == 48);
    Path root = Files.createTempDirectory("dead-earth-config-checks-");
    Path config = root.resolve("config");
    try (java.util.stream.Stream<Path> paths =
        Files.walk(Paths.get("legacy/pack/config/dead-earth"))) {
      paths
          .filter(Files::isRegularFile)
          .forEach(
              source -> {
                try {
                  Path target =
                      config
                          .resolve("dead-earth")
                          .resolve(Paths.get("legacy/pack/config/dead-earth").relativize(source));
                  Files.createDirectories(target.getParent());
                  Files.copy(source, target);
                } catch (Exception error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    Memory memory = new Memory();
    LiveConfiguration live =
        new LiveConfiguration(config, root, EPOCH, "4caf2895-c806-428c-a513-3cf37fe94df0", memory);
    String revision = live.revision();
    check(revision.matches("[0-9a-f]{64}"));
    check(live.view().getAsJsonArray("sections").size() == 13);
    if (args.length > 0) JsonFiles.write(Paths.get(args[0]), live.view());
    JsonObject largeChanges = new JsonObject(),
        coordinatorChanges = new JsonObject(),
        prices = new JsonObject();
    for (int n = 0; n < 1000; n++)
      prices.addProperty("test:" + String.join("", Collections.nCopies(80, "a")) + n, 1);
    coordinatorChanges.add("resourcePrices", prices);
    largeChanges.add("coordinator", coordinatorChanges);
    String tooLarge = id();
    stage(config, revision, "restart", largeChanges.toString(), tooLarge);
    check(!accepted(live.command("validate", EPOCH, revision, tooLarge)));
    String invalidRoster = id();
    stage(
        config,
        revision,
        "restart",
        "{\"srp-entities\":{\"entities\":{\"minecraft:zombie\":0}}}",
        invalidRoster);
    check(!accepted(live.command("validate", EPOCH, revision, invalidRoster)));
    String invalidRegistry = id();
    stage(
        config, revision, "restart", "{\"sieving\":{\"ownFirstCompression\":[]}}", invalidRegistry);
    check(!accepted(live.command("validate", EPOCH, revision, invalidRegistry)));
    String checkedRequest = id();
    stage(config, revision, "hot", "{\"siege\":{\"sightRadius\":95}}", checkedRequest);
    check(accepted(live.command("validate", EPOCH, revision, checkedRequest)));
    stage(config, revision, "hot", "{\"siege\":{\"sightRadius\":94}}", checkedRequest);
    check(!accepted(live.command("apply", EPOCH, revision, checkedRequest)));
    String request = id();
    stage(config, revision, "hot", "{\"siege\":{\"sightRadius\":96}}", request);
    check(accepted(live.command("validate", EPOCH, revision, request)) && memory.commits == 0);
    check(live.revision().equals(revision));
    check(accepted(live.command("apply", EPOCH, revision, request)) && memory.commits == 1);
    String changed = live.revision();
    check(!changed.equals(revision));
    String climateRequest = id();
    stage(
        config,
        changed,
        "hot",
        "{\"climate\":{\"coldDamage\":2.5,\"cellsPerTick\":1024}}",
        climateRequest);
    check(accepted(live.command("validate", EPOCH, changed, climateRequest)));
    check(accepted(live.command("apply", EPOCH, revision, request)) && memory.commits == 1);
    stage(config, revision, "hot", "{\"siege\":{\"sightRadius\":95}}", request);
    check(!accepted(live.command("apply", EPOCH, revision, request)) && memory.commits == 1);
    for (String changes :
        Arrays.asList(
            "{\"siege\":{\"sightRadius\":129}}",
            "{\"siege\":{\"sightRadius\":\"64\"}}",
            "{\"siege\":{\"sightRadius\":64.0}}",
            "{\"siege\":{\"unknown\":true}}",
            "{\"coordinator\":{\"seasonTicks\":200}}",
            "{\"machinery\":{\"capacityFE\":1000}}",
            "{\"farming\":{\"growthPerAccelerator\":2},\"siege\":{\"sightRadius\":0}}")) {
      request = id();
      stage(config, changed, "hot", changes, request);
      check(!accepted(live.command("apply", EPOCH, changed, request)));
      check(live.revision().equals(changed) && memory.commits == 1);
    }
    request = id();
    stage(config, changed, "hot", "{\"siege\":{\"sightRadius\":32}}", request);
    check(!accepted(live.command("apply", EPOCH, revision, request)));
    check(!accepted(live.command("apply", "wrong-epoch", changed, request)));
    String oldDisk =
        new String(Files.readAllBytes(config.resolve("dead-earth/siege.json")), "UTF-8");
    live.failWriteForTest(2);
    request = id();
    stage(
        config,
        changed,
        "hot",
        "{\"siege\":{\"sightRadius\":32},\"farming\":{\"minimumLight\":10}}",
        request);
    check(!accepted(live.command("apply", EPOCH, changed, request)));
    check(
        new String(Files.readAllBytes(config.resolve("dead-earth/siege.json")), "UTF-8")
            .equals(oldDisk));
    check(live.revision().equals(changed) && memory.commits == 1);
    live.failWriteForTest(0);
    request = id();
    stage(config, changed, "restart", "{\"coordinator\":{\"seasonTicks\":200}}", request);
    JsonObject pending = live.command("apply", EPOCH, changed, request);
    check(accepted(pending) && pending.get("state").getAsString().equals("restart_pending"));
    check(memory.commits == 1 && live.revision().equals(changed));
    JsonObject marker =
        JsonFiles.read(root.resolve("runtime/compose/config-restart-request.json"), 4096);
    check(marker.get("requestId").getAsString().equals(request));
    JsonObject manifest =
        JsonFiles.read(root.resolve("runtime/compose/config-restart-backup.json"), 16384);
    check(manifest.get("oldRevision").getAsString().equals(changed));
    check(
        manifest
            .getAsJsonArray("files")
            .get(0)
            .getAsJsonObject()
            .get("id")
            .getAsString()
            .equals("coordinator"));
    LiveConfiguration.recover(config, root);
    LiveConfiguration reloaded =
        new LiveConfiguration(
            config, root, EPOCH, "4caf2895-c806-428c-a513-3cf37fe94df0", new Memory());
    check(reloaded.revision().equals(pending.get("revision").getAsString()));
    check(reloaded.receipt(request).get("state").getAsString().equals("applied"));
    check(accepted(reloaded.command("apply", EPOCH, changed, request)));
    // Simulate a process exit between two file writes, before the commit record.
    String interrupted = id();
    Path siegeFile = config.resolve("dead-earth/siege.json");
    String knownGood = new String(Files.readAllBytes(siegeFile), "UTF-8");
    Path backup = root.resolve("runtime/compose/config-backups/" + interrupted + "/siege.json");
    Files.createDirectories(backup.getParent());
    Files.write(backup, knownGood.getBytes("UTF-8"));
    JsonObject journal = JsonFiles.read(config.resolve("dead-earth/config-state.json"), 2097152);
    JsonObject transaction =
        json(
            "{\"schema\":1,\"requestId\":\""
                + interrupted
                + "\",\"worldEpoch\":\""
                + EPOCH
                + "\",\"oldRevision\":\""
                + reloaded.revision()
                + "\",\"revision\":\""
                + reloaded.revision()
                + "\",\"fingerprint\":\""
                + JsonFiles.sha("interrupted")
                + "\",\"phase\":\"prepared\",\"files\":[{\"id\":\"siege\",\"sha256\":\""
                + JsonFiles.sha(knownGood)
                + "\"}]}");
    journal.add("transaction", transaction);
    JsonFiles.write(config.resolve("dead-earth/config-state.json"), journal);
    JsonFiles.write(siegeFile, json("{\"corrupt\":true}"));
    LiveConfiguration.recover(config, root);
    check(new String(Files.readAllBytes(siegeFile), "UTF-8").equals(knownGood));
    LiveConfiguration recovered =
        new LiveConfiguration(
            config, root, EPOCH, "4caf2895-c806-428c-a513-3cf37fe94df0", new Memory());
    JsonObject recoveredReceipt = recovered.receipt(interrupted);
    check(
        !accepted(recoveredReceipt)
            && recoveredReceipt
                .getAsJsonArray("errors")
                .get(0)
                .getAsJsonObject()
                .get("code")
                .getAsString()
                .equals("recovery_reverted"));
    System.out.println("Configuration checks passed: " + checks);
  }
}
