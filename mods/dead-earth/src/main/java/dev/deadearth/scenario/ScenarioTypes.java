package dev.deadearth.scenario;

import java.util.List;
import java.util.UUID;

public final class ScenarioTypes {
    private ScenarioTypes() {}
    public enum State { SCHEDULED, RUNNING, COMPLETED, CANCELLED, EXPIRED, UNCERTAIN, REJECTED }
    public enum StepResult { COMPLETED, UNCERTAIN }
    public record Proposal(UUID requestId, UUID worldEpoch, String targetId, long earliestTick, long expiresTick,
            String catalogId, String variant, int delayTicks) {
        public Proposal(UUID requestId, UUID worldEpoch, String targetId, long earliestTick, long expiresTick) {
            this(requestId,worldEpoch,targetId,earliestTick,expiresTick,ScenarioChoice.WARNING,"standard",200);
        }
        public ScenarioChoice choice() { return new ScenarioChoice(catalogId,targetId,variant,delayTicks); }
        public Proposal {
            if (requestId == null || worldEpoch == null || targetId == null)
                throw new IllegalArgumentException("Missing proposal fields");
        }
    }
    public record WorldView(UUID epoch, long activeTicks, int onlinePlayers,
            java.util.Set<String> knownTargets, java.util.Set<String> loadedTargets) {
        public boolean anchorLoaded() { return loadedTargets.contains("test_anchor"); }
        public WorldView(UUID epoch, long activeTicks, int onlinePlayers, boolean anchorLoaded) {
            this(epoch,activeTicks,onlinePlayers,java.util.Set.of("test_anchor"),anchorLoaded ? java.util.Set.of("test_anchor") : java.util.Set.of());
        }
        public WorldView {
            if (epoch == null || activeTicks < 0 || onlinePlayers < 0)
                throw new IllegalArgumentException("Invalid world view");
            knownTargets = java.util.Set.copyOf(knownTargets);
            loadedTargets = java.util.Set.copyOf(loadedTargets);
            if (!knownTargets.containsAll(loadedTargets)) throw new IllegalArgumentException("Loaded target is unknown");
        }
    }
    public record Decision(boolean accepted, List<String> reasons) {
        public Decision { reasons = List.copyOf(reasons); }
    }
    public record Receipt(Proposal proposal, UUID scenarioId, State state, long revision, String reason) {
        public Receipt {
            if (proposal == null || state == null || revision < 0 || reason == null
                    || (scenarioId == null && state != State.REJECTED))
                throw new IllegalArgumentException("Invalid receipt");
        }
    }
    @FunctionalInterface public interface StepExecutor { StepResult execute(UUID scenarioId); }
}
