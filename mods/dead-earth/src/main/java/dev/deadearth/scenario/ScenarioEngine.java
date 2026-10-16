package dev.deadearth.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import dev.deadearth.scenario.ScenarioTypes.*;

/** Server-owned, bounded queue. Accepted work survives session epoch changes. */
public final class ScenarioEngine {
    public static final int MAX_RECEIPTS = 1024;
    private final LinkedHashMap<UUID, Receipt> byRequest = new LinkedHashMap<>();

    public Decision preview(Proposal proposal, WorldView view) {
        var reasons = new ArrayList<String>();
        if (!proposal.worldEpoch().equals(view.epoch())) reasons.add("wrong_epoch");
        try { proposal.choice(); } catch (IllegalArgumentException invalid) { reasons.add("invalid_selection"); }
        if (!view.knownTargets().contains(proposal.targetId())) reasons.add("unknown_target");
        if (ScenarioChoice.RADIO.equals(proposal.catalogId()) && !byRequest.containsKey(proposal.requestId())) {
            var radios = byRequest.values().stream().filter(r -> ScenarioChoice.RADIO.equals(r.proposal().catalogId())).toList();
            boolean pending = radios.stream().anyMatch(r -> r.proposal().targetId().equals(proposal.targetId()) && pending(r));
            if (pending) reasons.add("target_pending");
            else if (radios.stream().anyMatch(r -> r.proposal().targetId().equals(proposal.targetId()) && r.state() != State.CANCELLED
                    && view.activeTicks() - r.proposal().earliestTick() < 2400)) reasons.add("target_cooldown");
            if (radios.stream().filter(ScenarioEngine::pending).count() >= 4) reasons.add("radio_capacity");
        }
        if (proposal.earliestTick() < 0 || proposal.expiresTick() <= proposal.earliestTick()) reasons.add("invalid_window");
        if (proposal.expiresTick() <= view.activeTicks()) reasons.add("expired");
        if (!byRequest.containsKey(proposal.requestId()) && byRequest.size() >= MAX_RECEIPTS) reasons.add("capacity");
        return new Decision(reasons.isEmpty(), reasons);
    }

    public Receipt submit(Proposal proposal, WorldView view) {
        var existing = byRequest.get(proposal.requestId());
        if (existing != null) {
            if (!proposal.worldEpoch().equals(view.epoch())) return rejected(proposal, "wrong_epoch");
            return existing.proposal().equals(proposal) ? existing : rejected(proposal, "conflicting_request");
        }
        var decision = preview(proposal, view);
        if (!decision.accepted()) return rejected(proposal, String.join(",", decision.reasons()));
        var receipt = new Receipt(proposal, UUID.randomUUID(), State.SCHEDULED, 0, "");
        byRequest.put(proposal.requestId(), receipt);
        return receipt;
    }

    public List<Receipt> tick(WorldView view, StepExecutor executor) {
        var changed = new ArrayList<Receipt>();
        for (var receipt : List.copyOf(byRequest.values())) {
            if (receipt.state() != State.SCHEDULED) continue;
            if (view.activeTicks() >= receipt.proposal().expiresTick()) {
                changed.add(transition(receipt, State.EXPIRED, "window_expired"));
                continue;
            }
            if (view.activeTicks() < receipt.proposal().earliestTick()
                    || view.onlinePlayers() == 0 || !view.knownTargets().contains(receipt.proposal().targetId())
                    || !view.loadedTargets().contains(receipt.proposal().targetId())) continue;
            var running = transition(receipt, State.RUNNING, "");
            try {
                var result = executor.execute(running.scenarioId());
                changed.add(transition(running, result == StepResult.COMPLETED ? State.COMPLETED : State.UNCERTAIN,
                        result == StepResult.COMPLETED ? "" : "unconfirmed_effect"));
            } catch (RuntimeException error) {
                changed.add(transition(running, State.UNCERTAIN, "executor_failed:" + error.getClass().getSimpleName()));
            }
            break;
        }
        return List.copyOf(changed);
    }

    public Receipt cancel(UUID scenarioId, long expectedRevision) {
        var existing = findScenario(scenarioId).orElseThrow(() -> new IllegalArgumentException("Unknown scenario"));
        if (existing.revision() != expectedRevision) return rejected(existing.proposal(), "stale_revision");
        if (existing.state() != State.SCHEDULED) return rejected(existing.proposal(), "not_pending");
        return transition(existing, State.CANCELLED, "operator_cancelled");
    }

    public Optional<Receipt> findRequest(UUID id) { return Optional.ofNullable(byRequest.get(id)); }
    public Optional<Receipt> findScenario(UUID id) {
        return byRequest.values().stream().filter(r -> r.scenarioId().equals(id)).findFirst();
    }
    public List<Receipt> receipts() { return List.copyOf(byRequest.values()); }

    public static ScenarioEngine restore(List<Receipt> receipts) {
        if (receipts.size() > MAX_RECEIPTS) throw new IllegalArgumentException("Oversized scenario save");
        var engine = new ScenarioEngine();
        var ids = new java.util.HashSet<UUID>();
        var pendingTargets = new java.util.HashSet<String>();
        int pendingRadios = 0;
        for (var receipt : receipts) {
            receipt.proposal().choice();
            if (ScenarioChoice.RADIO.equals(receipt.proposal().catalogId()) && pending(receipt)) {
                if (++pendingRadios > 4 || !pendingTargets.add(receipt.proposal().targetId()))
                    throw new IllegalArgumentException("Invalid saved radio pending set");
            }
            if (receipt.state() == State.REJECTED || receipt.proposal().earliestTick() < 0
                    || receipt.proposal().expiresTick() <= receipt.proposal().earliestTick()
                                        || !ids.add(receipt.scenarioId())
                    || engine.byRequest.putIfAbsent(receipt.proposal().requestId(), receipt) != null)
                throw new IllegalArgumentException("Invalid or duplicate saved scenario");
            if (receipt.state() == State.RUNNING)
                engine.transition(receipt, State.UNCERTAIN, "interrupted_execution");
        }
        return engine;
    }

    private static boolean pending(Receipt receipt) { return receipt.state() == State.SCHEDULED || receipt.state() == State.RUNNING; }

    private Receipt transition(Receipt receipt, State state, String reason) {
        var updated = new Receipt(receipt.proposal(), receipt.scenarioId(), state,
                Math.addExact(receipt.revision(), 1), reason);
        byRequest.put(receipt.proposal().requestId(), updated);
        return updated;
    }
    private static Receipt rejected(Proposal proposal, String reason) {
        return new Receipt(proposal, null, State.REJECTED, 0, reason);
    }
}
