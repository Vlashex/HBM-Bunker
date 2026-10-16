package dev.deadearth;

import dev.deadearth.campaign.CampaignStore;
import dev.deadearth.campaign.SavedDataAccess;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import dev.deadearth.scenario.ScenarioStore;
import dev.deadearth.scenario.ScenarioTypes.*;
import dev.deadearth.scenario.WarningBeaconExecutor;
import dev.deadearth.observation.ObservationLog;
import dev.deadearth.observation.ObservationStore;
import net.minecraft.server.level.ServerPlayer;
import dev.deadearth.site.RelayPlacement;
import dev.deadearth.site.SiteRegistry;
import dev.deadearth.site.SiteStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class CoordinatorRuntime {
    private final MinecraftServer server;
    private final UUID epoch = UUID.randomUUID();
    private final CampaignStore campaign;
    private final ScenarioStore scenarios;
    private final ObservationStore observations;
    private final SiteStore sites;
    private String fault;

    public CoordinatorRuntime(MinecraftServer server) {
        this.server = server;
        requireServerThread();
        campaign = SavedDataAccess.open(server.overworld(), CampaignStore.NAME,
                new SavedData.Factory<>(CampaignStore::create, CampaignStore::load));
        scenarios = campaign.enabled() ? SavedDataAccess.open(server.overworld(), ScenarioStore.NAME,
                new SavedData.Factory<>(() -> ScenarioStore.create(campaign.campaign().campaignId()),
                        (tag, registries) -> ScenarioStore.load(campaign.campaign().campaignId(), tag, registries))) : null;
        observations = campaign.enabled() ? SavedDataAccess.open(server.overworld(), ObservationStore.NAME,
                new SavedData.Factory<>(() -> ObservationStore.create(campaign.campaign().campaignId()),
                        (tag, registries) -> ObservationStore.load(campaign.campaign().campaignId(), tag, registries))) : null;
        sites = campaign.enabled() ? SavedDataAccess.open(server.overworld(), SiteStore.NAME,
                new SavedData.Factory<>(() -> SiteStore.create(campaign.campaign().campaignId()),
                        (tag, registries) -> SiteStore.load(campaign.campaign().campaignId(), tag, registries))) : null;
        if (enabled()) reconcileSites();
    }

    public boolean enabled() { return fault == null && campaign.enabled() && scenarios != null && scenarios.enabled()
            && observations != null && observations.enabled() && sites != null && sites.enabled(); }
    public UUID epoch() { return epoch; }
    public CampaignStore campaign() { requireServerThread(); return campaign; }
    public record Snapshot(UUID campaignId, UUID worldEpoch, long activeTicks, int onlinePlayers,
            ObservationLog.Snapshot observations, List<SiteRegistry.Site> discoveredSites) {}
    public Snapshot snapshot() {
        requireReady();
        return new Snapshot(campaign.campaign().campaignId(), epoch, campaign.campaign().activeTicks(),
                server.getPlayerCount(), observations.log().snapshot(), sites.registry().sites().stream().filter(s -> s.discoverer() != null).toList());
    }
    public List<SiteRegistry.Site> sites() { requireReady(); reconcileSites(); return sites.registry().sites(); }
    public RelayPlacement.Preview previewRelay(BlockPos origin) { requireReady(); return RelayPlacement.preview(server.overworld(), origin); }
    public SiteRegistry.Site placeRelay(UUID request, BlockPos origin) {
        requireReady();
        reconcileSites();
        var old = sites.registry().byRequest(request);
        if (old.isPresent()) {
            var site = old.get();
            if (site.x() != origin.getX() || site.y() != origin.getY() || site.z() != origin.getZ())
                throw new IllegalArgumentException("Placement request conflict");
            return site;
        }
        var preview = previewRelay(origin);
        if (!preview.allowed()) throw new IllegalArgumentException("Relay placement refused: " + preview.reason());
        var reserved = sites.registry().reserve(request, origin.getX(), origin.getY(), origin.getZ(), campaign.campaign().activeTicks());
        sites.setDirty(); checkpoint();
        try {
            RelayPlacement.place(server.overworld(), origin);
            var ready = sites.registry().complete(reserved.id()); sites.setDirty(); checkpoint(); return ready;
        } catch (RuntimeException error) {
            sites.registry().uncertain(reserved.id()); sites.setDirty();
            try { checkpoint(); } catch (RuntimeException failure) { error.addSuppressed(failure); }
            throw error;
        }
    }
    public void recordDeath(ServerPlayer player) {
        requireServerThread();
        if (!enabled()) return;
        try {
            var pos = player.blockPosition();
            observations.log().record(campaign.campaign().activeTicks(), ObservationLog.Kind.DEATH, player.getUUID(),
                    player.level().dimension().location() + "/" + Math.floorDiv(pos.getX(), 64) + "/" + Math.floorDiv(pos.getZ(), 64));
            observations.setDirty();
        } catch (RuntimeException error) { disable(error); }
    }
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
            if (server.getPlayerCount() > 0 && campaign.campaign().activeTicks() % 200 == 0) {
                reconcileSites();
                for (var player : server.getPlayerList().getPlayers()) {
                    var pos = player.blockPosition();
                    if (observations.log().sample(player.getUUID(), player.level().dimension().location().toString(),
                            pos.getX(), pos.getZ(), player.getHealth(), campaign.campaign().activeTicks())) observations.setDirty();
                    if (player.level() == server.overworld() && player.isAlive() && !player.isSpectator())
                        for (var site : sites.registry().discover(player.getUUID(), pos.getX(), pos.getY(), pos.getZ(), campaign.campaign().activeTicks())) {
                            observations.log().record(campaign.campaign().activeTicks(), ObservationLog.Kind.SITE_DISCOVERED,
                                    player.getUUID(), site.id().toString());
                            observations.setDirty(); sites.setDirty();
                            player.sendSystemMessage(Component.literal("[DEAD EARTH] Обнаружена заброшенная станция связи. Запись экспедиции сохранена."));
                        }
                }
            }
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
    private void reconcileSites() {
        for (var site : sites.registry().sites()) {
            if (site.state() != SiteRegistry.State.VERIFYING && site.state() != SiteRegistry.State.READY) continue;
            var intact = RelayPlacement.intact(server.overworld(), new BlockPos(site.x(), site.y(), site.z()));
            if (intact.isPresent() && (site.state() == SiteRegistry.State.VERIFYING || !intact.get())) {
                sites.registry().verify(site.id(), intact.get()); sites.setDirty();
            }
        }
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
            SavedDataAccess.checkpoint(server.overworld(), ObservationStore.NAME, observations);
            SavedDataAccess.checkpoint(server.overworld(), SiteStore.NAME, sites);
        } catch (RuntimeException error) { disable(error); throw error; }
    }
    private void disable(RuntimeException error) {
        if (fault == null) {
            fault = error.getClass().getSimpleName();
            DeadEarth.LOGGER.error("Dead Earth paused after persistence/execution failure", error);
        }
    }
}
