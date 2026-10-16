package dev.deadearth.climate;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClimateStoreTest {
    @Test void identityOriginAndNoticeMarkersRoundTrip() {
        UUID id=UUID.randomUUID();var store=ClimateStore.create(id,1234);
        assertEquals(1,store.advance(1234,true).size());
        var tag=store.save(new CompoundTag(),null);var restored=ClimateStore.load(id,1234,tag,null);
        assertTrue(restored.enabled());assertEquals(store.cycle().view(1234),restored.cycle().view(1234));
        assertTrue(restored.advance(1234,true).isEmpty());
    }
    @Test void invalidFutureForeignAndWrongTypedDataAreRetained() {
        UUID id=UUID.randomUUID();var original=ClimateStore.create(id,100).save(new CompoundTag(),null);
        var future=original.copy();future.putInt("schemaVersion",99);
        var wrong=original.copy();wrong.putString("phaseTicks","48000");
        var bad=original.copy();bad.putLong("lastWarnedPhase",1);
        for(var tag:new CompoundTag[]{future,wrong,bad}) {
            var store=ClimateStore.load(id,100,tag,null);assertFalse(store.enabled());assertFalse(store.isDirty());
            assertEquals(tag,store.save(new CompoundTag(),null));
        }
        var foreign=ClimateStore.load(UUID.randomUUID(),100,original,null);
        assertFalse(foreign.enabled());assertEquals(original,foreign.save(new CompoundTag(),null));
    }
    @Test void originAfterCampaignTimeDisablesWithoutReset() {
        UUID id=UUID.randomUUID();var tag=ClimateStore.create(id,100).save(new CompoundTag(),null);
        var restored=ClimateStore.load(id,99,tag,null);assertFalse(restored.enabled());assertEquals(tag,restored.save(new CompoundTag(),null));
    }
    @Test void pureReadsDoNotDirtyPersistedStore() {
        UUID id=UUID.randomUUID();var tag=ClimateStore.create(id,0).save(new CompoundTag(),null);
        var store=ClimateStore.load(id,0,tag,null);assertFalse(store.isDirty());
        store.cycle().view(0);store.cycle().forecast(0,4);store.advance(0,false);assertFalse(store.isDirty());
        store.advance(0,true);assertTrue(store.isDirty());
    }
}
