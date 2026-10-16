package dev.deadearth.campaign;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CampaignStoreTest {
    @Test void savesIdentityAndActiveTime() {
        var store = CampaignStore.create();
        store.advance(true);
        store.advance(false);
        var restored = CampaignStore.load(store.save(new CompoundTag(), null), null);
        assertTrue(restored.enabled());
        assertEquals(store.campaign(), restored.campaign());
        assertEquals(1L, restored.campaign().activeTicks());
    }
    @Test void futureSaveIsDisabledAndPreserved() {
        var tag = new CompoundTag();
        tag.putInt("schemaVersion", 99);
        tag.putString("futureField", "keep me");
        var original = tag.copy();
        var store = CampaignStore.load(tag, null);
        store.advance(true);
        assertFalse(store.enabled());
        assertFalse(store.isDirty());
        assertEquals(original, store.save(new CompoundTag(), null));
        assertThrows(IllegalStateException.class, store::campaign);
    }
    @Test void missingFieldsDoNotBecomeNewCampaign() {
        var tag = new CompoundTag();
        tag.putInt("schemaVersion", 1);
        var store = CampaignStore.load(tag, null);
        assertFalse(store.enabled());
        assertEquals(tag, store.save(new CompoundTag(), null));
    }
    @Test void offlineTickDoesNotDirtyStore() {
        var store = CampaignStore.create();
        store.setDirty(false);
        store.advance(false);
        assertFalse(store.isDirty());
        store.advance(true);
        assertTrue(store.isDirty());
    }
}
