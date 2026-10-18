package dev.deadearth;
import static dev.deadearth.config.DeadEarthConfig.*;

import dev.deadearth.campaign.CampaignStore;
import dev.deadearth.climate.ClimateStore;
import dev.deadearth.climate.ClimateCycle;
import dev.deadearth.climate.ClimateMessages;
import dev.deadearth.campaign.SavedDataAccess;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import dev.deadearth.scenario.ScenarioStore;
import dev.deadearth.scenario.ScenarioTypes.*;
import dev.deadearth.scenario.WarningBeaconExecutor;
import dev.deadearth.scenario.ScenarioChoice;
import dev.deadearth.scenario.RelayRadioExecutor;
import dev.deadearth.observation.ObservationLog;
import dev.deadearth.observation.ObservationStore;
import net.minecraft.server.level.ServerPlayer;
import dev.deadearth.site.RelayPlacement;
import dev.deadearth.site.SiteRegistry;
import dev.deadearth.site.SiteStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import dev.deadearth.bridge.BridgeController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

public final class CoordinatorRuntime {
    private final MinecraftServer server;
    private final UUID epoch = UUID.randomUUID();
    private final CampaignStore campaign;
    private final ClimateStore climate;
    private final dev.deadearth.difficulty.AdaptiveStore adaptive;
    private final dev.deadearth.evolution.EvolutionController evolution;
    private final dev.deadearth.difficulty.PlayerStatistics statistics=new dev.deadearth.difficulty.PlayerStatistics();
    private final ScenarioStore scenarios;
    private final ObservationStore observations;
    private final SiteStore sites;
    private final BridgeController bridge;
    private final dev.deadearth.climate.SeasonWeather weather=new dev.deadearth.climate.SeasonWeather();
    private final dev.deadearth.director.DirectorPolicy director = new dev.deadearth.director.DirectorPolicy();
    private final dev.deadearth.director.CinematicDirector cinematics;
    private String fault;
    private ClimateCycle.Phase testSeason;
    public ClimateCycle.Phase effectiveSeason() { requireReady();return testSeason==null?climateStatus().phase():testSeason; }
    public void testSeason(ClimateCycle.Phase phase) {
        requireReady();
        if(!Boolean.getBoolean("deadearth.testControls")) throw new IllegalArgumentException("Test controls are disabled");
        testSeason=phase;
    }

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
        adaptive = campaign.enabled() ? SavedDataAccess.open(server.overworld(),dev.deadearth.difficulty.AdaptiveStore.NAME,
                new SavedData.Factory<>(()->dev.deadearth.difficulty.AdaptiveStore.createForCampaign(campaign),
                    (tag,registries)->dev.deadearth.difficulty.AdaptiveStore.load(campaign.campaign().campaignId(),campaign.campaign().activeTicks(),tag,registries))) : null;
        climate = campaign.enabled() && adaptive.enabled() ? SavedDataAccess.open(server.overworld(), ClimateStore.NAME,
                new SavedData.Factory<>(() -> ClimateStore.create(campaign.campaign().campaignId(),campaign.campaign().activeTicks()),
                        (tag, registries) -> ClimateStore.load(campaign.campaign().campaignId(),campaign.campaign().activeTicks(),tag,registries))) : null;
        evolution=campaign.enabled()&&adaptive!=null&&adaptive.enabled()&&climate!=null&&climate.enabled()
                ?new dev.deadearth.evolution.EvolutionController(server,campaign):null;
        bridge = new BridgeController(server, this);
        cinematics=enabled()?new dev.deadearth.director.CinematicDirector(server,this):null;
        if (enabled()) {
            if(!campaign.adaptiveRequired()) {
                SavedDataAccess.checkpoint(server.overworld(),dev.deadearth.difficulty.AdaptiveStore.NAME,adaptive);
                campaign.requireAdaptive();SavedDataAccess.checkpoint(server.overworld(),CampaignStore.NAME,campaign);
            }
            reconcileSites();
        }
    }

    public boolean enabled() { return fault == null && campaign.enabled() && scenarios != null && scenarios.enabled()
            && observations != null && observations.enabled() && sites != null && sites.enabled()
            && climate != null && climate.enabled() && adaptive != null && adaptive.enabled()
            && evolution!=null&&evolution.enabled(); }
    public dev.deadearth.director.CinematicDirector cinematics(){requireReady();return cinematics;}
    public void cinematicSignal(UUID operation,String kind,String role){requireServerThread();if(cinematics!=null)cinematics.signal(operation,kind,role);}
    public UUID epoch() { return epoch; }
    public BridgeController bridge() { requireServerThread(); return bridge; }
    public void close() { requireServerThread(); bridge.close();dev.deadearth.evolution.EvolutionState.clear(server); }
    public CampaignStore campaign() { requireServerThread(); return campaign; }
    public record Snapshot(UUID campaignId, UUID worldEpoch, long activeTicks, int onlinePlayers,
            ObservationLog.Snapshot observations, List<SiteRegistry.Site> discoveredSites,
            ClimateCycle.Snapshot climate, boolean climateEffectsEnabled, dev.deadearth.difficulty.AdaptiveStore.Snapshot difficulty,
            List<dev.deadearth.zone.ZoneScanner.Summary> zones,dev.deadearth.evolution.EvolutionController.Snapshot parasites,com.google.gson.JsonObject analytics) {
        public Snapshot { discoveredSites = List.copyOf(discoveredSites); zones = List.copyOf(zones); }
        public Snapshot(UUID campaignId,UUID worldEpoch,long activeTicks,int onlinePlayers,ObservationLog.Snapshot observations,
                List<SiteRegistry.Site> discoveredSites,ClimateCycle.Snapshot climate,boolean climateEffectsEnabled,
                dev.deadearth.difficulty.AdaptiveStore.Snapshot difficulty,List<dev.deadearth.zone.ZoneScanner.Summary> zones) {
            this(campaignId,worldEpoch,activeTicks,onlinePlayers,observations,discoveredSites,climate,climateEffectsEnabled,difficulty,zones,null,null);
        }
    }
    public Snapshot snapshot() {
        requireReady();
        return new Snapshot(campaign.campaign().campaignId(), epoch, campaign.campaign().activeTicks(),
                server.getPlayerCount(), observations.log().snapshot(), sites.registry().sites().stream().filter(s -> s.discoverer() != null).toList(),
                climateStatus(),true,difficultyStatus(),dev.deadearth.zone.ZoneLoaders.summaries(server),evolutionStatus(),cinematics==null?null:cinematics.analytics().summary(dev.deadearth.director.AnalyticsTransport.SUMMARY_BYTES));
    }
    public record DirectorSnapshot(dev.deadearth.director.DirectorPolicy.Brief planning, String season, double pressure) {}
    private dev.deadearth.director.DirectorPolicy.Settings directorSettings() {
        return new dev.deadearth.director.DirectorPolicy.Settings(b(DIRECTOR_ENABLED),i(DIRECTOR_INTERVAL),
                Math.min(i(DIRECTOR_LIFETIME),i(DIRECTOR_INTERVAL)),i(DIRECTOR_ZONE_AGE));
    }
    private List<dev.deadearth.director.DirectorPolicy.Candidate> directorCandidates() {
        return dev.deadearth.zone.ZoneLoaders.summaries(server).stream().map(z ->
                new dev.deadearth.director.DirectorPolicy.Candidate(z.id().toString(),z.value(),z.ageTicks(),z.complete(),z.overflow())).toList();
    }
    public DirectorSnapshot directorBrief() {
        requireReady();
        return new DirectorSnapshot(director.brief(campaign.campaign().campaignId(),epoch,campaign.campaign().activeTicks(),
                server.getPlayerCount(),directorSettings(),directorCandidates()),effectiveSeason().name(),difficultyStatus().pressure());
    }
    public dev.deadearth.director.DirectorPolicy.Verdict previewDirector(UUID request,UUID goal,String variant,String text) {
        requireReady();
        return director.preview(request,goal,variant,text,campaign.campaign().campaignId(),epoch,campaign.campaign().activeTicks(),
                server.getPlayerCount(),directorSettings(),directorCandidates());
    }
    public ClimateCycle.Snapshot climateStatus() {
        requireReady(); return climate.cycle().view(campaign.campaign().activeTicks());
    }
    public dev.deadearth.difficulty.AdaptiveStore.Snapshot difficultyStatus() {requireReady();return adaptive.view(effectiveSeason(),campaign.campaign().activeTicks());}
    public dev.deadearth.evolution.EvolutionController.Snapshot evolutionStatus(){
        requireServerThread();
        if(evolution!=null&&fault==null)return evolution.snapshot(epoch,enabled()?difficultyStatus().parasiteEvolutionRate():0,!enabled()||server.getPlayerCount()==0);
        return new dev.deadearth.evolution.EvolutionController.Snapshot("unavailable",false,campaign.enabled()?campaign.campaign().campaignId():new UUID(0,0),epoch,0,"0".repeat(64),0,"Недоступно",0,0,0,null,0,true,"unavailable",List.of(),List.of(),List.of());
    }
    public dev.deadearth.evolution.EvolutionController.Shift shiftEvolution(UUID campaignId,UUID expectedEpoch,long revision,UUID request,int delta){
        requireReady();
        if(campaignId==null||expectedEpoch==null||request==null||revision<0||Math.abs(delta)!=1)
            throw new IllegalArgumentException("Phase delta must be -1 or 1");
        try{return evolution.shift(campaignId,expectedEpoch,epoch,revision,request,delta,difficultyStatus().parasiteEvolutionRate(),server.getPlayerCount()==0);}
        catch(RuntimeException failure){disable(failure);throw failure;}
    }
    public dev.deadearth.evolution.EvolutionCatalog evolutionCatalog(){requireReady();return evolution.catalog();}
    public void playerLeft(UUID player){requireServerThread();statistics.forget(player);}
    public void testPressure(double value){requireReady();adaptive.testPressure(value);checkpoint();}
    public List<ClimateCycle.Window> climateForecast() {
        requireReady(); return climate.forecast(campaign.campaign().activeTicks(),4);
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
    public Decision preview(UUID request) { return preview(request,ScenarioChoice.warning()); }
    public Decision preview(UUID request, ScenarioChoice choice) {
        requireReady();
        var existing=scenarios.engine().findRequest(request);
        if (existing.isPresent()) return existing.get().proposal().choice().equals(choice)
                ? new Decision(true,List.of()) : new Decision(false,List.of("conflicting_request"));
        return scenarios.engine().preview(proposal(request,choice),view());
    }
    public Receipt submit(UUID request) { return submit(request,ScenarioChoice.warning()); }
    public Receipt submit(UUID request, ScenarioChoice choice) {
        requireReady();
        var existing = scenarios.engine().findRequest(request);
        if (existing.isPresent()) return existing.get().proposal().choice().equals(choice) ? existing.get()
                : new Receipt(existing.get().proposal(),null,State.REJECTED,0,"conflicting_request");
        var receipt = scenarios.engine().submit(proposal(request,choice), view());
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
        var known=new java.util.HashSet<String>();var loaded=new java.util.HashSet<String>();known.add("test_anchor");
        if (server.overworld().hasChunkAt(server.overworld().getSharedSpawnPos())) loaded.add("test_anchor");
        for (var site:sites.registry().sites()) if (site.discoverer()!=null) {
            String target="relay:"+site.id();known.add(target);
            var origin=new BlockPos(site.x(),site.y(),site.z());
            if (site.state()==SiteRegistry.State.READY && server.overworld().hasChunksAt(origin,origin.offset(8,5,8))) loaded.add(target);
        }
        return new WorldView(epoch,campaign.campaign().activeTicks(),server.getPlayerCount(),known,loaded);
    }

    public void tick() {
        requireServerThread();
        if (!enabled()) { tickBridge(); return; }
        try {
            campaign.advance(server.getPlayerCount() > 0);
            dev.deadearth.starter.SafeBunker.tick(server,this);
            if(server.getPlayerCount()==0)statistics.retain(java.util.Set.of());
            if(server.getPlayerCount()>0 && campaign.campaign().activeTicks()%i(SAMPLE_TICKS)==0)
                for(var sample:statistics.collect(server.getPlayerList().getPlayers())) adaptive.observe(sample);
            adaptive.advance(campaign.campaign().activeTicks(),server.getPlayerCount()>0);
            var season=effectiveSeason();
            double pressure=dev.deadearth.difficulty.IntroDifficulty.pressure(campaign.campaign().activeTicks(),adaptive.pressure());
            dev.deadearth.climate.SeasonState.update(season,pressure);
            evolution.advance(campaign.campaign().activeTicks(),server.getPlayerCount()>0,difficultyStatus().parasiteEvolutionRate());
            weather.tick(server.overworld(),season,server.getPlayerCount()>0,climate.cycle().view(campaign.campaign().activeTicks()).cycle());
            if (server.getPlayerCount()>0 && server.getTickCount()%i(NETWORK_REFRESH)==0)
                for(var player:server.getPlayerList().getPlayers())
                    if(net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(player.connection,dev.deadearth.climate.SeasonNetwork.State.TYPE.id()))
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new dev.deadearth.climate.SeasonNetwork.State(season.ordinal(),pressure));
            var notices=climate.advance(campaign.campaign().activeTicks(),server.getPlayerCount()>0);
            if (!notices.isEmpty()) {
                checkpoint();
                for(var notice:notices) server.getPlayerList().broadcastSystemMessage(Component.literal(ClimateMessages.text(notice)),false);
            }
            if (server.getPlayerCount() > 0 && campaign.campaign().activeTicks() % i(OBSERVATION_REFRESH) == 0) {
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
                var proposal=scenarios.engine().findScenario(id).orElseThrow().proposal();
                if (!proposal.choice().radio()) return new WarningBeaconExecutor(server).execute(id);
                var site=sites.registry().sites().stream().filter(s -> ("relay:"+s.id()).equals(proposal.targetId())).findFirst();
                if (site.isEmpty()) return StepResult.UNCERTAIN;
                return new RelayRadioExecutor(server,site.get(),proposal.choice()).execute(id);
            });
            if (!changed.isEmpty()) {
                scenarios.setDirty();
                checkpoint();
                for (var receipt : changed)
                    DeadEarth.LOGGER.info("Dead Earth scenario {} -> {}", receipt.scenarioId(), receipt.state());
            }
        } catch (RuntimeException error) { disable(error); }
        if(cinematics!=null)cinematics.tick();
        tickBridge();
    }

    private void tickBridge() {
        try { bridge.tick(); }
        catch (RuntimeException error) {
            bridge.close(); DeadEarth.LOGGER.error("Dead Earth bridge disabled after internal failure; campaign remains autonomous", error);
        }
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
    private Proposal proposal(UUID request, ScenarioChoice choice) {
        long now = campaign.campaign().activeTicks();
        if (now > Long.MAX_VALUE - 2200) throw new IllegalStateException("Campaign clock exhausted");
        return new Proposal(request,epoch,choice.targetId(),now+choice.delayTicks(),now+choice.delayTicks()+1000,choice.catalogId(),choice.variant(),choice.delayTicks());
    }
    public void checkpointCinematic(){requireReady();checkpoint();}
    private void checkpoint() {
        try {
            SavedDataAccess.checkpoint(server.overworld(), CampaignStore.NAME, campaign);
            SavedDataAccess.checkpoint(server.overworld(), dev.deadearth.difficulty.AdaptiveStore.NAME, adaptive);
            evolution.checkpoint();
            SavedDataAccess.checkpoint(server.overworld(), ClimateStore.NAME, climate);
            SavedDataAccess.checkpoint(server.overworld(), ScenarioStore.NAME, scenarios);
            SavedDataAccess.checkpoint(server.overworld(), ObservationStore.NAME, observations);
            SavedDataAccess.checkpoint(server.overworld(), SiteStore.NAME, sites);
        } catch (RuntimeException error) { disable(error); throw error; }
    }
    private void disable(RuntimeException error) {
        if (fault == null) {
            fault = error.getClass().getSimpleName();
            dev.deadearth.evolution.EvolutionState.clear(server);
            dev.deadearth.climate.SeasonState.reset();
            for(var player:server.getPlayerList().getPlayers()) try {
                if(net.neoforged.neoforge.network.registration.NetworkRegistry.hasChannel(player.connection,dev.deadearth.climate.SeasonNetwork.State.TYPE.id()))
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new dev.deadearth.climate.SeasonNetwork.State(-1,1));
            } catch(RuntimeException noticeFailure) {DeadEarth.LOGGER.warn("Unable to clear client season after coordinator failure",noticeFailure);}
            DeadEarth.LOGGER.error("Dead Earth paused after persistence/execution failure", error);
        }
    }
}
