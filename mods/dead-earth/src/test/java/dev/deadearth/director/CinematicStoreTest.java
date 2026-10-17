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
    @Test void retainedSignalsLoadRegardlessOfJsonArrayOrderAfterRestart()throws Exception {
        UUID campaign=UUID.randomUUID();var store=CinematicStore.create(campaign);var catalog=CinematicEngineTest.catalog();
        var port=CinematicEngineTest.admitted(store.engine(),catalog,0,"CALM");port.signals=Set.of("inspect","inspect:flank","burn","burn:rear");
        store.engine().tick(catalog,CinematicEngineTest.context(0,"CALM"),port);
        var tag=store.save(new CompoundTag(),null);var root=com.google.gson.JsonParser.parseString(tag.getString("queue")).getAsJsonObject();
        var op=root.getAsJsonArray("operations").get(0).getAsJsonObject();var original=op.getAsJsonArray("latched");
        var reversed=new com.google.gson.JsonArray();for(int n=original.size()-1;n>=0;n--)reversed.add(original.get(n));op.add("latched",reversed);
        tag.putString("queue",root.toString());var loaded=CinematicStore.load(campaign,tag,null);
        assertTrue(loaded.enabled(),"Valid persistent signals depend on JVM set iteration order");
        assertEquals(port.signals,loaded.engine().operations().getFirst().latched());
        // Duplicated facts are malformed input, even though set comparison is order independent.
        reversed.add(reversed.get(0));tag.putString("queue",root.toString());
        assertFalse(CinematicStore.load(campaign,tag,null).enabled());
    }
    @Test void outstandingWaveSurvivesRestartUntilDeathIsActuallyConfirmed()throws Exception {
        UUID campaign=UUID.randomUUID();var store=CinematicStore.create(campaign);var c=CinematicEngineTest.catalog();
        CinematicEngineTest.admitted(store.engine(),c,0,"CALM");var operation=store.engine().operations().getFirst().id();var entity=UUID.randomUUID();
        store.observeOwned(operation,entity,6000);var loaded=CinematicStore.load(campaign,store.save(new CompoundTag(),null),null);
        assertTrue(loaded.enabled());assertEquals(1,loaded.outstandingOwned(100));assertFalse(loaded.ownedCleared(operation,100));
        loaded.confirmedGone(operation,entity);assertEquals(0,loaded.outstandingOwned(100));assertTrue(loaded.ownedCleared(operation,100));
        var again=CinematicStore.load(campaign,loaded.save(new CompoundTag(),null),null);assertTrue(again.ownedCleared(operation,100));
    }
    @Test void unobservedWaveExpiresWithoutBeingReportedAsKilled()throws Exception {
        UUID campaign=UUID.randomUUID();var store=CinematicStore.create(campaign);var operation=UUID.randomUUID();
        store.observeOwned(operation,UUID.randomUUID(),200);assertEquals(1,store.outstandingOwned(199));
        assertEquals(0,store.outstandingOwned(200));assertFalse(store.ownedCleared(operation,200));
    }
}
