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
}
