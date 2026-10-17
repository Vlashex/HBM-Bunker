package dev.deadearth.gametest;

import dev.deadearth.DeadEarth;
import dev.deadearth.content.*;
import dev.deadearth.director.*;
import dev.deadearth.zone.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("dead_earth_cinematic") @PrefixGameTestTemplate(false)
public final class CinematicGameTests {
    static CinematicCatalog catalog() {
        try(var in=CinematicGameTests.class.getResourceAsStream("/data/dead_earth/director/catalog.json")) {
            return CinematicCatalog.parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @GameTest(template="empty",batch="cinematic_catalog")
    public static void fullCatalogReferencesExistInPinnedPack(GameTestHelper h) {
        String revision=DeadEarth.runtime(h.getLevel().getServer()).cinematics().reload();
        h.assertTrue(revision.matches("[0-9a-f]{64}"),"Catalog rejected by physical registries");h.succeed();
    }
    @GameTest(template="empty",batch="cinematic_physical",timeoutTicks=80)
    public static void placedTraceDoesNotBlockLaterSoundOrWaveAnchor(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var director=DeadEarth.runtime(server).cinematics();
        var base=new BlockPos(1800008,200,1800008);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(base.getX()/16+x,base.getZ()/16+z);
        for(int x=-16;x<32;x++)for(int z=-16;z<32;z++)level.setBlock(new BlockPos(1800000+x,200,1800000+z),Blocks.STONE.defaultBlockState(),3);
        level.setBlock(base,ZoneLoaderContent.LOADER.get().defaultBlockState(),3);
        try(var client=FoundationGameTests.attach(h)) {
            client.player().setPos(base.getX()+.5,201,base.getZ()+.5);client.player().setYRot(90);client.player().setXRot(0);
            var zone=ZoneLoaders.zones(server).stream().filter(v->v.loaders().stream().anyMatch(l->l.x()==base.getX())).findFirst().orElseThrow();
            var c=catalog();var op=new CinematicEngine.Operation(UUID.fromString("00000000-0000-4000-8000-000000000001"),UUID.randomUUID(),
                CinematicEngine.freeze(c,"spore_lure"),"spore_lure",zone.id().toString(),CinematicEngine.State.WAITING,"pollen",0,0,4800,0,4,0,"",Set.of());
            director.emit(op,new CinematicCatalog.Action("trace","spore_1","approach",0),0,c);
            var traces=new ArrayList<TraceContent.TraceEntity>();
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(var be:level.getChunk(base.getX()/16+x,base.getZ()/16+z).getBlockEntities().values())
                if(be instanceof TraceContent.TraceEntity trace&&op.id().equals(trace.operation()))traces.add(trace);
            h.assertTrue(traces.size()==1,"No physical trace placed");
            Object packet;while((packet=client.channel().readOutbound())!=null){}
            var sound=new CinematicCatalog.Sound("minecraft:entity.warden.heartbeat",2,1);
            var loud=new CinematicCatalog(c.revision(),c.rules(),Map.of("test",sound),c.traces(),c.waves(),c.events());
            director.emit(op,new CinematicCatalog.Action("sound","test","approach",0),0,loud);
            int heard=0;while((packet=client.channel().readOutbound())!=null)if(packet instanceof ClientboundSoundPacket)heard++;
            h.assertTrue(heard==1,"Existing own trace silenced the next spatial cue");
            director.emit(op,new CinematicCatalog.Action("wave","scouts","approach",1),1,c);
            h.assertTrue(director.livingOwned()>0,"Own trace prevented a valid native scout spawn");
            traces.forEach(t->level.removeBlock(t.getBlockPos(),false));
        } finally {level.removeBlock(base,false);}
        h.succeed();
    }
    @GameTest(template="empty",batch="cinematic_interaction",timeoutTicks=180)
    public static void realPlayerInteractionChangesThePersistedEventBranch(GameTestHelper h) {
        var level=h.getLevel();var server=level.getServer();var runtime=DeadEarth.runtime(server);var director=runtime.cinematics();
        var base=new BlockPos(1900008,200,1900008);var cat=catalog();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(base.getX()/16+x,base.getZ()/16+z);
        for(int x=-16;x<32;x++)for(int z=-16;z<32;z++)level.setBlock(new BlockPos(1900000+x,200,1900000+z),Blocks.STONE.defaultBlockState(),3);
        level.setBlock(base,ZoneLoaderContent.LOADER.get().defaultBlockState(),3);
        var client=FoundationGameTests.attach(h);client.player().setPos(base.getX()+.5,201,base.getZ()+.5);
        // Only this isolated fixture enables automatic cinematic execution.
        boolean previous=dev.deadearth.config.DeadEarthConfig.b(dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED);
        UUID[] operation={null};
        try {
            ZoneLoaders.requestScan(server);for(int pass=0;pass<100;pass++)ZoneLoaders.tick(server);
            dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED.set(true);
            var brief=director.brief();h.assertTrue(brief.goal()!=null,"No actionable complete fixture zone: "+brief.status());
            var choice=brief.goal().options().stream().filter(o->cat.events().get(o.eventId()).nodes().get(cat.events().get(o.eventId()).start()).branches().stream()
                .anyMatch(b->java.util.Set.of("water","burn","inspect","inspect:flank","inspect:rear").contains(b.when()))).findFirst().orElseThrow();
            var definition=cat.events().get(choice.eventId());
            var branch=definition.nodes().get(definition.start()).branches().stream().filter(b->java.util.Set.of("water","burn","inspect","inspect:flank","inspect:rear").contains(b.when())).findFirst().orElseThrow();
            var verdict=director.choose(UUID.randomUUID(),brief.goal().id(),choice.eventId(),choice.zoneId());
            h.assertTrue(verdict.accepted(),"Live admission rejected: "+verdict.code());operation[0]=verdict.operationId();
            // Turn away from all clue roles used by this graph so the first cue can be staged outside view.
            client.player().setXRot(-90);
            h.runAfterDelay(90,()->{
                try {
                    var clues=new ArrayList<TraceContent.TraceEntity>();
                    for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(var be:level.getChunk(base.getX()/16+x,base.getZ()/16+z).getBlockEntities().values())
                        if(be instanceof TraceContent.TraceEntity trace&&operation[0].equals(trace.operation()))clues.add(trace);
                    var trace=clues.stream().filter(t->!branch.when().contains(":")||branch.when().endsWith(":"+t.role())).findFirst().orElseThrow();
                    var pos=trace.getBlockPos();client.player().setPos(pos.getX()+2.5,pos.getY(),pos.getZ()+.5);
                    String kind=branch.when().split(":")[0];
                    var item=kind.equals("water")?net.minecraft.world.item.Items.WATER_BUCKET:kind.equals("burn")?net.minecraft.world.item.Items.FLINT_AND_STEEL:net.minecraft.world.item.Items.AIR;
                    client.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(item));
                    var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);
                    client.player().gameMode.useItemOn(client.player(),level,client.player().getMainHandItem(),net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                    if(kind.equals("water"))h.assertTrue(client.player().getMainHandItem().is(net.minecraft.world.item.Items.BUCKET),"Trace did not consume actual water interaction");
                    h.runAfterDelay(25,()->{
                        try {
                            var raw=(java.util.Map<?,?>)director.status();var rows=(java.util.List<?>)raw.get("operations");
                            var row=rows.stream().map(o->(java.util.Map<?,?>)o).filter(o->operation[0].equals(o.get("id"))).findFirst().orElseThrow();
                            h.assertTrue(branch.next().equals(row.get("node")),"Physical interaction failed to change branch: "+row.get("node"));
                            h.succeed();
                        } finally {
                            if(operation[0]!=null)director.cancel(operation[0]);client.close();level.removeBlock(base,false);
                            dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED.set(previous);
                        }
                    });
                } catch(RuntimeException|Error failure) {
                    if(operation[0]!=null)director.cancel(operation[0]);client.close();level.removeBlock(base,false);
                    dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED.set(previous);throw failure;
                }
            });
        } catch(RuntimeException|Error failure) {
            if(operation[0]!=null)director.cancel(operation[0]);client.close();level.removeBlock(base,false);
            dev.deadearth.config.DeadEarthConfig.DIRECTOR_ENABLED.set(previous);throw failure;
        }
    }

}
