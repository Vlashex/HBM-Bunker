package dev.deadearth.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class SnapshotCache {
    static final class Prepared {
        private final JsonObject data;
        private final long captured;
        private Prepared(JsonObject data, long captured) { this.data = data; this.captured = captured; }
    }
    private final AtomicReference<Prepared> latest = new AtomicReference<>();
    private final LongSupplier clock;
    public SnapshotCache(LongSupplier clock) { this.clock = clock; }
    public void publish(JsonObject raw) {
        publish(raw, clock.getAsLong());
    }
    public void publish(JsonObject raw, long capturedNanos) {
        publishPrepared(prepare(raw, capturedNanos));
    }
    static Prepared prepare(JsonObject raw, long capturedNanos) {
        var data = raw.deepCopy();
        if (data.has("observations")) {
            var observations = data.getAsJsonObject("observations");
            trim(observations, "players", 64, "omittedPlayers");
            trim(observations, "regions", 128, "omittedRegions");
            trim(observations, "history", 100, "omittedHistory");
        }
        if (data.has("discoveredSites")) trim(data, "discoveredSites", 64, "omittedSites");
        return new Prepared(data, capturedNanos);
    }
    void publishPrepared(Prepared prepared) { latest.set(prepared); }
    public JsonObject get() {
        var cached = latest.get(); if (cached == null) throw new BridgeFailure("snapshot_unavailable");
        var reply = new JsonObject(); reply.addProperty("cache_age_millis", Math.max(0, clock.getAsLong() - cached.captured) / 1_000_000L);
        reply.add("snapshot", cached.data.deepCopy()); return reply;
    }
    private static void trim(JsonObject object, String key, int max, String omitted) {
        var source = object.getAsJsonArray(key); var result = new JsonArray();
        for (int i = Math.max(0, source.size() - max); i < source.size(); i++) result.add(source.get(i));
        object.add(key, result); object.addProperty(omitted, Math.max(0, source.size() - max));
    }
}
