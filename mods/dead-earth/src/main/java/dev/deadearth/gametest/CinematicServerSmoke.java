package dev.deadearth.gametest;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.deadearth.DeadEarth;
import dev.deadearth.config.DeadEarthConfig;
import dev.deadearth.content.*;
import dev.deadearth.director.*;
import dev.deadearth.zone.ZoneLoaders;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** A normal-clock, explicitly opted-in development server; never enabled by a pack config. */
public final class CinematicServerSmoke {
    private static State current;
    private static final int OFFSET=4096;
    private static BlockPos point(int x,int y,int z){return new BlockPos(x+OFFSET,y,z+OFFSET);}
    public static void tick(ServerTickEvent.Post event) {
        if(!Boolean.getBoolean("deadearth.cinematicServerSmoke")||!Boolean.getBoolean("deadearth.testControls"))return;
        try {if(current==null)current=new State(event.getServer());current.tick();}
        catch(Exception failure){DeadEarth.LOGGER.error("Cinematic smoke fixture failed",failure);if(current!=null)current.finish(false,"fixture_error");else event.getServer().halt(false);}
    }
    private static final class State {
        final MinecraftServer server;final ServerPlayer player;final EmbeddedChannel channel;final Path root;
        final long deadline=System.nanoTime()+420_000_000_000L;
        final Set<UUID> positioned=new HashSet<>();
        boolean ready,finished,clientSeen;
        State(MinecraftServer server)throws Exception {
            this.server=server;root=server.getWorldPath(LevelResource.ROOT);var level=server.overworld();
            // This fixture is only ever launched in its own new void world.
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk(x+OFFSET/16,z+OFFSET/16);
            for(int x=-16;x<32;x++)for(int z=-16;z<32;z++)level.setBlock(point(x,70,z),Blocks.GRASS_BLOCK.defaultBlockState(),3);
            for(var tree:List.of(point(-4,71,10),point(20,71,12),point(14,71,21))) {
                for(int y=0;y<5;y++)level.setBlock(tree.above(y),Blocks.OAK_LOG.defaultBlockState(),3);
                for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=3;dy<=5;dy++)
                    if(Math.abs(dx)+Math.abs(dz)<4&&!level.getBlockState(tree.offset(dx,dy,dz)).is(Blocks.OAK_LOG))
                        level.setBlock(tree.offset(dx,dy,dz),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true),3);
            }
            var loader=point(8,71,8);level.setBlock(loader,ZoneLoaderContent.LOADER.get().defaultBlockState(),3);
            var chest=point(9,71,8);level.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
            ((ChestBlockEntity)level.getBlockEntity(chest)).setItem(0,new ItemStack(Items.DIAMOND,4));
            var profile=new GameProfile(UUID.randomUUID(),"DECinematicProbe");var cookie=CommonListenerCookie.createInitial(profile,false);
            player=new ServerPlayer(server,level,profile,cookie.clientInformation());
            var persisted=player.getPersistentData().getCompound("PlayerPersisted");persisted.putBoolean("outpostzero:first_join_spawn_done",true);player.getPersistentData().put("PlayerPersisted",persisted);
            var connection=new Connection(PacketFlow.SERVERBOUND);channel=new EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection,player,cookie);
            player.setInvulnerable(true);player.setGameMode(GameType.SURVIVAL);player.setPos(8.5+OFFSET,72,8.5+OFFSET);player.setXRot(-90);
            level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
            try(var input=CinematicServerSmoke.class.getResourceAsStream("/data/dead_earth/director/catalog.json")) {
                var cat=CinematicCatalog.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
                int index=0;for(String pattern:List.of("claw_1","spore_1","drag_2","foot_2","root_3","fracture_5")) {
                    var pos=point(3+index*2,71,12+(index%2)*2);index++;
                    level.setBlock(pos,TraceContent.TRACE.get().defaultBlockState(),3);
                    ((TraceContent.TraceEntity)level.getBlockEntity(pos)).configure(UUID.fromString("00000000-0000-4000-8000-00000000cafe"),"approach",cat.traces().get(pattern),6000);
                }
            }
            // Keep one known observation stable for this model/renderer fixture; scanner cadence is covered separately.
            DeadEarthConfig.ZONE_INTERVAL.set(72000);DeadEarthConfig.DIRECTOR_ENABLED.set(!Boolean.getBoolean("deadearth.cinematicCloseup"));ZoneLoaders.requestScan(server);
        }
        void tick()throws Exception {
            if(finished)return;
            Object packet;while((packet=channel.readOutbound())!=null) {
                if(packet instanceof ClientboundKeepAlivePacket keep)player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keep.getId()));
                io.netty.util.ReferenceCountUtil.release(packet);
            }
            if(System.nanoTime()>deadline){finish(false,"bounded_timeout");return;}
            for(var visitor:server.getPlayerList().getPlayers())if(visitor!=player&&visitor.tickCount>=40&&positioned.add(visitor.getUUID())) {
                var nativeState=visitor.getPersistentData().getCompound("PlayerPersisted");nativeState.putBoolean("outpostzero:first_join_spawn_done",true);nativeState.remove("outpostzero:first_join_spawn_pending");visitor.getPersistentData().put("PlayerPersisted",nativeState);
                clientSeen=true;visitor.setInvulnerable(true);visitor.setGameMode(GameType.SPECTATOR);
                if(Boolean.getBoolean("deadearth.cinematicCloseup"))visitor.connection.teleport(4.5+OFFSET,71.6,10.5+OFFSET,26.565f,44.58f);
                else visitor.connection.teleport(8.5+OFFSET,73,3.5+OFFSET,0,30);
                DeadEarth.LOGGER.info("Cinematic smoke client positioned in test grove");
            }
            var runtime=DeadEarth.runtime(server);
            if(server.getTickCount()>=40&&!runtime.bridge().running())runtime.bridge().start();
            var brief=runtime.cinematics().brief();
            if(!ready&&server.getTickCount()>300) {
                var zone=ZoneLoaders.summaries(server);
                if(zone.stream().anyMatch(z->z.finished()&&!z.complete())){finish(false,"fixture_zone_incomplete");return;}
            }
            if(!ready&&(brief.goal()!=null||Boolean.getBoolean("deadearth.cinematicCloseup")&&server.getTickCount()>=40)) {
                runtime.bridge().start();Files.writeString(root.resolve("director-cinematic-ready.json"),"{\"ready\":true}");ready=true;
            }
            if(server.getTickCount()%20==0)writeStatus();
            var result=root.resolve("director-cinematic-result.json");
            if(ready&&Files.isRegularFile(result)) {
                var report=JsonParser.parseString(Files.readString(result)).getAsJsonObject();
                finish(report.get("ok").getAsBoolean(),"external_checks_complete");
            }
        }
        void writeStatus()throws Exception {
            var status=new JsonObject();status.addProperty("ready",ready);status.addProperty("clientSeen",clientSeen);
            status.addProperty("clientDone",clientSeen&&server.getPlayerList().getPlayers().stream().noneMatch(p->p!=player));
            status.addProperty("activeTicks",DeadEarth.runtime(server).campaign().campaign().activeTicks());
            status.addProperty("livingOwned",DeadEarth.runtime(server).cinematics().livingOwned());
            status.add("zones",new Gson().toJsonTree(ZoneLoaders.summaries(server)));
            status.add("queue",new Gson().toJsonTree(DeadEarth.runtime(server).cinematics().status()));
            Files.writeString(root.resolve("director-cinematic-status.json"),status.toString());
        }
        void finish(boolean okay,String reason) {
            if(finished)return;finished=true;
            try{writeStatus();Files.writeString(root.resolve("director-cinematic-server-result.json"),new Gson().toJson(Map.of("success",okay,"reason",reason)));}catch(Exception ignored){}
            DeadEarth.runtime(server).bridge().close();server.getPlayerList().remove(player);channel.finishAndReleaseAll();
            DeadEarth.LOGGER.info("Cinematic smoke finished {} ({})",okay,reason);server.halt(false);
        }
    }
}
