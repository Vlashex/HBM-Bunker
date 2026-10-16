package dev.deadearth.site;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class SiteStoreTest {
    @Test void roundTripKeepsDiscoveryAndPlacementIdentity() {
        var campaign = UUID.randomUUID(); var store = SiteStore.create(campaign);
        var site = store.registry().reserve(UUID.randomUUID(), 0, 70, 0, 200);
        store.registry().complete(site.id());
        store.registry().discover(UUID.randomUUID(), 4, 71, 4, 300);
        var restored = SiteStore.load(campaign, store.save(new CompoundTag(), null), null);
        assertTrue(restored.enabled());
        var loaded = restored.registry().sites().getFirst();
        var original = store.registry().sites().getFirst();
        assertEquals(original.id(), loaded.id()); assertEquals(original.request(), loaded.request());
        assertEquals(original.discoverer(), loaded.discoverer()); assertEquals(300, loaded.discoveredTick());
        assertEquals(SiteRegistry.State.VERIFYING, loaded.state());
        restored.registry().verify(loaded.id(), true);
        assertEquals(original, restored.registry().sites().getFirst());
    }
    @Test void interruptedPlacementBecomesDirtyUncertainReceipt() {
        var campaign = UUID.randomUUID(); var store = SiteStore.create(campaign);
        store.registry().reserve(UUID.randomUUID(), 0, 70, 0, 200);
        var restored = SiteStore.load(campaign, store.save(new CompoundTag(), null), null);
        assertTrue(restored.enabled()); assertTrue(restored.isDirty());
        assertEquals(SiteRegistry.State.UNCERTAIN, restored.registry().sites().getFirst().state());
    }
    @Test void unknownOrForeignSaveIsPreserved() {
        var campaign = UUID.randomUUID(); var raw = SiteStore.create(campaign).save(new CompoundTag(), null);
        assertFalse(SiteStore.load(UUID.randomUUID(), raw, null).enabled());
        raw.putInt("schemaVersion", 20);
        var restored = SiteStore.load(campaign, raw, null);
        assertFalse(restored.enabled()); assertFalse(restored.isDirty());
        assertEquals(raw, restored.save(new CompoundTag(), null));
    }
}
