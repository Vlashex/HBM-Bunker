package dev.deadearth.director;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinematicEngineTest {
    static final UUID EPOCH=UUID.randomUUID();
    static final String ZONE=UUID.randomUUID().toString();
    static String source()throws Exception {
        try(var in=CinematicEngineTest.class.getResourceAsStream("/data/dead_earth/director/catalog.json")) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static CinematicCatalog catalog()throws Exception{return CinematicCatalog.parse(source());}
    static CinematicEngine.Context context(long now,String season){return new CinematicEngine.Context(now,2,season,1.2,
        List.of(new CinematicEngine.Zone(ZONE,100,1,2,20,true,false,2,3,0)));}
    static class Port implements CinematicEngine.World {
        CinematicEngine engine;boolean ready=true;int living=0,emissions=0,spawned=0;Set<String> signals=Set.of();
        List<CinematicEngine.Snapshot> barriers=new ArrayList<>();List<String> effects=new ArrayList<>();
        public boolean ready(CinematicEngine.Operation op){return ready;}
        public Set<String> signals(CinematicEngine.Operation op){return signals;}
        public int livingOwned(){return living;}
        public void emit(CinematicEngine.Operation op,CinematicCatalog.Action a,int count,CinematicCatalog c){
            assertEquals(CinematicEngine.State.APPLYING,engine.operations().getFirst().state());
            assertEquals(CinematicEngine.State.APPLYING,barriers.getLast().operations().getFirst().state());
            emissions++;spawned+=count;effects.add(a.type()+"/"+a.key());
        }
        public void checkpoint(){barriers.add(engine.snapshot());}
    }
    static Port admitted(CinematicEngine engine,CinematicCatalog cat,long now,String season) {
        var goal=engine.brief(EPOCH,cat,context(now,season),true);assertNotNull(goal);
        var choice=goal.options().getFirst();var result=engine.admit(UUID.randomUUID(),goal.id(),choice.eventId(),choice.zoneId(),EPOCH,cat,context(now,season),true);
        assertTrue(result.accepted(),result.code());var port=new Port();port.engine=engine;return port;
    }
    @Test void fullCatalogHasDistinctInteractiveGraphsAndLargePhysicalLibraries()throws Exception {
        var c=catalog();assertEquals(15,c.events().size());assertEquals(103,c.sounds().size());assertEquals(48,c.traces().size());
        assertEquals(15,c.events().values().stream().map(CinematicCatalog.Event::tactic).distinct().count());
        for(var e:c.events().values()) {
            assertTrue(e.nodes().values().stream().mapToInt(n->n.branches().size()).sum()>=3,e.id());
            assertTrue(e.nodes().values().stream().flatMap(n->n.actions().stream()).anyMatch(a->a.type().equals("trace")),e.id());
            assertEquals(e,CinematicCatalog.parse(CinematicEngine.freeze(c,e.id())).events().get(e.id()));
        }
    }
    @Test void strictJsonAndTypedGraphsRejectCommandsDuplicatesCyclesAndDeadEnds()throws Exception {
        assertThrows(IllegalArgumentException.class,()->CinematicCatalog.strict("{\"x\":1,\"x\":2}"));
        assertThrows(IllegalArgumentException.class,()->CinematicCatalog.strict("{x:1}"));
        var raw=JsonParser.parseString(source()).getAsJsonObject();raw.addProperty("run_command","op player");
        assertThrows(IllegalArgumentException.class,()->CinematicCatalog.parse(raw.toString()));
        raw.remove("run_command");var n=raw.getAsJsonArray("events").get(0).getAsJsonObject().getAsJsonArray("nodes").get(0).getAsJsonObject();
        n.addProperty("timeout",n.get("id").getAsString());
        assertThrows(IllegalArgumentException.class,()->CinematicCatalog.parse(raw.toString()));
    }
    @Test void noPlayersUnknownScoresAndStaleZonesCannotBecomeAttackTargets()throws Exception {
        var c=catalog();var engine=new CinematicEngine();
        assertNull(engine.brief(EPOCH,c,new CinematicEngine.Context(0,0,"CALM",1,List.of()),true));
        for(var z:List.of(new CinematicEngine.Zone(ZONE,1e8,0,0,0,false,false,2,3,0),
                new CinematicEngine.Zone(ZONE,1e8,0,0,2401,true,false,2,3,0),
                new CinematicEngine.Zone(ZONE,Double.NaN,0,0,0,true,false,2,3,0)))
            assertTrue(engine.options(c,new CinematicEngine.Context(0,2,"CALM",1,List.of(z))).isEmpty());
    }
    @Test void shortlistIsVariedAcrossVirtualCyclesAndRespectsSeasons()throws Exception {
        var c=catalog();var seen=new HashSet<String>();
        for(int cycle=0;cycle<200;cycle++)for(var season:List.of("CALM","HEAT","ASH_WINTER")) {
            var options=new CinematicEngine().options(c,context(cycle*18000L,season));assertTrue(options.size()<=8);
            for(var o:options){seen.add(o.eventId());assertTrue(c.events().get(o.eventId()).seasons().contains(season));}
        }
        assertEquals(c.events().keySet(),seen);
    }
    @Test void actionsNeedDurableBarrierAndCrashNeverReplaysAPhysicalEffect()throws Exception {
        var c=catalog();var engine=new CinematicEngine();var port=admitted(engine,c,0,"CALM");
        engine.tick(c,context(0,"CALM"),port);assertEquals(1,port.emissions);
        var applying=port.barriers.stream().filter(s->!s.operations().isEmpty()&&s.operations().getFirst().state()==CinematicEngine.State.APPLYING).findFirst().orElseThrow();
        var restored=CinematicEngine.restore(applying);assertTrue(restored.operations().isEmpty());assertEquals("uncertain",restored.history().getFirst().outcome());
        var after=CinematicEngine.restore(engine.snapshot());assertEquals(1,after.operations().getFirst().cursor());
    }
    @Test void offlineAndUnloadedPauseWithoutForceLoadAndExpireWithVirtualClock()throws Exception {
        var c=catalog();var engine=new CinematicEngine();var port=admitted(engine,c,0,"CALM");port.ready=false;
        engine.tick(c,context(100,"CALM"),port);assertEquals(0,port.emissions);
        engine.tick(c,new CinematicEngine.Context(4801,0,"CALM",1,List.of()),port);assertEquals(1,engine.operations().size());
        engine.tick(c,context(4801,"CALM"),port);assertTrue(engine.operations().isEmpty());assertEquals("expired",engine.history().getFirst().outcome());
    }
    @Test void everyEventTimeoutAndEveryReachableBranchTerminatesWithinFiniteBudget()throws Exception {
        var full=catalog();
        for(var e:full.events().values()) {
            var c=CinematicCatalog.parse(CinematicEngine.freeze(full,e.id()));
            for(String signal:List.of("","near","near:rear","near:flank","inspect","inspect:rear","inspect:flank","break","burn","water","mine","repair","clear","far")) {
                var engine=new CinematicEngine();String season=e.seasons().iterator().next();var port=admitted(engine,c,0,season);
                port.signals=signal.isEmpty()?Set.of():Set.of(signal);
                for(long t=0;t<5000&&!engine.operations().isEmpty();t+=20)engine.tick(c,context(t,season),port);
                assertTrue(engine.operations().isEmpty(),e.id()+"/"+signal);
                assertTrue(port.spawned<=10);assertTrue(port.spawned<=engine.history().getFirst().spent());
                assertNotEquals("uncertain",engine.history().getFirst().outcome());
            }
        }
    }
    @Test void defenseAndLossesDecreaseFiniteAllocation()throws Exception {
        var c=catalog();var engine=new CinematicEngine();
        var healthy=engine.options(c,context(0,"CALM")).getFirst().budget();
        var hurt=engine.options(c,new CinematicEngine.Context(0,2,"CALM",1.2,List.of(new CinematicEngine.Zone(ZONE,100,16,0,0,true,false,2,3,3))));
        assertTrue(hurt.stream().allMatch(o->o.budget()<healthy));
    }
    @Test void requestReplayIsIdempotentAndConflictsDoNotCreateAnotherOperation()throws Exception {
        var c=catalog();var engine=new CinematicEngine();var g=engine.brief(EPOCH,c,context(0,"CALM"),true);var o=g.options().getFirst();var id=UUID.randomUUID();
        var first=engine.admit(id,g.id(),o.eventId(),o.zoneId(),EPOCH,c,context(0,"CALM"),true);
        assertEquals(first.operationId(),engine.admit(id,g.id(),o.eventId(),o.zoneId(),EPOCH,c,context(0,"CALM"),true).operationId());
        assertFalse(engine.admit(id,g.id(),"different",o.zoneId(),EPOCH,c,context(0,"CALM"),true).accepted());
        assertEquals(1,engine.operations().size());
    }
    @Test void oneShotPlayerActionDuringCueSequenceSurvivesUntilBranchEvaluation()throws Exception {
        var c=CinematicCatalog.parse(CinematicEngine.freeze(catalog(),"spore_lure"));
        var engine=new CinematicEngine();var port=admitted(engine,c,0,"CALM");port.signals=Set.of("burn");
        engine.tick(c,context(0,"CALM"),port);port.signals=Set.of();
        for(int t=20;t<=80;t+=20)engine.tick(c,context(t,"CALM"),port);
        assertEquals("smoke",engine.operations().getFirst().node());
    }
    @Test void reactionWindowStartsAfterTheLastCueWasDelivered()throws Exception {
        var c=CinematicCatalog.parse(CinematicEngine.freeze(catalog(),"spore_lure"));
        var engine=new CinematicEngine();var port=admitted(engine,c,0,"CALM");
        engine.tick(c,context(0,"CALM"),port);engine.tick(c,context(200,"CALM"),port);
        engine.tick(c,context(300,"CALM"),port);
        assertEquals("pollen",engine.operations().getFirst().node());
        engine.tick(c,context(460,"CALM"),port);assertEquals("retreat",engine.operations().getFirst().node());
    }
    @Test void failedCheckpointCannotBeBypassedByReloadingTheCatalog()throws Exception {
        var c=catalog();var engine=new CinematicEngine();var initial=admitted(engine,c,0,"CALM");
        var failing=new Port(){@Override public void checkpoint(){super.checkpoint();if(engine.operations().stream().anyMatch(o->o.state()==CinematicEngine.State.APPLYING))throw new IllegalStateException("disk failed");}};
        failing.engine=engine;
        assertThrows(IllegalStateException.class,()->engine.tick(c,context(0,"CALM"),failing));
        engine.tick(c,context(20,"CALM"),initial);
        assertEquals(0,initial.emissions);assertTrue(engine.operations().isEmpty());
        assertEquals("uncertain",engine.history().getFirst().outcome());
    }

    @Test void admissionRecalculatesBudgetWhenPlayersWereHurtWhileModelWasThinking()throws Exception {
        var c=CinematicCatalog.parse(CinematicEngine.freeze(catalog(),"spore_lure"));var engine=new CinematicEngine();
        var goal=engine.brief(EPOCH,c,context(0,"CALM"),true);var choice=goal.options().getFirst();
        var changed=new CinematicEngine.Context(100,2,"CALM",1.2,List.of(new CinematicEngine.Zone(ZONE,100,16,0,0,true,false,2,3,3)));
        int current=engine.options(c,changed).getFirst().budget();assertTrue(current<choice.budget());
        assertTrue(engine.admit(UUID.randomUUID(),goal.id(),choice.eventId(),choice.zoneId(),EPOCH,c,changed,true).accepted());
        assertEquals(current,engine.operations().getFirst().budget());
    }

    @Test void emptyInitialScanRetriesQuicklyRatherThanConsumingAnEntireDecisionCycle()throws Exception {
        var c=catalog();var engine=new CinematicEngine();
        assertNull(engine.brief(EPOCH,c,new CinematicEngine.Context(0,2,"CALM",1,List.of()),true));
        assertNotNull(engine.brief(EPOCH,c,context(200,"CALM"),true));
    }
    @Test void remainingWeightsCanBeChangedInDataWithoutRecompiling()throws Exception {
        var raw=JsonParser.parseString(source()).getAsJsonObject();var rules=raw.getAsJsonObject("rules");
        var coefficients=rules.has("coefficients")?rules.getAsJsonObject("coefficients"):new JsonObject();
        coefficients.addProperty("defenseBudgetWeight",5);rules.add("coefficients",coefficients);
        var configured=assertDoesNotThrow(()->CinematicCatalog.parse(raw.toString()));
        var ctx=new CinematicEngine.Context(0,2,"CALM",1,List.of(new CinematicEngine.Zone(ZONE,100,8,0,0,true,false,2,3,0)));
        assertTrue(new CinematicEngine().options(configured,ctx).stream().allMatch(o->o.budget()==1));
    }

}
