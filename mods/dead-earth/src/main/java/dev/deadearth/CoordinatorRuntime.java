package dev.deadearth;

import dev.deadearth.campaign.CampaignStore;
import dev.deadearth.campaign.SavedDataAccess;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import dev.deadearth.scenario.ScenarioStore;
import dev.deadearth.scenario.ScenarioTypes.*;
import dev.deadearth.scenario.WarningBeaconExecutor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class CoordinatorRuntime {
    private final MinecraftServer server;
    private final UUID epoch = UUID.randomUUID();
    private final CampaignStore campaign;
    private final ScenarioStore scenarios;
    private String fault;

    public CoordinatorRuntime(MinecraftServer server) {
        this.server = server;
        requireServerThread();
        campaign = SavedDataAccess.open(server.overworld(), CampaignStore.NAME,
                new SavedData.Factory<>(CampaignStore::create, CampaignStore::load));
        scenarios = campaign.enabled() ? SavedDataAccess.open(server.overworld(), ScenarioStore.NAME,
                new SavedData.Factory<>(() -> ScenarioStore.create(campaign.campaign().campaignId()),
                        (tag, registries) -> ScenarioStore.load(campaign.campaign().campaignId(), tag, registries))) : null;
    }

    public boolean enabled() { return fault == null && campaign.enabled() && scenarios != null && scenarios.enabled(); }
    public UUID epoch() { return epoch; }
    public CampaignStore campaign() { requireServerThread(); return campaign; }
    public Decision preview(UUID request) { requireReady(); return scenarios.engine().preview(proposal(request), view()); }
    public Receipt submit(UUID request) {
        requireReady();
        var existing = scenarios.engine().findRequest(request);
        if (existing.isPresent()) return existing.get();
        var receipt = scenarios.engine().submit(proposal(request), view());
        if (receipt.state() != State.REJECTED) {
            scenarios.setDirty();
            checkpoint();
        }
        return receipt;
    }
    public Receipt cancel(UUID scenarioId, long revision) {
        requireReady();
        var receipt = scenarios.engine().cancel(scenarioId, revision);
        if (receipt.state() != State.REJECTED) { scenarios.setDirty(); checkpoint(); }
        return receipt;
    }
    public Optional<Receipt> findScenario(UUID id) { requireReady(); return scenarios.engine().findScenario(id); }
    public List<Receipt> receipts() { requireReady(); return scenarios.engine().receipts(); }
    public WorldView view() {
        requireReady();
        return new WorldView(epoch, campaign.campaign().activeTicks(), server.getPlayerCount(),
                server.overworld().hasChunkAt(server.overworld().getSharedSpawnPos()));
    }

    public void tick() {
        requireServerThread();
        if (!enabled()) return;
        try {
            campaign.advance(server.getPlayerCount() > 0);
            var changed = scenarios.engine().tick(view(), id -> {
                scenarios.setDirty();
                checkpoint();
                return new WarningBeaconExecutor(server).execute(id);
            });
            if (!changed.isEmpty()) {
                scenarios.setDirty();
                checkpoint();
                for (var receipt : changed)
                    DeadEarth.LOGGER.info("Dead Earth scenario {} -> {}", receipt.scenarioId(), receipt.state());
            }
        } catch (RuntimeException error) { disable(error); }
    }

    public void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Dead Earth world access outside server thread");
    }

    private void requireReady() {
        requireServerThread();
        if (!enabled()) throw new IllegalStateException("Dead Earth disabled; inspect server log");
    }
    private Proposal proposal(UUID request) {
        long now = campaign.campaign().activeTicks();
        if (now > Long.MAX_VALUE - 1200) throw new IllegalStateException("Campaign clock exhausted");
        return new Proposal(request, epoch, "test_anchor", now + 200, now + 1200);
    }
    private void checkpoint() {
        try {
            SavedDataAccess.checkpoint(server.overworld(), CampaignStore.NAME, campaign);
            SavedDataAccess.checkpoint(server.overworld(), ScenarioStore.NAME, scenarios);
        } catch (RuntimeException error) { disable(error); throw error; }
    }
    private void disable(RuntimeException error) {
        if (fault == null) {
            fault = error.getClass().getSimpleName();
            DeadEarth.LOGGER.error("Dead Earth paused after persistence/execution failure", error);
        }
    }
}
