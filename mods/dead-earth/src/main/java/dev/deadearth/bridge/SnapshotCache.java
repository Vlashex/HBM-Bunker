package dev.deadearth.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class SnapshotCache {
    private record Cached(JsonObject data, long captured) {}
    private final AtomicReference<Cached> latest = new AtomicReference<>();
    private final LongSupplier clock;
    public SnapshotCache(LongSupplier clock) { this.clock = clock; }
    public void publish(JsonObject raw) {
        var data = raw.deepCopy();
        if (data.has("observations")) {
            var observations = data.getAsJsonObject("observations");
            trim(observations, "players", 64, "omittedPlayers");
            trim(observations, "regions", 128, "omittedRegions");
            trim(observations, "history", 100, "omittedHistory");
        }
        if (data.has("discoveredSites")) trim(data, "discoveredSites", 64, "omittedSites");
        latest.set(new Cached(data, clock.getAsLong()));
    }
    public JsonObject get() {
        var cached = latest.get(); if (cached == null) throw new BridgeFailure("snapshot_unavailable");
        var reply = new JsonObject(); reply.addProperty("cache_age_millis", Math.max(0, clock.getAsLong() - cached.captured()) / 1_000_000L);
        reply.add("snapshot", cached.data().deepCopy()); return reply;
    }
    private static void trim(JsonObject object, String key, int max, String omitted) {
        var source = object.getAsJsonArray(key); var result = new JsonArray();
        for (int i = Math.max(0, source.size() - max); i < source.size(); i++) result.add(source.get(i));
        object.add(key, result); object.addProperty(omitted, Math.max(0, source.size() - max));
    }
}
