package dev.deadearth.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.deadearth.DeadEarth;
import dev.deadearth.bridge.BridgeController;
import dev.deadearth.bridge.BridgeProtocol;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dead_earth")
@PrefixGameTestTemplate(false)
public final class BridgeGameTests {
    @GameTest(template = "empty", batch = "bridge")
    public static void bridgeKeepsReadsOffThreadAndQueuesWorldCalls(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer(); var runtime = DeadEarth.runtime(server);
        var descriptor = server.getWorldPath(LevelResource.ROOT).resolve("dead_earth_bridge.json");
        try (var bridge = new BridgeController(server, runtime)) {
            helper.assertTrue(!bridge.running(), "Bridge enabled without operator request");
            var status = bridge.start();
            var secret = JsonParser.parseString(Files.readString(descriptor)).getAsJsonObject().get("token").getAsString();
            helper.assertTrue(!status.toString().contains(secret), "Operator status leaked token");
            var cached = CompletableFuture.supplyAsync(() -> bridge.request(new BridgeProtocol.Request("get_snapshot", new JsonObject())).join()).join();
            helper.assertTrue(cached.getAsJsonObject().has("snapshot"), "Worker could not read cached snapshot");
            var args = new JsonObject(); args.addProperty("request_id", UUID.randomUUID().toString());
            var proposed = CompletableFuture.supplyAsync(() -> bridge.request(new BridgeProtocol.Request("propose_scenario", args))).join();
            helper.assertTrue(!proposed.isDone(), "Worker directly changed campaign");
            bridge.tick(); var receipt = proposed.join().getAsJsonObject();
            var duplicate = bridge.request(new BridgeProtocol.Request("propose_scenario", args)); bridge.tick();
            helper.assertTrue(receipt.get("scenarioId").equals(duplicate.join().getAsJsonObject().get("scenarioId")), "MCP duplicate created another scenario");
            var cancel = new JsonObject(); cancel.add("scenario_id", receipt.get("scenarioId")); cancel.add("revision", receipt.get("revision"));
            var cancelled = bridge.request(new BridgeProtocol.Request("cancel_scenario", cancel)); bridge.tick();
            helper.assertTrue(cancelled.join().getAsJsonObject().get("state").getAsString().equals("CANCELLED"), "Queued cancellation failed");
        }
        helper.assertTrue(!Files.exists(descriptor), "Owned token descriptor remained after stop"); helper.succeed();
    }
    @GameTest(template = "empty", batch = "bridge")
    public static void cacheRefreshesWhenCampaignClockIsPaused(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var runtime = DeadEarth.runtime(server);
        var client = FoundationGameTests.attach(helper); boolean attached = true, scheduled = false;
        var bridge = new BridgeController(server, runtime);
        try {
            bridge.start(); client.close(); attached = false;
            long before = runtime.campaign().campaign().activeTicks();
            for (int i = 0; i < 200; i++) bridge.tick();
            awaitOfflineSnapshot(helper, bridge, runtime, before, 60); scheduled = true;
        } finally { if (attached) client.close(); if (!scheduled) bridge.close(); }
    }
    private static void awaitOfflineSnapshot(GameTestHelper helper, BridgeController bridge,
            dev.deadearth.CoordinatorRuntime runtime, long before, int remaining) {
        helper.runAfterDelay(1, () -> {
            boolean finished = false;
            try {
                // get_snapshot reads an already published cache; it never waits for the encoder.
                var cached = bridge.request(new BridgeProtocol.Request("get_snapshot", new JsonObject())).join().getAsJsonObject();
                if (cached.getAsJsonObject("snapshot").get("onlinePlayers").getAsInt() != 0 && remaining > 1) {
                    awaitOfflineSnapshot(helper, bridge, runtime, before, remaining - 1); return;
                }
                finished = true;
                helper.assertTrue(cached.getAsJsonObject("snapshot").get("onlinePlayers").getAsInt() == 0, "Offline state never refreshed");
                helper.assertTrue(runtime.campaign().campaign().activeTicks() == before, "Cache heartbeat advanced campaign clock");
                helper.succeed();
            } catch (RuntimeException | Error failure) { finished = true; throw failure; }
            finally { if (finished) bridge.close(); }
        });
    }
    @GameTest(template = "empty", batch = "bridge")
    public static void stopRejectsPendingCallsAndRestartRotatesToken(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer(); var runtime = DeadEarth.runtime(server);
        var path = server.getWorldPath(LevelResource.ROOT).resolve("dead_earth_bridge.json");
        try (var bridge = new BridgeController(server, runtime)) {
            bridge.start(); var first = JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("token");
            int count = runtime.receipts().size(); var args = new JsonObject(); args.addProperty("request_id", UUID.randomUUID().toString());
            var pending = bridge.request(new BridgeProtocol.Request("propose_scenario", args)); bridge.close();
            helper.assertTrue(pending.isCompletedExceptionally(), "Stopped bridge retained queued action");
            bridge.start(); var second = JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("token");
            helper.assertTrue(!first.equals(second), "Restart reused bridge secret"); bridge.tick();
            helper.assertTrue(runtime.receipts().size() == count, "Stopped request was executed by new session");
        }
        helper.succeed();
    }
}
