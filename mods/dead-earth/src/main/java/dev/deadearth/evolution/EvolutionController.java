package dev.deadearth.evolution;

import java.util.*;
import dev.deadearth.campaign.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.fml.loading.FMLPaths;

/** Server-thread orchestration: save before publishing new permissions. */
public final class EvolutionController {
    public record Snapshot(String status,boolean enabled,UUID campaignId,UUID worldEpoch,long revision,String configRevision,
            int phase,String name,double progress,double points,double phaseStartPoints,Double nextPhasePoints,
            double rate,boolean paused,String migration,List<String> unlockedEntities,List<String> abilities,List<EvolutionStore.Transition> history) {}
    public record Shift(boolean accepted,String code,Snapshot evolution) {}
    private final MinecraftServer server;
    private final CampaignStore campaign;
    private final EvolutionCatalog catalog;
    private final EvolutionStore store;
    private boolean persistenceFailed;
    public EvolutionController(MinecraftServer server,CampaignStore campaign) {
        this.server=server;this.campaign=campaign;
        catalog=EvolutionCatalog.load(FMLPaths.CONFIGDIR.get().resolve("dead-earth/evolution.json"));
        for(var phase:catalog.phases())for(var id:phase.entities())
            if(!BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(id)))throw new IllegalArgumentException("Unknown configured evolution entity");
        store=SavedDataAccess.open(server.overworld(),EvolutionStore.NAME,new SavedData.Factory<>(
            ()->EvolutionStore.createForCampaign(campaign,catalog,legacyTime(server)),
            (tag,registries)->EvolutionStore.load(campaign.campaign().campaignId(),campaign.campaign().activeTicks(),tag,registries)));
        if(store.enabled()) {
            SavedDataAccess.checkpoint(server.overworld(),EvolutionStore.NAME,store);
            if(!campaign.evolutionRequired()){campaign.requireEvolution();SavedDataAccess.checkpoint(server.overworld(),CampaignStore.NAME,campaign);}
            publish();
        } else EvolutionState.publish(server,catalog,0);
    }
    private static double legacyTime(MinecraftServer server) {
        try {return ((Number)Class.forName("com.asestefan.mutationcraft.network.ModVariables").getMethod("time",LevelAccessor.class).invoke(null,server.overworld())).doubleValue();}
        catch(ReflectiveOperationException ex){throw new IllegalStateException("Unsupported native evolution save",ex);}
    }
    public boolean enabled(){return store.enabled()&&!persistenceFailed;}
    public EvolutionCatalog catalog(){return catalog;}
    public int phase(){return catalog.phase(store.points());}
    private void publish(){EvolutionState.publish(server,catalog,phase());}
    public void advance(long now,boolean online,double rate){if(store.advance(now,online,rate,catalog)){checkpoint();publish();}}
    public void checkpoint(){
        try {
            // The active clock must reach disk before an evolution checkpoint that refers to it.
            SavedDataAccess.checkpoint(server.overworld(),CampaignStore.NAME,campaign);
            SavedDataAccess.checkpoint(server.overworld(),EvolutionStore.NAME,store);
        } catch(RuntimeException failure) {
            persistenceFailed=true;store.setDirty(false);EvolutionState.clear(server);throw failure;
        }
    }
    public Snapshot snapshot(UUID epoch,double rate,boolean paused) {
        if(!enabled())return new Snapshot(persistenceFailed?"unavailable":"unsupported_save",false,campaign.campaign().campaignId(),epoch,0,catalog.revision(),0,"Недоступно",0,0,0,null,0,true,"unavailable",List.of(),List.of(),List.of());
        int phase=phase();var current=catalog.phases().get(phase);Double next=phase<5?(double)catalog.phases().get(phase+1).threshold():null;
        double progress=next==null?1:Math.clamp((store.points()-current.threshold())/(next-current.threshold()),0,1);
        return new Snapshot(!catalog.enabled()?"disabled":paused?"paused":"ready",catalog.enabled(),campaign.campaign().campaignId(),epoch,store.revision(),catalog.revision(),phase,current.name(),progress,store.points(),current.threshold(),next,paused?0:rate,paused,store.migration(),catalog.entities(phase),catalog.abilities(phase),store.history());
    }
    public Shift shift(UUID expectedCampaign,UUID expectedEpoch,UUID actualEpoch,long revision,UUID request,int delta,double rate,boolean paused) {
        if(!enabled())return new Shift(false,"unavailable",snapshot(actualEpoch,rate,paused));
        if(!expectedCampaign.equals(campaign.campaign().campaignId()))return new Shift(false,"stale_campaign",snapshot(actualEpoch,rate,paused));
        if(!expectedEpoch.equals(actualEpoch))return new Shift(false,"stale_epoch",snapshot(actualEpoch,rate,paused));
        if(!catalog.enabled())return new Shift(false,"disabled",snapshot(actualEpoch,rate,paused));
        var result=store.shift(request,revision,delta,campaign.campaign().activeTicks(),catalog);
        checkpoint();if(result.accepted())publish();
        return new Shift(result.accepted(),result.code(),snapshot(actualEpoch,rate,paused));
    }
}
