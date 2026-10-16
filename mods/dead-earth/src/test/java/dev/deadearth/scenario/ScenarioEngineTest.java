package dev.deadearth.scenario;

import dev.deadearth.scenario.ScenarioTypes.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioEngineTest {
    private static final UUID EPOCH = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private Proposal proposal() { return new Proposal(REQUEST, EPOCH, "test_anchor", 200, 1200); }
    private WorldView view(long ticks, int players, boolean loaded) { return new WorldView(EPOCH, ticks, players, loaded); }

    @Test void duplicateRequestExecutesOnce() {
        var engine = new ScenarioEngine();
        var first = engine.submit(proposal(), view(0, 1, true));
        assertEquals(first, engine.submit(proposal(), view(100, 1, true)));
        var signals = new ArrayList<UUID>();
        engine.tick(view(200, 1, true), id -> { signals.add(id); return StepResult.COMPLETED; });
        engine.tick(view(201, 1, true), id -> { signals.add(id); return StepResult.COMPLETED; });
        assertEquals(List.of(first.scenarioId()), signals);
        assertEquals(State.COMPLETED, engine.findRequest(REQUEST).orElseThrow().state());
    }
    @Test void conflictingDuplicateRejected() {
        var engine = new ScenarioEngine();
        var first = engine.submit(proposal(), view(0, 1, true));
        var conflict = new Proposal(REQUEST, EPOCH, "another_anchor", 200, 1200);
        assertEquals(State.REJECTED, engine.submit(conflict, view(0, 1, true)).state());
        assertEquals(first, engine.findRequest(REQUEST).orElseThrow());
    }
    @Test void wrongEpochRejected() {
        var engine = new ScenarioEngine();
        var wrong = new WorldView(UUID.randomUUID(), 0, 1, true);
        assertFalse(engine.preview(proposal(), wrong).accepted());
        assertEquals(State.REJECTED, engine.submit(proposal(), wrong).state());
        assertTrue(engine.receipts().isEmpty());
    }
    @Test void offlineOrUnloadedDefersUntilExpiry() {
        var engine = new ScenarioEngine();
        engine.submit(proposal(), view(0, 1, true));
        engine.tick(view(200, 0, true), id -> { fail("Executed while offline"); return null; });
        engine.tick(view(201, 1, false), id -> { fail("Executed in unloaded target"); return null; });
        assertEquals(State.SCHEDULED, engine.findRequest(REQUEST).orElseThrow().state());
        engine.tick(view(1200, 1, true), id -> { fail("Executed after expiry"); return null; });
        assertEquals(State.EXPIRED, engine.findRequest(REQUEST).orElseThrow().state());
    }
    @Test void cancelledStepDoesNotRun() {
        var engine = new ScenarioEngine();
        var first = engine.submit(proposal(), view(0, 1, true));
        assertEquals(State.CANCELLED, engine.cancel(first.scenarioId(), first.revision()).state());
        engine.tick(view(200, 1, true), id -> { fail("Executed after cancellation"); return null; });
    }
    @Test void staleCancelDoesNotChangeState() {
        var engine = new ScenarioEngine();
        var first = engine.submit(proposal(), view(0, 1, true));
        assertEquals(State.REJECTED, engine.cancel(first.scenarioId(), first.revision() + 1).state());
        assertEquals(State.SCHEDULED, engine.findRequest(REQUEST).orElseThrow().state());
    }
    @Test void restoredRunningBecomesUncertainWithoutRetry() {
        var p = proposal();
        var running = new Receipt(p, UUID.randomUUID(), State.RUNNING, 1, "");
        var engine = ScenarioEngine.restore(List.of(running));
        engine.tick(view(200, 1, true), id -> { fail("Retried ambiguous effect"); return null; });
        assertEquals(State.UNCERTAIN, engine.findRequest(REQUEST).orElseThrow().state());
    }
    @Test void scheduledRequestSurvivesNewSessionEpoch() {
        var original = new ScenarioEngine();
        var receipt = original.submit(proposal(), view(0, 1, true));
        var restored = ScenarioEngine.restore(original.receipts());
        var signals = new ArrayList<UUID>();
        restored.tick(new WorldView(UUID.randomUUID(), 200, 1, true), id -> { signals.add(id); return StepResult.COMPLETED; });
        assertEquals(List.of(receipt.scenarioId()), signals);
    }
    @Test void previewDoesNotCreateOrReserveScenario() {
        var engine = new ScenarioEngine();
        assertTrue(engine.preview(proposal(), view(0, 1, true)).accepted());
        assertTrue(engine.receipts().isEmpty());
    }
    @Test void invalidWindowAndUnknownTargetAreRejected() {
        var engine = new ScenarioEngine();
        assertFalse(engine.preview(new Proposal(REQUEST, EPOCH, "test_anchor", 100, 100), view(0,1,true)).accepted());
        assertFalse(engine.preview(new Proposal(REQUEST, EPOCH, "anything", 200, 1200), view(0,1,true)).accepted());
    }
    @Test void executorFailureBecomesUncertain() {
        var engine = new ScenarioEngine();
        engine.submit(proposal(), view(0, 1, true));
        engine.tick(view(200, 1, true), id -> { throw new IllegalStateException("lost acknowledgement"); });
        assertEquals(State.UNCERTAIN, engine.findRequest(REQUEST).orElseThrow().state());
        engine.tick(view(201, 1, true), id -> { fail("Retried failed effect"); return null; });
    }
    @Test void executesAtMostOneReadyStepPerTick() {
        var engine = new ScenarioEngine();
        engine.submit(proposal(), view(0, 1, true));
        engine.submit(new Proposal(UUID.randomUUID(), EPOCH, "test_anchor", 200, 1200), view(0,1,true));
        var signals = new ArrayList<UUID>();
        engine.tick(view(200,1,true), id -> { signals.add(id); return StepResult.COMPLETED; });
        assertEquals(1, signals.size());
        engine.tick(view(201,1,true), id -> { signals.add(id); return StepResult.COMPLETED; });
        assertEquals(2, signals.size());
    }
}
