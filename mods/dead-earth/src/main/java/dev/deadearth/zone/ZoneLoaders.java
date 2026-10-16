package dev.deadearth.zone;

import static dev.deadearth.config.DeadEarthConfig.*;
import dev.deadearth.content.ZoneLoaderContent;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.*;

/** Native block-owned persistent tickets are the single authoritative loader registry. */
public final class ZoneLoaders {
    public static final TicketController TICKETS=new TicketController(ResourceLocation.parse("dead_earth:zone_loader"),ZoneLoaders::restore);
    private static final Map<MinecraftServer,State> SERVERS=new IdentityHashMap<>();
    private static final class State {final TreeSet<ZoneTopology.Loader> owners=new TreeSet<>();boolean reconcile,dirty=true;List<ZoneTopology.Zone> zones=List.of();final ZoneScanner scanner=new ZoneScanner();}
    private ZoneLoaders() {}
    private static State state(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Zone access outside server thread");return SERVERS.computeIfAbsent(server,s->new State());}
    private static ZoneTopology.Loader owner(ServerLevel level,BlockPos pos){return new ZoneTopology.Loader(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
    public static void register(RegisterTicketControllersEvent event){event.register(TICKETS);}
    public static boolean active(ServerLevel level,BlockPos pos){return state(level.getServer()).owners.contains(owner(level,pos));}
    public static List<ZoneTopology.Zone> zones(MinecraftServer server){var s=state(server);if(s.dirty){s.zones=ZoneTopology.build(s.owners);s.dirty=false;}return s.zones;}
    public static List<ZoneScanner.Report> reports(MinecraftServer server){return state(server).scanner.reports(Integer.toUnsignedLong(server.getTickCount()));}
    public static List<ZoneScanner.Summary> summaries(MinecraftServer server){return state(server).scanner.summaries(Integer.toUnsignedLong(server.getTickCount()));}
    public static void requestScan(MinecraftServer server){state(server).scanner.request();}
    public static int count(MinecraftServer server){return state(server).owners.size();}
    public static boolean activate(ServerLevel level,BlockPos pos) {
        var s=state(level.getServer());var key=owner(level,pos);
        if(!level.getBlockState(pos).is(ZoneLoaderContent.LOADER.get()))return false;
        if(!s.owners.contains(key)&&s.owners.size()>=i(ZONE_LOADERS))return false;
        if(s.owners.add(key))s.dirty=true;tickets(level,key,true);return true;
    }
    public static void deactivate(ServerLevel level,BlockPos pos) {
        var s=state(level.getServer());var key=owner(level,pos);
        if(s.owners.remove(key)){s.dirty=true;tickets(level,key,false);}
    }
    private static void tickets(ServerLevel level,ZoneTopology.Loader owner,boolean add) {
        var pos=new BlockPos(owner.x(),owner.y(),owner.z());
        for(long chunk:ZoneTopology.footprint(owner))TICKETS.forceChunk(level,pos,(int)chunk,(int)(chunk>>32),add,true);
    }
    private static void restore(ServerLevel level,TicketHelper helper) {
        var s=state(level.getServer());
        for(var entity:helper.getEntityTickets().keySet())helper.removeAllTickets(entity);
        for(var pos:new TreeSet<>(helper.getBlockTickets().keySet())) {
            var key=owner(level,pos);var saved=helper.getBlockTickets().get(pos);var expected=ZoneTopology.footprint(key);
            // Loading the owner chunk is part of restoring this explicit block loader, never valuation/weather.
            if(s.owners.size()>=i(ZONE_LOADERS)||!level.getBlockState(pos).is(ZoneLoaderContent.LOADER.get())){helper.removeAllTickets(pos);continue;}
            for(long chunk:saved.nonTicking())helper.removeTicket(pos,chunk,false);
            for(long chunk:saved.ticking())if(!expected.contains(chunk))helper.removeTicket(pos,chunk,true);
            s.owners.add(key);s.dirty=true;s.reconcile=true;
        }
    }
    public static void tick(MinecraftServer server) {
        var s=state(server);
        if(s.reconcile){s.reconcile=false;for(var owner:s.owners){var level=level(server,owner.dimension());if(level!=null)tickets(level,owner,true);}}
        s.scanner.tick(server,zones(server));
    }
    public static ServerLevel level(MinecraftServer server,String dimension){return server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(dimension)));}
    public static void close(MinecraftServer server){SERVERS.remove(server);}
}
