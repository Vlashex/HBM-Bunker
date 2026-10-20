package dev.deadearth.legacyprobe;

import com.google.gson.JsonObject;
import dev.deadearth.legacy.Campaign;
import dev.deadearth.legacy.DeadEarthLegacy;
import dev.deadearth.legacy.JsonFiles;
import dev.deadearth.legacy.LegacyRuntime;
import dev.deadearth.legacy.Reflect;
import dev.deadearth.legacy.kernel.CinematicCatalog;
import dev.deadearth.legacy.kernel.CinematicEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;

/** Runs only in a fresh, explicitly enabled native integration server. */
@Mod(modid="deadearth_port_probe",name="Dead Earth SRP Native Port Probe",version="0.2.0",
    dependencies="required-after:dead_earth;required-after:srparasites;required-after:epicsiegemod",acceptableRemoteVersions="*")
public final class PortProbe {
    private final List<Map<String,Object>> checks=new ArrayList<Map<String,Object>>();
    private final Map<String,Object> report=new LinkedHashMap<String,Object>();
    private LegacyRuntime runtime;private Object world,player;private List<Object> playerList;
    private int baseX,baseY,baseZ;private int failures;

    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        report.put("schema",1);report.put("probe","native_srp_forge_1_12_2");
        try {
            if(!Boolean.getBoolean("deadearth.testControls"))throw new IllegalStateException("Test controls are not enabled");
            if(!Files.exists(Paths.get(".dead-earth-isolated-native-test")))throw new IllegalStateException("Isolated test marker is absent");
            runtime=DeadEarthLegacy.runtime;if(runtime==null)throw new IllegalStateException("Coordinator did not start before probe");
            runtime.requireThread();world=runtime.world.world(0);
            run("native_dependencies",()->dependencies());
            run("no_player_pause_and_native_lock",()->noPlayers());
            run("transformed_world_write_guard",()->writeGuard());
            run("survival_player_advances_active_clock",()->survivalPlayer());
            run("phase_zero_to_ten_CAS_round_trip",()->phaseRoundTrip());
            run("loaded_relays_six_tickets_no_overlap",()->anchors());
            run("two_chests_and_unopened_loot",()->valuation());
            run("native_health_scaling_is_idempotent",()->health());
            run("phase_gated_native_wave_emission",()->wave());
            run("typed_queue_replay_and_native_effect",()->queue());
            run("durable_campaign_checkpoint",()->{runtime.checkpoint();require(Files.size(runtime.savePath)>0,"campaign checkpoint exists");Campaign loaded=Campaign.open(runtime.savePath);require(loaded.campaignId.equals(runtime.campaign.campaignId),"campaign restores same identity");CinematicEngine.restore(loaded.cinematic);});
            report.put("activeTicks",runtime.campaign.activeTicks);report.put("phase",runtime.campaign.phase);
            report.put("nativePhase",runtime.srp.phase(world));report.put("zoneCount",runtime.campaign.zones.size());
            report.put("receiptCount",runtime.campaign.receipts.size());
        } catch(Throwable failed) {
            failures++;checks.add(result("probe_setup",false,failed));failed.printStackTrace();
        } finally {
            boolean retained=Boolean.getBoolean("deadearth.bridgeProbe")&&failures==0&&playerList!=null&&player!=null;
            if(retained) {
                try {
                    // Isolated fixture reset: the completed native queue test
                    // would otherwise cool down Zone A for 18000 active ticks.
                    // No production reset endpoint or setter is exposed.
                    ((List<?>)Reflect.get(runtime.engine,"history")).clear();
                    Reflect.set(runtime.engine,"nextDecision",0L);Reflect.set(runtime.engine,"recoveryUntil",0L);
                    Reflect.set(runtime.engine,"heavyUntil",0L);Reflect.set(runtime.engine,"pressuresSinceRest",0);
                    Reflect.set(runtime.engine,"goal",null);runtime.checkpoint();
                    Reflect.call(Reflect.get(runtime,"bridge"),"publish");
                    report.put("bridgeFixtureReset",true);
                } catch(Throwable failed) {
                    retained=false;failures++;checks.add(result("bridge_fixture_preparation",false,failed));failed.printStackTrace();
                }
            }
            if(!retained&&playerList!=null&&player!=null)playerList.remove(player);
            report.put("bridgePlayerRetained",retained);
            report.put("checks",checks);report.put("failures",failures);report.put("passed",failures==0);
            try {
                Path output=Paths.get("port-probe.json"),temporary=Paths.get("port-probe.json.tmp");
                Files.write(temporary,JsonFiles.JSON.toJson(report).getBytes(StandardCharsets.UTF_8));
                Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                System.out.println("DEAD EARTH native port probe finished: "+(failures==0?"passed":"failed"));
            } catch(Exception failed){failed.printStackTrace();}
        }
        // The runner requests a normal stop only after reading the complete report.
    }

    private void dependencies() {
        require(Loader.isModLoaded("dead_earth"),"coordinator loaded");
        require(Loader.isModLoaded("srparasites"),"native SRP loaded");
        require(Loader.isModLoaded("epicsiegemod"),"native Epic Siege loaded");
        require(Loader.isModLoaded("hbm"),"native HBM loaded");
        require(Reflect.registry("BLOCKS","dead_earth:zone_relay")!=null,"own relay registered");
        Object exemptions=Reflect.get(Reflect.type("funwayguy.epicsiegemod.config.EsmConfigGlobal"),"AIExempt");
        boolean parasite=false,zombie=false;
        for(Object id:(Collection<?>)exemptions){if(id.toString().equals("srparasites:buglin"))parasite=true;if(id.toString().equals("minecraft:zombie"))zombie=true;}
        require(parasite&&!zombie,"Epic Siege AI scope preserves native parasites and enables zombies");
        require(runtime.settings.maximumZones==6,"six-anchor configuration");
    }

    private void noPlayers() {
        require(runtime.world.players().isEmpty(),"fresh test has no survival players");
        long ticks=runtime.campaign.activeTicks;double points=runtime.campaign.points;
        runtime.tick();runtime.advanceForTest(200,0);
        require(runtime.campaign.activeTicks==ticks&&runtime.campaign.points==points,"offline active clock and evolution pause");
        Object data=runtime.srp.data(world);int dim=Reflect.dimension(world);
        require(!(Boolean)Reflect.call(data,"getCanGain",dim),"native gain is locked");
        int before=((Number)Reflect.call(data,"getTotalKills",dim)).intValue();
        Reflect.call(data,"setTotalKills",dim,100,true,world,false);
        require(((Number)Reflect.call(data,"getTotalKills",dim)).intValue()==before,"native kill gain cannot bypass lock");
    }

    private void writeGuard() {
        Object spawn=Reflect.call(world,"getSpawnPoint/func_175694_M");int x=Reflect.x(spawn),y=Reflect.y(spawn),z=Reflect.z(spawn);
        Object inside=Reflect.pos(x+1,y+1,z+1),edge=Reflect.pos(x+16,y+32,z),outside=Reflect.pos(x+17,y+1,z+1);
        load(x,z,2);
        require(runtime.protectedAt(world,inside)&&runtime.protectedAt(world,edge),"spawn grace includes 16 by 32 boundary");
        require(!runtime.protectedAt(world,outside)&&!runtime.protectedAt(world,Reflect.pos(x,y+33,z)),"spawn grace has finite boundary");
        placeAt(inside,"minecraft:stone");
        require((Boolean)Reflect.call(world,"setBlockState/func_180501_a",inside,state("minecraft:glass"),3),"player-side world mutation remains available");
        boolean denied=(Boolean)Reflect.call(Reflect.type("com.dhanantry.scapeandrunparasites.legacyprobe.NativeWriter"),"write",world,inside,state("minecraft:obsidian"));
        require(!denied&&Reflect.blockId(world,inside).equals("minecraft:glass"),"actual World write blocks native parasite caller");
        require((Boolean)Reflect.call(Reflect.type("com.dhanantry.scapeandrunparasites.legacyprobe.NativeWriter"),"write",world,outside,state("minecraft:stone")),"native writer allowed outside grace");
    }

    @SuppressWarnings("unchecked") private void survivalPlayer() {
        Object spawn=Reflect.call(world,"getSpawnPoint/func_175694_M");
        Object profile=Reflect.make("com.mojang.authlib.GameProfile",UUID.fromString("44a86b84-0d55-44de-b7af-e5986274846c"),"DeadEarthProbe");
        player=Reflect.call(Reflect.type("net.minecraftforge.common.util.FakePlayerFactory"),"get",world,profile);
        Object survival=Reflect.get(Reflect.type("net.minecraft.world.GameType"),"SURVIVAL");
        Reflect.call(Reflect.get(player,"interactionManager/field_71134_c"),"setGameType/func_73076_a",survival);
        Reflect.call(player,"setPosition/func_70107_b",Reflect.x(spawn)+.5,(double)Reflect.y(spawn)+1,Reflect.z(spawn)+.5);
        Reflect.call(player,"setHealth/func_70606_j",20f);
        playerList=(List<Object>)Reflect.get(world,"playerEntities/field_73010_i");
        if(!playerList.contains(player))playerList.add(player);
        require(runtime.world.players().contains(player),"in-world fake player is survival");
        long ticks=runtime.campaign.activeTicks;double points=runtime.campaign.points;
        runtime.advanceForTest(200,1);
        require(runtime.campaign.activeTicks==ticks+200,"online active clock advances exact virtual ticks");
        require(runtime.campaign.points>points,"online survival pressure advances native-backed evolution points");
        require(runtime.srp.phase(world)==runtime.campaign.phase,"native phase synchronized after advancement");
    }

    private void phaseRoundTrip() {
        while(runtime.campaign.phase>0)shift(-1);
        for(int phase=1;phase<=10;phase++){shift(1);require(runtime.campaign.phase==phase&&runtime.srp.phase(world)==phase,"native upward phase "+phase);}
        JsonObject status=asObject(runtime.command(new String[]{"evolution","status"}));
        require(status.get("phaseMaximum").getAsInt()==10&&status.get("phase").getAsInt()==10,"operator status exposes phase ten");
        long revision=runtime.campaign.revision;
        JsonObject staleEpoch=shiftRequest(runtime.campaign.campaignId,UUID.randomUUID().toString(),revision,UUID.randomUUID().toString(),-1);
        require(!staleEpoch.get("accepted").getAsBoolean()&&"stale_epoch".equals(staleEpoch.get("code").getAsString()),"stale epoch rejected");
        JsonObject staleCampaign=shiftRequest(UUID.randomUUID().toString(),runtime.campaign.worldEpoch,revision,UUID.randomUUID().toString(),-1);
        require(!staleCampaign.get("accepted").getAsBoolean()&&"stale_campaign".equals(staleCampaign.get("code").getAsString()),"stale campaign rejected");
        JsonObject staleRevision=shiftRequest(runtime.campaign.campaignId,runtime.campaign.worldEpoch,revision-1,UUID.randomUUID().toString(),-1);
        require(!staleRevision.get("accepted").getAsBoolean()&&"stale_revision".equals(staleRevision.get("code").getAsString()),"stale revision rejected");
        require(runtime.campaign.revision==revision&&runtime.campaign.phase==10,"stale commands have no phase effect");
        for(int phase=9;phase>=0;phase--){shift(-1);require(runtime.campaign.phase==phase&&runtime.srp.phase(world)==phase,"native downward phase "+phase);}
    }

    private void shift(int delta) {
        long revision=runtime.campaign.revision;String request=UUID.randomUUID().toString();
        JsonObject first=shiftRequest(runtime.campaign.campaignId,runtime.campaign.worldEpoch,revision,request,delta);
        require(first.get("accepted").getAsBoolean(),"phase CAS accepted");
        JsonObject replay=shiftRequest(runtime.campaign.campaignId,runtime.campaign.worldEpoch,revision,request,delta);
        require(first.equals(replay)&&runtime.campaign.revision==revision+1,"phase receipt replay is idempotent");
        JsonObject conflict=shiftRequest(runtime.campaign.campaignId,runtime.campaign.worldEpoch,revision,request,-delta);
        require(!conflict.get("accepted").getAsBoolean()&&runtime.campaign.revision==revision+1,"phase receipt rejects conflicting payload");
    }

    private JsonObject shiftRequest(String campaign,String epoch,long revision,String request,int delta) {
        return asObject(runtime.command(new String[]{"evolution","shift",campaign,epoch,Long.toString(revision),request,Integer.toString(delta)}));
    }

    private void anchors() {
        Object far=Reflect.pos(1000000,64,1000000);require(!Reflect.loaded(world,far),"unloaded anchor fixture");
        int before=runtime.campaign.zones.size();boolean accepted=runtime.anchor(world,far);
        if(accepted){for(int i=runtime.campaign.zones.size()-1;i>=before;i--)runtime.campaign.zones.remove(i);}
        require(!accepted&&runtime.campaign.zones.size()==before,"unloaded anchor cannot allocate zone");
        Object spawn=Reflect.call(world,"getSpawnPoint/func_175694_M");baseX=Reflect.x(spawn)+160;baseZ=Reflect.z(spawn)+160;
        load(baseX,baseZ,3);
        Object surface=Reflect.call(world,"getTopSolidOrLiquidBlock/func_175672_r",Reflect.pos(baseX,64,baseZ));baseY=Math.max(4,Reflect.y(surface));
        for(int i=0;i<6;i++) {
            int x=baseX+i*80;load(x,baseZ,2);Object pos=Reflect.pos(x,baseY,baseZ);
            placeAt(Reflect.pos(x,baseY-1,baseZ),"minecraft:stone");placeAt(pos,"dead_earth:zone_relay");
            require(runtime.anchor(world,pos),"physical relay accepted "+i);
            Object tile=Reflect.call(world,"getTileEntity/func_175625_s",pos);require(tile!=null,"physical relay tile exists "+i);
            Reflect.call(tile,"update/func_73660_a");Object ticket=Reflect.get(tile,"ticket");
            require(ticket!=null,"real Forge chunk ticket allocated "+i);
            require(((Collection<?>)Reflect.call(ticket,"getChunkList")).size()==9,"ticket forces exactly 3 by 3 chunks "+i);
            if(i==0) {
                Object overlap=Reflect.pos(baseX+16,baseY,baseZ);placeAt(overlap,"dead_earth:zone_relay");
                require(!runtime.anchor(world,overlap)&&runtime.campaign.zones.size()==1,"overlapping anchor rejected before reaching cap");
            }
        }
        require(runtime.campaign.zones.size()==6,"six distinct physical zones");
        int x=baseX+6*80;load(x,baseZ,1);Object seventh=Reflect.pos(x,baseY,baseZ);placeAt(seventh,"dead_earth:zone_relay");
        require(!runtime.anchor(world,seventh)&&runtime.campaign.zones.size()==6,"seventh anchor cannot exceed cap");
        Object tile=Reflect.call(world,"getTileEntity/func_175625_s",seventh);Reflect.call(tile,"update/func_73660_a");
        require(Reflect.get(tile,"ticket")==null,"rejected anchor owns no chunk ticket");
        Reflect.call(player,"setPosition/func_70107_b",baseX+.5,(double)baseY,baseZ+.5);
    }

    private void valuation() {
        require(runtime.campaign.zones.size()==6,"anchor prerequisite");Campaign.Zone zone=runtime.campaign.zones.get(0);
        Object first=place(baseX+2,baseY,baseZ,"minecraft:chest"),second=place(baseX+5,baseY,baseZ,"minecraft:chest"),unopened=place(baseX+8,baseY,baseZ,"minecraft:chest");
        Object iron=Reflect.registry("ITEMS","minecraft:iron_ingot");
        Reflect.call(first,"setInventorySlotContents/func_70299_a",0,Reflect.make("net.minecraft.item.ItemStack",iron,19,0));
        Reflect.call(second,"setInventorySlotContents/func_70299_a",0,Reflect.make("net.minecraft.item.ItemStack",iron,13,0));
        Reflect.call(unopened,"setLootTable/func_189404_a",Reflect.make("net.minecraft.util.ResourceLocation","minecraft:chests/simple_dungeon"),123L);
        Object loot=Reflect.get(unopened,"lootTable/field_184284_m");require(loot!=null,"unopened chest retains deferred loot");
        zone.complete=false;zone.scannedAt=runtime.campaign.activeTicks-runtime.settings.scanTicks;
        for(int i=0;i<128&&!zone.complete;i++)runtime.world.scan();
        Long price=runtime.settings.resourcePrices.get("minecraft:iron_ingot");require(price!=null,"iron resource price configured");
        require(zone.complete&&zone.resourceEmc==32L*price.longValue(),"bounded zone scan sums two real chests: complete="+zone.complete+", emc="+zone.resourceEmc+", error="+zone.scanError);
        require(zone.ignoredLootContainers==1,"deferred loot chest counted separately");
        require(Reflect.get(unopened,"lootTable/field_184284_m")!=null,"valuation does not generate deferred loot");
    }

    private void health() {
        Object zombie=entity("minecraft:zombie");runtime.scale(zombie);
        Object attribute=Reflect.get(Reflect.type("net.minecraft.entity.SharedMonsterAttributes"),"MAX_HEALTH/field_111267_a");
        Object instance=Reflect.call(zombie,"getEntityAttribute/func_110148_a",attribute);
        UUID id=UUID.fromString("fc254a51-aa7e-4df4-b2fe-963155acfa31");
        Object modifier=Reflect.call(instance,"getModifier/func_111127_a",id);
        double maximum=((Number)Reflect.call(zombie,"getMaxHealth/func_110138_aP")).doubleValue();
        Reflect.call(zombie,"setHealth/func_70606_j",(float)(maximum*.6));Object before=modifier;
        runtime.scale(zombie);runtime.scale(zombie);
        require(Math.abs(((Number)Reflect.call(zombie,"getMaxHealth/func_110138_aP")).doubleValue()-maximum)<.001,"repeated scale preserves native max health");
        require(Math.abs(((Number)Reflect.call(zombie,"getHealth/func_110143_aJ")).doubleValue()/maximum-.6)<.001,"repeated scale preserves health fraction");
        require(before!=null&&Reflect.call(instance,"getModifier/func_111127_a",id)==before,"health modifier is reused without stacking");
    }

    private void wave() {
        require(!runtime.campaign.zones.isEmpty(),"wave anchor prerequisite");Campaign.Zone zone=runtime.campaign.zones.get(0);
        runtime.campaign.phase=0;runtime.campaign.points=0;runtime.srp.sync(world,0,0);
        Object high=entity("srparasites:rupter"),low=entity("srparasites:buglin");
        require(!runtime.world.phaseAllowed(high)&&runtime.world.phaseAllowed(low),"phase zero rejects locked form and allows scout");
        Map<String,CinematicCatalog.Wave> waves=new LinkedHashMap<String,CinematicCatalog.Wave>(runtime.catalog.waves());
        waves.put("probe_high",new CinematicCatalog.Wave(Collections.singletonList("srparasites:rupter")));
        CinematicCatalog frozen=new CinematicCatalog(runtime.catalog.revision(),runtime.catalog.rules(),runtime.catalog.sounds(),runtime.catalog.traces(),waves,runtime.catalog.events());
        CinematicCatalog.Event event=runtime.catalog.events().values().iterator().next();
        CinematicEngine.Operation operation=new CinematicEngine.Operation(UUID.randomUUID(),UUID.randomUUID(),CinematicEngine.freeze(runtime.catalog,event.id()),event.id(),zone.id,CinematicEngine.State.APPLYING,event.start(),0,runtime.campaign.activeTicks,runtime.campaign.activeTicks+600,runtime.campaign.activeTicks,3,0,"",Collections.<String>emptySet());
        CinematicCatalog.Action action=new CinematicCatalog.Action("wave","probe_high","approach",1);
        Set<String> before=new HashSet<String>(runtime.campaign.owned);
        require(runtime.world.point(zone,"approach",true)!=null,"native wave has safe loaded approach");
        runtime.world.emit(operation,action,3,frozen);
        require(runtime.campaign.owned.equals(before),"phase-gated wave emits no locked entities");
        runtime.campaign.phase=10;runtime.campaign.points=runtime.srp.thresholds[10];runtime.srp.sync(world,10,runtime.campaign.points);
        require(runtime.world.phaseAllowed(high),"phase ten allows configured form");
        runtime.world.emit(operation,action,1,frozen);
        require(runtime.campaign.owned.size()==before.size()+1,"real native allowed wave emits exactly once");
        runtime.campaign.phase=0;runtime.campaign.points=0;runtime.srp.sync(world,0,0);
        boolean retained=false;
        for(Object level:runtime.world.worlds())for(Object value:new ArrayList<Object>((Collection<?>)Reflect.get(level,"loadedEntityList/field_72996_f"))) {
            String id=Reflect.call(value,"getUniqueID/func_110124_au").toString();
            if(!before.contains(id)&&runtime.campaign.owned.contains(id)) {
                retained=(Boolean)Reflect.call(value,"isEntityAlive/func_70089_S");Reflect.call(value,"setDead/func_70106_y");runtime.campaign.owned.remove(id);runtime.campaign.ownership.remove(id);
            }
        }
        require(retained,"phase downgrade does not erase already emitted entity");
    }

    private void queue() {
        runtime.advanceForTest(20,1);
        List<CinematicEngine.Option> options=runtime.engine.options(runtime.catalog,runtime.context());require(!options.isEmpty(),"physical zone has typed catalogue options");
        CinematicEngine.Option choice=options.get(0);UUID request=UUID.randomUUID();
        CinematicEngine.Verdict first=runtime.engine.admitManual(request,choice.eventId(),choice.zoneId(),runtime.catalog,runtime.context(),true);
        require(first.accepted(),"finite typed operation admitted");runtime.checkpoint();
        CinematicEngine.Verdict replay=runtime.engine.admitManual(request,choice.eventId(),choice.zoneId(),runtime.catalog,runtime.context(),true);
        require(first.operationId().equals(replay.operationId())&&runtime.engine.operations().size()==1,"native queue request replay shares operation");
        CinematicEngine.Operation operation=runtime.engine.operations().get(0);
        require(operation.budget()>=1&&operation.budget()<=runtime.catalog.rules().maxWave(),"native queue finite head count");
        runtime.engine.tick(runtime.catalog,runtime.context(),runtime.world);
        boolean effect=runtime.engine.operations().isEmpty()||runtime.engine.operations().get(0).cursor()>0;
        require(effect,"native world adapter executes first typed effect");
        if(!runtime.engine.operations().isEmpty())runtime.engine.cancel(first.operationId(),runtime.campaign.activeTicks,runtime.catalog.rules(),runtime.world);
        require(runtime.engine.history().get(runtime.engine.history().size()-1).spent()<=operation.budget(),"native operation never exceeds allocation");
    }

    private Object entity(String id) {
        Object value=Reflect.call(Reflect.type("net.minecraft.entity.EntityList"),"createEntityByIDFromName/func_188429_b",Reflect.make("net.minecraft.util.ResourceLocation",id),world);
        if(value==null)throw new IllegalStateException("Native entity is absent: "+id);return value;
    }
    private void load(int x,int z,int radius){for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)Reflect.call(world,"getChunkFromChunkCoords/func_72964_e",(x>>4)+dx,(z>>4)+dz);}
    private Object state(String id){Object block=Reflect.registry("BLOCKS",id);if(block==null)throw new IllegalStateException("Missing block "+id);return Reflect.call(block,"getDefaultState/func_176223_P");}
    private void placeAt(Object position,String id){Reflect.call(world,"setBlockState/func_180501_a",position,state(id),3);}
    private Object place(int x,int y,int z,String id){Object position=Reflect.pos(x,y,z);placeAt(position,id);return Reflect.call(world,"getTileEntity/func_175625_s",position);}
    private static JsonObject asObject(Object value){return JsonFiles.JSON.toJsonTree(value).getAsJsonObject();}
    private void require(boolean value,String name){checks.add(result(name,value,null));if(!value)throw new IllegalStateException(name);}
    private void run(String name,Checked action){try{action.run();checks.add(result(name,true,null));}catch(Throwable failed){failures++;checks.add(result(name,false,failed));failed.printStackTrace();}}
    private static Map<String,Object> result(String name,boolean passed,Throwable failed){Map<String,Object> value=new LinkedHashMap<String,Object>();value.put("name",name);value.put("passed",passed);if(failed!=null)value.put("error",failed.toString());return value;}
    private interface Checked {void run()throws Exception;}
}
