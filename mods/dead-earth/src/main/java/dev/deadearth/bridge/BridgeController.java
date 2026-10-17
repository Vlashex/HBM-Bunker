package dev.deadearth.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.deadearth.CoordinatorRuntime;
import dev.deadearth.DeadEarth;
import dev.deadearth.scenario.ScenarioChoice;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class BridgeController implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final MinecraftServer server;
    private final CoordinatorRuntime runtime;
    private volatile boolean running;
    private volatile UUID generation;
    private SnapshotCache cache;
    private AsyncSnapshotPublisher<Optional<CoordinatorRuntime.Snapshot>> snapshots;
    private ServerCallQueue calls;
    private LocalBridge transport;
    private Path descriptor;
    private String descriptorContents;
    private int ticks;
    public BridgeController(MinecraftServer server, CoordinatorRuntime runtime) { this.server = server; this.runtime = runtime; }
    public boolean running() { return running; }
    public JsonObject start() {
        runtime.requireServerThread(); if (running) return status();
        if (!runtime.enabled()) throw new IllegalStateException("Coordinator unavailable");
        var secret = new byte[32]; new SecureRandom().nextBytes(secret);
        var token = HexFormat.of().formatHex(secret);
        var protocol = new BridgeProtocol(runtime.campaign().campaign().campaignId(), runtime.epoch(), token);
        var nextGeneration = UUID.randomUUID();
        var nextCache = new SnapshotCache(System::nanoTime); var nextCalls = new ServerCallQueue(System::nanoTime);
        long capturedNanos = System.nanoTime();
        nextCache.publish(JSON.toJsonTree(runtime.snapshot()).getAsJsonObject(), capturedNanos);
        var nextSnapshots = new AsyncSnapshotPublisher<Optional<CoordinatorRuntime.Snapshot>>(nextCache,
                BridgeController::encodeSnapshot, failed -> DeadEarth.LOGGER.warn("Bridge snapshot encoding failed; previous snapshot retained", failed));
        LocalBridge nextTransport = null;
        try {
            nextTransport = new LocalBridge(protocol, request -> dispatch(nextGeneration, nextCache, nextCalls, request));
            var info = new JsonObject(); info.addProperty("protocol", 1); info.addProperty("host", nextTransport.host());
            info.addProperty("port", nextTransport.port()); info.addProperty("token", token);
            info.addProperty("campaign_id", runtime.campaign().campaign().campaignId().toString()); info.addProperty("world_epoch", runtime.epoch().toString());
            descriptor = server.getWorldPath(LevelResource.ROOT).resolve("dead_earth_bridge.json");
            descriptorContents = info.toString() + "\n";
            writeDescriptor(descriptor, descriptorContents);
            cache = nextCache; snapshots = nextSnapshots; calls = nextCalls; transport = nextTransport; generation = nextGeneration; ticks = 0; running = true;
            DeadEarth.LOGGER.info("Dead Earth bridge listening on 127.0.0.1:{}; bounded test-scenario catalog", transport.port());
            return status();
        } catch (IOException failed) {
            nextSnapshots.close(); nextCalls.close(); if (nextTransport != null) nextTransport.close();
            throw new IllegalStateException("Unable to start local bridge", failed);
        } catch (RuntimeException failed) {
            nextSnapshots.close(); nextCalls.close(); if (nextTransport != null) nextTransport.close();
            throw failed;
        }
    }
    public JsonObject status() {
        runtime.requireServerThread(); var status = new JsonObject(); status.addProperty("running", running);
        if (running) { status.addProperty("host", transport.host()); status.addProperty("port", transport.port()); status.addProperty("descriptor", descriptor.toAbsolutePath().toString()); }
        return status;
    }
    public CompletableFuture<JsonElement> request(BridgeProtocol.Request request) {
        if (!running) return CompletableFuture.failedFuture(new BridgeFailure("bridge_stopped"));
        return dispatch(generation, cache, calls, request);
    }
    private CompletableFuture<JsonElement> dispatch(UUID expectedGeneration, SnapshotCache snapshot, ServerCallQueue queue, BridgeProtocol.Request request) {
        if (!running || !expectedGeneration.equals(generation)) return CompletableFuture.failedFuture(new BridgeFailure("bridge_stopped"));
        try {
            return switch (request.operation()) {
                case "get_snapshot" -> CompletableFuture.completedFuture(snapshot.get());
                case "get_history" -> {
                    var cached = snapshot.get(); var data = cached.getAsJsonObject("snapshot");
                    if (!data.has("observations")) throw new BridgeFailure("snapshot_unavailable");
                    var history = data.getAsJsonObject("observations").getAsJsonArray("history");
                    int limit = request.arguments().get("limit").getAsInt(); var result = new JsonArray();
                    for (int i = Math.max(0, history.size() - limit); i < history.size(); i++) result.add(history.get(i));
                    var reply = new JsonObject(); reply.add("cache_age_millis", cached.get("cache_age_millis")); reply.add("history", result);
                    yield CompletableFuture.completedFuture(reply);
                }
                case "list_catalog" -> CompletableFuture.completedFuture(catalog());
                default -> queue.submit(request.operation(), request.arguments());
            };
        } catch (RuntimeException failed) { return CompletableFuture.failedFuture(failed); }
    }
    public void tick() {
        runtime.requireServerThread(); if (!running) return;
        calls.drain((operation, args) -> switch (operation) {
            case "get_cinematic_brief" -> JSON.toJsonTree(runtime.cinematics().brief());
            case "get_cinematic_queue" -> JSON.toJsonTree(runtime.cinematics().status());
            case "choose_cinematic" -> JSON.toJsonTree(runtime.cinematics().choose(
                UUID.fromString(args.get("request_id").getAsString()),UUID.fromString(args.get("goal_id").getAsString()),
                args.get("event_id").getAsString(),args.get("zone_id").getAsString()));
            case "get_director_brief" -> JSON.toJsonTree(runtime.directorBrief());
            case "preview_director" -> JSON.toJsonTree(runtime.previewDirector(
                    UUID.fromString(args.get("request_id").getAsString()),UUID.fromString(args.get("goal_id").getAsString()),
                    args.get("variant").getAsString(),args.get("text").getAsString()));
            case "preview_scenario" -> JSON.toJsonTree(runtime.preview(UUID.fromString(args.get("request_id").getAsString()),BridgeProtocol.choice(args)));
            case "propose_scenario" -> JSON.toJsonTree(runtime.submit(UUID.fromString(args.get("request_id").getAsString()),BridgeProtocol.choice(args)));
            case "get_scenario_status" -> JSON.toJsonTree(runtime.findScenario(UUID.fromString(args.get("scenario_id").getAsString()))
                    .orElseThrow(() -> new BridgeFailure("unknown_scenario")));
            case "cancel_scenario" -> JSON.toJsonTree(runtime.cancel(UUID.fromString(args.get("scenario_id").getAsString()), args.get("revision").getAsLong()));
            default -> throw new BridgeFailure("unknown_operation");
        });
        if (++ticks == 200) {
            ticks = 0;
            long capturedNanos = System.nanoTime();
            snapshots.submit(runtime.enabled() ? Optional.of(runtime.snapshot()) : Optional.empty(), capturedNanos);
        }
    }
    private static JsonObject encodeSnapshot(Optional<CoordinatorRuntime.Snapshot> snapshot) {
        if (snapshot.isPresent()) return JSON.toJsonTree(snapshot.get()).getAsJsonObject();
        var unavailable = new JsonObject(); unavailable.addProperty("enabled", false); return unavailable;
    }
    private static JsonArray catalog() {
        var entry = new JsonObject(); entry.addProperty("catalogId", "warning_beacon_v1"); entry.addProperty("effect", "chat_only");
        entry.addProperty("earliestActiveTicks", 200); entry.addProperty("expiresAfterActiveTicks", 1200); entry.addProperty("targetId", "test_anchor");
        var result = new JsonArray(); result.add(entry);
        var radio = new JsonObject(); radio.addProperty("catalogId",ScenarioChoice.RADIO);radio.addProperty("effect","chat_only");
        radio.addProperty("targetSource","discoveredSites[].id");radio.addProperty("targetIdFormat","relay:<canonical UUID>");
        var variants = new JsonArray();java.util.List.of("check_in","survey","rally").forEach(variants::add);radio.add("variants",variants);
        radio.addProperty("delayMinActiveTicks",200);radio.addProperty("delayMaxActiveTicks",1200);radio.addProperty("delayStepActiveTicks",20);
        radio.addProperty("expiresAfterEarliestActiveTicks",1000);radio.addProperty("maxPendingGlobal",4);radio.addProperty("maxPendingPerTarget",1);
        radio.addProperty("cooldownFromEarliestActiveTicks",2400);result.add(radio);return result;
    }
    private static void writeDescriptor(Path path, String contents) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        var temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "dead-earth-bridge-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() {
        runtime.requireServerThread(); running = false;
        if (snapshots != null) snapshots.close();
        if (calls != null) calls.close(); if (transport != null) transport.close();
        if (descriptor != null) try {
            if (Files.isRegularFile(descriptor) && Files.size(descriptor) <= 4096
                    && Files.readString(descriptor, StandardCharsets.UTF_8).equals(descriptorContents)) Files.delete(descriptor);
        } catch (IOException failed) { DeadEarth.LOGGER.warn("Could not remove owned bridge descriptor", failed); }
    }
}
