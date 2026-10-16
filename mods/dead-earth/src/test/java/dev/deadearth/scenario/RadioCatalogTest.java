package dev.deadearth.scenario;

import dev.deadearth.scenario.ScenarioTypes.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioCatalogTest {
    private final UUID epoch = UUID.randomUUID();
    private final String target = "relay:" + UUID.randomUUID();
    private ScenarioChoice choice(String target) { return new ScenarioChoice("relay_broadcast_v1", target, "survey", 200); }
    private Proposal proposal(UUID request, String target) { return new Proposal(request, epoch, target, 200, 1200, "relay_broadcast_v1", "survey", 200); }
    private WorldView view(long tick, Set<String> known, Set<String> loaded) { return new WorldView(epoch, tick, 1, known, loaded); }

    @Test void catalogRejectsUnboundedOrMixedSelections() {
        assertEquals("standard", ScenarioChoice.warning().variant());
        assertEquals(1200, new ScenarioChoice("relay_broadcast_v1", target, "rally", 1200).delayTicks());
        assertThrows(IllegalArgumentException.class, () -> new ScenarioChoice("relay_broadcast_v1", target, "free text", 200));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioChoice("relay_broadcast_v1", "test_anchor", "survey", 200));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioChoice("warning_beacon_v1", target, "standard", 200));
        for (int delay : new int[]{199,201,1201,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new ScenarioChoice("relay_broadcast_v1", target, "survey", delay));
    }
    @Test void onlyKnownTargetRunsAndItsOwnFootprintMustBeLoaded() {
        var engine = new ScenarioEngine(); var request = UUID.randomUUID();
        assertFalse(engine.preview(proposal(request,target),view(0,Set.of("test_anchor"),Set.of("test_anchor"))).accepted());
        var receipt = engine.submit(proposal(request,target),view(0,Set.of(target),Set.of()));
        assertEquals(State.SCHEDULED,receipt.state());
        engine.tick(view(200,Set.of(target,"test_anchor"),Set.of("test_anchor")),id -> { fail("Used spawn readiness for relay");return null; });
        engine.tick(view(201,Set.of(),Set.of()),id -> { fail("Ran unknown target");return null; });
        engine.tick(view(202,Set.of(target),Set.of(target)),id -> StepResult.COMPLETED);
        assertEquals(State.COMPLETED,engine.findRequest(request).orElseThrow().state());
    }
    @Test void pendingAndGlobalLimitsAreReleasedByCancellation() {
        var engine = new ScenarioEngine(); var targets = new LinkedHashSet<String>();
        for(int i=0;i<5;i++)targets.add("relay:"+UUID.randomUUID());
        var view=view(0,targets,targets); var list=List.copyOf(targets); var first=engine.submit(proposal(UUID.randomUUID(),list.get(0)),view);
        assertEquals("target_pending",engine.submit(proposal(UUID.randomUUID(),list.get(0)),view).reason());
        for(int i=1;i<4;i++)assertEquals(State.SCHEDULED,engine.submit(proposal(UUID.randomUUID(),list.get(i)),view).state());
        assertEquals("radio_capacity",engine.submit(proposal(UUID.randomUUID(),list.get(4)),view).reason());
        engine.cancel(first.scenarioId(),first.revision());
        assertEquals(State.SCHEDULED,engine.submit(proposal(UUID.randomUUID(),list.get(4)),view).state());
    }
    @Test void cooldownSurvivesCompletedReceiptRestore() {
        var engine = new ScenarioEngine(); var view=view(0,Set.of(target),Set.of(target));
        engine.submit(proposal(UUID.randomUUID(),target),view);
        engine.tick(view(200,Set.of(target),Set.of(target)),id -> StepResult.COMPLETED);
        var restored=ScenarioEngine.restore(engine.receipts());
        assertTrue(restored.preview(proposal(UUID.randomUUID(),target),view(2599,Set.of(target),Set.of(target))).reasons().contains("target_cooldown"));
        var later=new Proposal(UUID.randomUUID(),epoch,target,2800,3800,"relay_broadcast_v1","survey",200);
        assertTrue(restored.preview(later,view(2600,Set.of(target),Set.of(target))).accepted());
    }
}
