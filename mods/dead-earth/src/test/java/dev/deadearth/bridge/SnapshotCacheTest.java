package dev.deadearth.bridge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnapshotCacheTest {
    private JsonObject raw() {
        var snapshot = new JsonObject(); snapshot.addProperty("onlinePlayers", 0);
        var observations = new JsonObject();
        for (var key : new String[] {"players", "regions", "history"}) {
            var rows = new JsonArray(); for (int i = 0; i < 150; i++) rows.add(i); observations.add(key, rows);
        }
        snapshot.add("observations", observations);
        var sites = new JsonArray(); for (int i = 0; i < 70; i++) sites.add(i); snapshot.add("discoveredSites", sites); return snapshot;
    }
    @Test void limitsProjectionAndKeepsMostRecentRegionsAndHistory() {
        var cache = new SnapshotCache(() -> 0L); cache.publish(raw());
        var snapshot = cache.get().getAsJsonObject("snapshot"); var observations = snapshot.getAsJsonObject("observations");
        assertEquals(64, observations.getAsJsonArray("players").size());
        assertEquals(128, observations.getAsJsonArray("regions").size()); assertEquals(22, observations.getAsJsonArray("regions").get(0).getAsInt());
        assertEquals(100, observations.getAsJsonArray("history").size()); assertEquals(50, observations.getAsJsonArray("history").get(0).getAsInt());
        assertEquals(64, snapshot.getAsJsonArray("discoveredSites").size());
        assertEquals(22, observations.get("omittedRegions").getAsInt());
    }
    @Test void publisherAndCallerCannotMutateCachedFactsAndAgeDoesNotFollowCampaignClock() {
        var clock = new AtomicLong(); var cache = new SnapshotCache(clock::get); var source = raw(); cache.publish(source);
        source.addProperty("onlinePlayers", 999); clock.set(300_000_000L);
        var first = cache.get(); assertEquals(300, first.get("cache_age_millis").getAsLong());
        first.getAsJsonObject("snapshot").addProperty("onlinePlayers", 111);
        assertEquals(0, cache.get().getAsJsonObject("snapshot").get("onlinePlayers").getAsInt());
        cache.publish(raw()); assertEquals(0, cache.get().get("cache_age_millis").getAsLong());
    }
    @Test void unpublishedSnapshotIsUnavailable() {
        var cache = new SnapshotCache(() -> 0L);
        assertEquals("snapshot_unavailable", assertThrows(BridgeFailure.class, cache::get).code());
    }
}
