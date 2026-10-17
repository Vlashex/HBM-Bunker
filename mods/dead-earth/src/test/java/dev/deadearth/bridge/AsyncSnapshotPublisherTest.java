package dev.deadearth.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.deadearth.CoordinatorRuntime;
import dev.deadearth.climate.ClimateCycle;
import dev.deadearth.difficulty.AdaptiveStore;
import dev.deadearth.observation.ObservationLog;
import dev.deadearth.site.SiteRegistry;
import dev.deadearth.zone.ZoneScanner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsyncSnapshotPublisherTest {
    private static final Gson JSON = new Gson();
    private static final UUID CAMPAIGN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static CoordinatorRuntime.Snapshot snapshot(long tick) {
        return snapshot(tick, List.of(), List.of());
    }

    private static CoordinatorRuntime.Snapshot snapshot(long tick, List<SiteRegistry.Site> sites,
            List<ZoneScanner.Summary> zones) {
        return new CoordinatorRuntime.Snapshot(CAMPAIGN, EPOCH, tick, 2,
                new ObservationLog.Snapshot(List.of(), List.of(), List.of()), sites,
                new ClimateCycle.Snapshot(0, ClimateCycle.Phase.CALM, ClimateCycle.Trend.WARMING,
                        0, 0, 0, 48000, 48000 - tick, ClimateCycle.Phase.HEAT, ClimateCycle.Trend.NONE, 48000, tick),
                true, new AdaptiveStore.Snapshot(1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0), zones);
    }

    private static JsonObject encode(CoordinatorRuntime.Snapshot snapshot) {
        return JSON.toJsonTree(snapshot).getAsJsonObject();
    }

    private static long tick(SnapshotCache cache) {
        return cache.get().getAsJsonObject("snapshot").get("activeTicks").getAsLong();
    }

    private static void await(CountDownLatch gate) {
        try { assertTrue(gate.await(5, TimeUnit.SECONDS), "Worker gate timed out"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }

    private static boolean result(CompletableFuture<Boolean> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch gate) {
        boolean interrupted = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                try {
                    assertTrue(gate.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                            "Worker gate timed out");
                    return;
                } catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Test void encodingRunsOnOneDaemonAndOnlyNewestPendingSnapshotIsEncoded() throws Exception {
        var cache = new SnapshotCache(() -> 0L); cache.publish(encode(snapshot(0)));
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var encoded = Collections.synchronizedList(new ArrayList<Long>());
        var worker = new AtomicReference<Thread>(); var caller = Thread.currentThread();
        try (var publisher = new AsyncSnapshotPublisher<CoordinatorRuntime.Snapshot>(cache, value -> {
            var previous = worker.getAndSet(Thread.currentThread());
            assertTrue(previous == null || previous == Thread.currentThread(), "More than one encoder thread");
            encoded.add(value.activeTicks());
            if (value.activeTicks() == 1) { started.countDown(); await(release); }
            return encode(value);
        }, failure -> fail("Unexpected serialization failure", failure))) {
            try {
                var first = publisher.submit(snapshot(1), 0); await(started);
                assertNotSame(caller, worker.get()); assertTrue(worker.get().isDaemon());
                assertEquals(0, tick(cache), "Capture blocked the caller or changed the previous cache");
                var replaced = new ArrayList<CompletableFuture<Boolean>>();
                for (int id = 2; id < 50; id++) replaced.add(publisher.submit(snapshot(id), 0));
                var last = publisher.submit(snapshot(50), 0);
                for (var discarded : replaced) assertFalse(result(discarded));
                release.countDown(); assertTrue(result(first)); assertTrue(result(last));
                assertEquals(List.of(1L, 50L), encoded);
                assertEquals(50, tick(cache));
            } finally { release.countDown(); }
        }
    }

    @Test void failedEncodingKeepsPreviousCacheAndWorkerAcceptsTheNextSnapshot() throws Exception {
        var cache = new SnapshotCache(() -> 0L); cache.publish(encode(snapshot(0)));
        var failure = new IllegalArgumentException("controlled serialization failure");
        var reported = new AtomicReference<RuntimeException>();
        try (var publisher = new AsyncSnapshotPublisher<CoordinatorRuntime.Snapshot>(cache, value -> {
            if (value.activeTicks() == 1) throw failure;
            return encode(value);
        }, reported::set)) {
            var rejected = publisher.submit(snapshot(1), 0);
            assertSame(failure, assertThrows(ExecutionException.class, () -> result(rejected)).getCause());
            assertSame(failure, reported.get()); assertEquals(0, tick(cache));
            assertTrue(result(publisher.submit(snapshot(2), 0))); assertEquals(2, tick(cache));
        }
    }

    @Test void closeDoesNotWaitForEncodingAndOldGenerationCannotPublishLater() throws Exception {
        var oldCache = new SnapshotCache(() -> 0L); oldCache.publish(encode(snapshot(0)));
        var nextCache = new SnapshotCache(() -> 0L); nextCache.publish(encode(snapshot(100)));
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var oldThread = new AtomicReference<Thread>();
        var oldFailure = new AtomicReference<RuntimeException>();
        var old = new AsyncSnapshotPublisher<CoordinatorRuntime.Snapshot>(oldCache, value -> {
            oldThread.set(Thread.currentThread()); started.countDown(); awaitIgnoringInterrupt(release); return encode(value);
        }, oldFailure::set);
        try (var next = new AsyncSnapshotPublisher<CoordinatorRuntime.Snapshot>(nextCache,
                AsyncSnapshotPublisherTest::encode, failure -> fail("Unexpected serialization failure", failure))) {
            var active = old.submit(snapshot(1), 0); await(started);
            var pending = old.submit(snapshot(2), 0);
            assertTimeoutPreemptively(Duration.ofSeconds(1), old::close);
            assertFalse(result(active)); assertFalse(result(pending));
            assertFalse(result(old.submit(snapshot(3), 0)));
            assertTrue(result(next.submit(snapshot(101), 0)));
            release.countDown(); oldThread.get().join(5000);
            assertFalse(oldThread.get().isAlive(), "Closed encoder thread did not stop");
            assertNull(oldFailure.get(), "Late successful encode unexpectedly failed");
            assertEquals(0, tick(oldCache), "Closed generation published a late snapshot");
            assertEquals(101, tick(nextCache), "Old generation changed the new cache");
        } finally { release.countDown(); old.close(); }
    }

    @Test void snapshotAgeIncludesTimeWaitingForWorkerEncoding() throws Exception {
        var clock = new AtomicLong(10_000_000L); var cache = new SnapshotCache(clock::get);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var publisher = new AsyncSnapshotPublisher<CoordinatorRuntime.Snapshot>(cache, value -> {
            started.countDown(); await(release); return encode(value);
        }, failure -> fail("Unexpected serialization failure", failure))) {
            try {
                var submitted = publisher.submit(snapshot(1), clock.get()); await(started);
                clock.set(910_000_000L); release.countDown(); assertTrue(result(submitted));
                assertEquals(900, cache.get().get("cache_age_millis").getAsLong());
            } finally { release.countDown(); }
        }
    }

    @Test void snapshotDefensivelyCopiesSiteAndZoneListsBeforeTheWorkerReadsThem() throws Exception {
        var sites = new ArrayList<SiteRegistry.Site>();
        sites.add(new SiteRegistry.Site(CAMPAIGN, EPOCH, 0, 64, 0, 0, SiteRegistry.State.READY, CAMPAIGN, 0));
        var zones = new ArrayList<ZoneScanner.Summary>();
        zones.add(new ZoneScanner.Summary(EPOCH, "minecraft:overworld", 1, 9, 0, true, true,
                100, 0, .0001, .01, 0, 0, 0, 0, 0, 0, 0, false, false, 0));
        var captured = snapshot(1, sites, zones);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cache = new SnapshotCache(() -> 0L);
        try (var publisher = new AsyncSnapshotPublisher<CoordinatorRuntime.Snapshot>(cache, value -> {
            started.countDown(); await(release); return encode(value);
        }, failure -> fail("Unexpected serialization failure", failure))) {
            try {
                var submitted = publisher.submit(captured, 0); await(started);
                sites.clear(); zones.clear();
                assertThrows(UnsupportedOperationException.class, () -> captured.discoveredSites().clear());
                assertThrows(UnsupportedOperationException.class, () -> captured.zones().clear());
                release.countDown(); assertTrue(result(submitted));
                var data = cache.get().getAsJsonObject("snapshot");
                assertEquals(1, data.getAsJsonArray("discoveredSites").size());
                assertEquals(1, data.getAsJsonArray("zones").size());
            } finally { release.countDown(); }
        }
    }

    @Test void invalidProjectionKeepsPreviousCacheAndDoesNotKillTheWorker() throws Exception {
        var cache = new SnapshotCache(() -> 0L); cache.publish(encode(snapshot(0)));
        var reported = new AtomicReference<RuntimeException>();
        try (var publisher = new AsyncSnapshotPublisher<CoordinatorRuntime.Snapshot>(cache, value -> {
            if (value.activeTicks() == 1) {
                var malformed = new JsonObject(); malformed.add("observations", new JsonObject()); return malformed;
            }
            return encode(value);
        }, reported::set)) {
            var malformed = publisher.submit(snapshot(1), 0);
            assertThrows(ExecutionException.class, () -> result(malformed));
            assertNotNull(reported.get()); assertEquals(0, tick(cache));
            assertTrue(result(publisher.submit(snapshot(2), 0))); assertEquals(2, tick(cache));
        }
    }
}
