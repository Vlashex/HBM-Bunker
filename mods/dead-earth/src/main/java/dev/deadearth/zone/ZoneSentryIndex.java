package dev.deadearth.zone;

import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Lifecycle index avoids spatial queries that enumerate every mob/item or multipart entity. */
public final class ZoneSentryIndex {
    public record Sample(List<UUID> ids,boolean truncated) {}
    private record Location(String dimension,long chunk) {}
    private static final class Index {final Map<UUID,Location> positions=new HashMap<>();final Map<Location,LinkedHashSet<UUID>> chunks=new HashMap<>();}
    private static final Map<MinecraftServer,Index> SERVERS=new IdentityHashMap<>();
    private ZoneSentryIndex() {}
    public static void install(){
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e)->observe(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent e)->{if(e.getLevel() instanceof ServerLevel level)remove(level,e.getEntity());});
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Post e)->observe(e.getEntity()));
    }
    public static void observe(Entity entity) {
        if(!(entity.level() instanceof ServerLevel level)||!ZoneCompatibility.sentry(entity))return;
        var index=SERVERS.computeIfAbsent(level.getServer(),s->new Index());var id=entity.getUUID();
        var location=new Location(level.dimension().location().toString(),entity.chunkPosition().toLong());var old=index.positions.put(id,location);
        if(location.equals(old))return;
        if(old!=null)removeFromBucket(index,old,id);
        index.chunks.computeIfAbsent(location,k->new LinkedHashSet<>()).add(id);
    }
    private static void removeFromBucket(Index index,Location location,UUID id){var bucket=index.chunks.get(location);if(bucket!=null){bucket.remove(id);if(bucket.isEmpty())index.chunks.remove(location);}}
    private static void remove(ServerLevel level,Entity entity) {
        var index=SERVERS.get(level.getServer());if(index==null)return;var id=entity.getUUID();var old=index.positions.get(id);
        if(old!=null&&old.dimension.equals(level.dimension().location().toString())){index.positions.remove(id);removeFromBucket(index,old,id);}
    }
    public static Sample sample(ServerLevel level,long chunk,int limit) {
        if(limit<1)throw new IllegalArgumentException("Invalid sentry sample limit");
        var index=SERVERS.get(level.getServer());if(index==null)return new Sample(List.of(),false);
        var bucket=index.chunks.get(new Location(level.dimension().location().toString(),chunk));if(bucket==null)return new Sample(List.of(),false);
        var ids=new ArrayList<UUID>();int visited=0;
        for(var id:bucket){if(visited++==limit)break;var entity=level.getEntity(id);
            if(entity!=null&&!entity.isRemoved()&&entity.chunkPosition().toLong()==chunk)ids.add(id);}
        return new Sample(List.copyOf(ids),bucket.size()>limit);
    }
    public static void close(MinecraftServer server){SERVERS.remove(server);}
}
