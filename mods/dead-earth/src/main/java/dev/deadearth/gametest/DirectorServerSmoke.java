package dev.deadearth.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.deadearth.DeadEarth;
import dev.deadearth.config.DeadEarthConfig;
import dev.deadearth.zone.ZoneLoaders;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Explicit dev-server fixture. No accelerated GameTest clock and no user-world auto-start. */
public final class DirectorServerSmoke {
    private static State current;
    private DirectorServerSmoke() {}
    public static void tick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("deadearth.directorServerSmoke") || !Boolean.getBoolean("deadearth.testControls")) return;
        var server=event.getServer();
        try {
            if(current==null)current=new State(server);
            current.tick();
        } catch(Exception error) {
            DeadEarth.LOGGER.error("Director CPU smoke failed: {}",error.getClass().getSimpleName());
            if(current!=null)current.finish(false,"fixture_error");
            server.halt(false);
        }
    }
    private static final class State {
        final MinecraftServer server;final ServerPlayer player;final EmbeddedChannel channel;final Path root;
        final boolean previous;final long deadline=System.nanoTime()+180_000_000_000L;
        boolean ready,finished;
        State(MinecraftServer server) throws Exception {
            this.server=server;root=server.getWorldPath(LevelResource.ROOT);
            previous=DeadEarthConfig.b(DeadEarthConfig.DIRECTOR_ENABLED);
            DeadEarthConfig.DIRECTOR_ENABLED.set(true);
            var profile=new GameProfile(UUID.randomUUID(),"DEDirectorProbe");
            var cookie=CommonListenerCookie.createInitial(profile,false);
            player=new ServerPlayer(server,server.overworld(),profile,cookie.clientInformation());
            var persisted=player.getPersistentData().getCompound("PlayerPersisted");
            persisted.putBoolean("outpostzero:first_join_spawn_done",true);
            player.getPersistentData().put("PlayerPersisted",persisted);
            var connection=new Connection(PacketFlow.SERVERBOUND);channel=new EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection,player,cookie);
            player.setInvulnerable(true);player.setGameMode(GameType.SPECTATOR);
            var level=server.overworld();int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,8,8)+1;
            var loader=new BlockPos(8,y,8);var chest=new BlockPos(10,y,8);
            level.setBlockAndUpdate(loader,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("dead_earth:zone_loader")).defaultBlockState());
            if(!ZoneLoaders.activate(level,loader))throw new IllegalStateException("Fixture loader failed");
            level.setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity)level.getBlockEntity(chest)).setItem(0,new ItemStack(Items.DIAMOND,4));
            ZoneLoaders.requestScan(server);
        }
        void tick() throws Exception {
            if(finished)return;
            Object packet;
            while((packet=channel.readOutbound())!=null) {
                if(packet instanceof ClientboundKeepAlivePacket keep)player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keep.getId()));
                io.netty.util.ReferenceCountUtil.release(packet);
            }
            if(System.nanoTime()>deadline){finish(false,"bounded_timeout");return;}
            var reports=ZoneLoaders.summaries(server);
            if(!ready&&server.getTickCount()>=40) {
                DeadEarth.runtime(server).bridge().start();
                var marker=new JsonObject();marker.addProperty("ready",true);marker.addProperty("previewOnly",true);
                Files.writeString(root.resolve("director-d1-ready.json"),marker.toString());ready=true;
                DeadEarth.LOGGER.info("Director CPU smoke ready; using observed completeness, not a presumed EMC value");
            }
            var result=root.resolve("director-d1-result.json");
            if(ready&&Files.isRegularFile(result)) {
                var data=JsonParser.parseString(Files.readString(result)).getAsJsonObject();
                boolean valid=data.get("ok").getAsBoolean()&&data.get("maximum_vram_bytes").getAsLong()==0;
                finish(valid,valid?"complete":"external_check_failed");
            }
        }
        void finish(boolean success,String reason) {
            if(finished)return;finished=true;
            try {
                var status=new JsonObject();status.addProperty("success",success);status.addProperty("reason",reason);
                status.add("zones",new com.google.gson.Gson().toJsonTree(ZoneLoaders.summaries(server)));
                Files.writeString(root.resolve("director-d1-server-status.json"),status.toString());
            }catch(Exception ignored){}
            DeadEarth.runtime(server).bridge().close();
            server.getPlayerList().remove(player);channel.finishAndReleaseAll();
            DeadEarthConfig.DIRECTOR_ENABLED.set(previous);
            DeadEarth.LOGGER.info("Director CPU smoke finished: {} ({})",success,reason);
            server.halt(false);
        }
    }
}
