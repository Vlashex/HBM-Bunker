package dev.deadearth.observation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ObservationStoreTest {
    @Test void roundTripRetainsObservedFactsAndDeaths() {
        var campaign = UUID.randomUUID();
        var store = ObservationStore.create(campaign);
        var player = UUID.randomUUID();
        store.log().sample(player, "minecraft:overworld", -65, 0, 5, 200);
        store.log().record(210, ObservationLog.Kind.DEATH, player, "minecraft:overworld");
        var loaded = ObservationStore.load(campaign, store.save(new CompoundTag(), null), null);
        assertTrue(loaded.enabled());
        assertEquals(store.log().snapshot(), loaded.log().snapshot());
    }
    @Test void foreignCampaignAndUnknownSchemaPreserveRawData() {
        var campaign = UUID.randomUUID();
        var raw = ObservationStore.create(campaign).save(new CompoundTag(), null);
        assertFalse(ObservationStore.load(UUID.randomUUID(), raw, null).enabled());
        raw.putInt("schemaVersion", 99);
        var loaded = ObservationStore.load(campaign, raw, null);
        assertFalse(loaded.enabled());
        assertFalse(loaded.isDirty());
        assertEquals(raw, loaded.save(new CompoundTag(), null));
    }
    @Test void missingOrWrongTypedListsDoNotBecomeEmptyHistory() {
        var campaign = UUID.randomUUID();
        var raw = ObservationStore.create(campaign).save(new CompoundTag(), null);
        raw.putString("players", "corrupt");
        var loaded = ObservationStore.load(campaign, raw, null);
        assertFalse(loaded.enabled());
        assertEquals(raw, loaded.save(new CompoundTag(), null));
    }
}
