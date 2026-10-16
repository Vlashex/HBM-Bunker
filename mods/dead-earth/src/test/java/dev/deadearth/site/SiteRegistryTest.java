package dev.deadearth.site;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SiteRegistryTest {
    @Test void restoredReadySiteNeedsPhysicalValidationBeforeDiscovery() {
        var registry = new SiteRegistry();
        var site = registry.reserve(UUID.randomUUID(), 0, 70, 0, 0);
        registry.complete(site.id());
        var restored = SiteRegistry.restore(registry.sites());
        assertEquals("VERIFYING", restored.sites().getFirst().state().name());
        assertTrue(restored.discover(UUID.randomUUID(), 4, 71, 4, 200).isEmpty());
    }
    @Test void duplicatePlacementKeepsIdentityAndRejectsDifferentCoordinates() {
        var registry = new SiteRegistry(); var request = UUID.randomUUID();
        var site = registry.reserve(request, 10, 70, 10, 200);
        assertEquals(site, registry.reserve(request, 10, 70, 10, 300));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve(request, 11, 70, 10, 300));
        assertEquals(1, registry.sites().size());
    }
    @Test void restoreDoesNotRetryInterruptedPlacement() {
        var registry = new SiteRegistry();
        var site = registry.reserve(UUID.randomUUID(), 10, 70, 10, 200);
        var restored = SiteRegistry.restore(registry.sites());
        assertEquals(SiteRegistry.State.UNCERTAIN, restored.sites().getFirst().state());
        assertEquals(site.id(), restored.sites().getFirst().id());
        assertThrows(IllegalStateException.class, () -> restored.complete(site.id()));
    }
    @Test void onlyReadyNearbySiteIsDiscoveredAndOnlyOnceForTeam() {
        var registry = new SiteRegistry(); var player = UUID.randomUUID();
        var site = registry.reserve(UUID.randomUUID(), 0, 70, 0, 200);
        assertTrue(registry.discover(player, 4, 71, 4, 250).isEmpty());
        registry.complete(site.id());
        assertTrue(registry.discover(player, 100, 71, 4, 250).isEmpty());
        assertTrue(registry.discover(player, 4, 100, 4, 250).isEmpty());
        var found = registry.discover(player, 4, 71, 4, 250);
        assertEquals(1, found.size());
        assertEquals(player, found.getFirst().discoverer());
        assertEquals(250, found.getFirst().discoveredTick());
        assertTrue(registry.discover(UUID.randomUUID(), 4, 71, 4, 300).isEmpty());
    }
    @Test void placementCapacityRetainsOriginalReceipts() {
        var registry = new SiteRegistry();
        for (int i = 0; i < 64; i++) registry.reserve(new UUID(0, i), i * 16, 70, 0, 0);
        assertThrows(IllegalStateException.class, () -> registry.reserve(UUID.randomUUID(), 2000, 70, 0, 0));
        assertEquals(64, registry.sites().size());
        assertEquals(64, SiteRegistry.restore(registry.sites()).sites().size());
    }
    @Test void invalidAndDuplicateSavedSitesAreRejected() {
        var registry = new SiteRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.reserve(UUID.randomUUID(), 30000000, 70, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve(UUID.randomUUID(), 0, 319, 0, 0));
        var site = registry.reserve(UUID.randomUUID(), 0, 70, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> SiteRegistry.restore(List.of(site, site)));
    }
}
