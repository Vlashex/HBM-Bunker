package dev.deadearth.director;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinematicStoreTest {
    @Test void queueRoundTripKeepsFrozenGraphRequestBudgetAndExpiry()throws Exception {
        UUID campaign=UUID.randomUUID();var store=CinematicStore.create(campaign);var catalog=CinematicEngineTest.catalog();
        CinematicEngineTest.admitted(store.engine(),catalog,0,"CALM");
        var before=store.engine().snapshot();var tag=store.save(new CompoundTag(),null);
        var restored=CinematicStore.load(campaign,tag,null);
        assertTrue(restored.enabled());assertEquals(before,restored.engine().snapshot());
    }
    @Test void unknownOrForeignSaveIsPreservedAndNotMarkedDirty()throws Exception {
        UUID campaign=UUID.randomUUID();var original=CinematicStore.create(campaign).save(new CompoundTag(),null);
        for(int kind=0;kind<4;kind++) {
            var raw=original.copy();UUID expected=campaign;
            if(kind==0)raw.putInt("schema",99);
            if(kind==1)expected=UUID.randomUUID();
            if(kind==2)raw.putString("queue","{\"operations\":[],\"opaque\":1}");
            if(kind==3)raw.putString("queue","{\"operations\":[],\"operations\":[]}");
            var restored=CinematicStore.load(expected,raw,null);assertFalse(restored.enabled());assertFalse(restored.isDirty());
            assertEquals(raw,restored.save(new CompoundTag(),null));
        }
    }
    @Test void interruptedPhysicalEffectBecomesUncertainAndNeverReturnsToQueue()throws Exception {
        UUID campaign=UUID.randomUUID();var store=CinematicStore.create(campaign);var catalog=CinematicEngineTest.catalog();
        var port=CinematicEngineTest.admitted(store.engine(),catalog,0,"CALM");store.engine().tick(catalog,CinematicEngineTest.context(0,"CALM"),port);
        var pending=port.barriers.stream().filter(s->!s.operations().isEmpty()&&s.operations().getFirst().state()==CinematicEngine.State.APPLYING).findFirst().orElseThrow();
        var tag=store.save(new CompoundTag(),null);tag.putString("queue",new com.google.gson.Gson().toJson(pending));
        var restored=CinematicStore.load(campaign,tag,null);assertTrue(restored.enabled());assertTrue(restored.engine().operations().isEmpty());
        assertEquals("uncertain",restored.engine().history().getFirst().outcome());
    }
}
