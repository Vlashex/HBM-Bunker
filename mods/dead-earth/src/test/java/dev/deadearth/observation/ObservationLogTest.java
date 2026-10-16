package dev.deadearth.observation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservationLogTest {
    private final UUID player = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @Test void negativeCoordinatesUseFloorRegions() {
        var log = new ObservationLog();
        log.sample(player, "minecraft:overworld", -1, -65, 18, 200);
        var fact = log.snapshot().players().getFirst();
        assertEquals(-1, fact.regionX());
        assertEquals(-2, fact.regionZ());
    }
    @Test void repeatedTeamVisitsDoNotInventNewRegions() {
        var log = new ObservationLog();
        log.sample(player, "minecraft:overworld", 1, 1, 20, 200);
        log.sample(UUID.randomUUID(), "minecraft:overworld", 63, 63, 10, 400);
        assertEquals(1, log.snapshot().regions().size());
        assertEquals(1, log.snapshot().history().size());
        assertEquals(2, log.snapshot().players().size());
    }
    @Test void dimensionsHaveIndependentRegions() {
        var log = new ObservationLog();
        log.sample(player, "minecraft:overworld", 0, 0, 20, 200);
        log.sample(player, "minecraft:the_nether", 0, 0, 20, 400);
        assertEquals(2, log.snapshot().regions().size());
        assertEquals("minecraft:the_nether", log.snapshot().players().getFirst().dimension());
    }
    @Test void boundedRegionsKeepPreviouslyObservedFacts() {
        var log = new ObservationLog();
        for (int i = 0; i < 1030; i++) log.sample(player, "minecraft:overworld", i * 64, 0, 20, i);
        assertEquals(1024, log.snapshot().regions().size());
        assertTrue(log.snapshot().regions().stream().anyMatch(r -> r.x() == 0));
        assertEquals(1029, log.snapshot().players().getFirst().regionX());
    }
    @Test void playerLimitDoesNotStopUpdatingExistingPlayers() {
        var log = new ObservationLog();
        for (int i = 0; i < 64; i++) log.sample(new UUID(0, i), "minecraft:overworld", 0, 0, 20, 0);
        assertFalse(log.sample(player, "minecraft:overworld", 0, 0, 20, 1));
        assertTrue(log.sample(new UUID(0, 0), "minecraft:overworld", 128, 0, 5, 2));
        assertEquals(64, log.snapshot().players().size());
        assertEquals(5, log.snapshot().players().stream().filter(p -> p.player().equals(new UUID(0, 0))).findFirst().orElseThrow().health());
    }
    @Test void historyKeepsLatest512RealEvents() {
        var log = new ObservationLog();
        for (int i = 0; i < 520; i++) log.record(i, ObservationLog.Kind.DEATH, player, "minecraft:overworld");
        assertEquals(512, log.snapshot().history().size());
        assertEquals(8, log.snapshot().history().getFirst().tick());
        assertEquals(519, log.snapshot().history().getLast().tick());
    }
    @Test void snapshotsAreImmutableAndRejectInvalidObservations() {
        var log = new ObservationLog();
        assertThrows(IllegalArgumentException.class, () -> log.sample(player, "minecraft:overworld", 0, 0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> log.sample(player, "bad dimension", 0, 0, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> log.record(-1, ObservationLog.Kind.DEATH, player, "x"));
        assertThrows(UnsupportedOperationException.class, () -> log.snapshot().history().clear());
    }
}
