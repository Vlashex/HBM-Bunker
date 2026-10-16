package dev.deadearth.zone;

import static dev.deadearth.config.DeadEarthConfig.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Incremental observations on the server thread. Snapshots are evidence with age, not transactions. */
public final class ZoneScanner {
    public record Summary(UUID id,String dimension,int loaders,int chunks,long ageTicks,boolean finished,boolean complete,
            long resourceEmc,long unpricedItems,double resourceCoefficient,double value,long armedRank,long emptyRank,int unknownContainers,
            int unknownCells,int unknownPrices,int unknownTurrets,int missingChunks,boolean truncated,boolean overflow) {}
    public record Report(UUID id,String dimension,int loaders,int chunks,long generation,long startedTick,long completedTick,long ageTicks,
            boolean finished,boolean complete,boolean overflow,long resourceEmc,double resourceCoefficient,double value,
            long unpricedItems,int unknownPrices,int containers,int inventorySlots,int meCells,int unknownContainers,int unknownCells,
            int missingChunks,boolean truncated,long armedRank,long emptyRank,int unknownTurrets,List<ZoneCompatibility.Turret> turrets,String fault) {}
    private final Map<UUID,Report> published=new LinkedHashMap<>();
    private List<ZoneTopology.Zone> topology=List.of();private final ArrayDeque<Job> jobs=new ArrayDeque<>();
    private long nextScan,generation;private String fault;
    private static final class Budget {int chunks=i(ZONE_CHUNKS),bes=i(ZONE_BES),slots=i(ZONE_SLOTS),cells=i(ZONE_CELLS),entities=i(ZONE_MAX_ENTITIES);}
    private static final class Job {
        final ZoneTopology.Zone zone;final long generation,started;final double coefficient=d(ZONE_EMC);final ZoneScore score=new ZoneScore();
        final ArrayDeque<Long> chunks;final ArrayDeque<BlockPos> blocks=new ArrayDeque<>();final ArrayDeque<UUID> entities=new ArrayDeque<>();
        final Set<UUID> seenEntities=new HashSet<>();final List<ZoneCompatibility.Turret> turrets=new ArrayList<>();
        BlockEntity current;int slot,cell,containers,slots,cells,unknownContainers,unknownCells,unknownPrices,missing;boolean truncated;
        Job(ZoneTopology.Zone zone,long generation,long started){this.zone=zone;this.generation=generation;this.started=started;chunks=new ArrayDeque<>(zone.chunks());}
        void turret(ZoneCompatibility.Turret turret){if(turret==null)return;score.turret(turret.state(),turret.rank());if(turrets.size()<256)turrets.add(turret);else truncated=true;}
        void stock(net.minecraft.world.item.ItemStack item,long amount){if(item.isEmpty()||amount<=0)return;var price=ZoneCompatibility.price(item);if(price.unknown())unknownPrices++;else score.stock(price.unit(),amount);}
        Report report(long now,boolean done,String fault){boolean complete=done&&missing==0&&!truncated&&unknownContainers==0&&unknownCells==0&&unknownPrices==0&&score.unknownTurrets()==0&&fault==null;
            return new Report(zone.id(),zone.dimension(),zone.loaders().size(),zone.chunks().size(),generation,started,done?now:-1,0,done,complete,score.overflow(),score.emc(),coefficient,score.value(coefficient),
                    score.unpricedItems(),unknownPrices,containers,slots,cells,unknownContainers,unknownCells,missing,truncated,score.armedRank(),score.emptyRank(),score.unknownTurrets(),List.copyOf(turrets),fault);}
    }
    public void request(){nextScan=0;}
    public List<Summary> summaries(long now){return reports(now).stream().map(r->new Summary(r.id,r.dimension,r.loaders,r.chunks,r.ageTicks,r.finished,r.complete,r.resourceEmc,r.unpricedItems,r.resourceCoefficient,r.value,r.armedRank,r.emptyRank,r.unknownContainers,r.unknownCells,r.unknownPrices,r.unknownTurrets,r.missingChunks,r.truncated,r.overflow)).toList();}
    public List<Report> reports(long now) {
        return published.values().stream().map(r->new Report(r.id,r.dimension,r.loaders,r.chunks,r.generation,r.startedTick,r.completedTick,
                Math.max(0,now-(r.finished?r.completedTick:r.startedTick)),r.finished,r.complete,r.overflow,r.resourceEmc,r.resourceCoefficient,r.value,
                r.unpricedItems,r.unknownPrices,r.containers,r.inventorySlots,r.meCells,r.unknownContainers,r.unknownCells,r.missingChunks,r.truncated,r.armedRank,r.emptyRank,r.unknownTurrets,r.turrets,r.fault)).toList();
    }
    public void tick(MinecraftServer server,List<ZoneTopology.Zone> zones) {
        if(!server.isSameThread())throw new IllegalStateException("Zone scan outside server thread");
        if(!topology.equals(zones)){topology=List.copyOf(zones);published.clear();jobs.clear();nextScan=0;fault=null;}
        if(fault!=null)return;
        long now=Integer.toUnsignedLong(server.getTickCount());
        if(jobs.isEmpty()&&now>=nextScan){generation++;for(var zone:topology){var job=new Job(zone,generation,now);jobs.add(job);published.put(zone.id(),job.report(now,false,null));}nextScan=now+i(ZONE_INTERVAL);}
        var budget=new Budget();
        try {
            for(int work=0;work<4096&&!jobs.isEmpty();work++) {
                var job=jobs.getFirst();int result=step(server,job,budget);
                if(result==0)break;
                boolean done=result==2;published.put(job.zone.id(),job.report(now,done,null));if(done)jobs.removeFirst();
            }
        }catch(RuntimeException error){fault="scan_failed";if(!jobs.isEmpty()){var job=jobs.removeFirst();published.put(job.zone.id(),job.report(now,true,fault));}jobs.clear();dev.deadearth.DeadEarth.LOGGER.error("Zone scanner suspended; physical resources unchanged",error);}
    }
    /** 0: budget exhausted, 1: bounded progress, 2: finished. */
    private int step(MinecraftServer server,Job job,Budget budget) {
        ServerLevel level=ZoneLoaders.level(server,job.zone.dimension());
        if(level==null){job.missing+=job.chunks.size();job.chunks.clear();job.blocks.clear();job.entities.clear();job.current=null;return 2;}
        if(job.current!=null) {
            var be=job.current;
            if(!level.hasChunkAt(be.getBlockPos())||be.isRemoved()||level.getBlockEntity(be.getBlockPos())!=be){job.unknownContainers++;job.current=null;return 1;}
            if(ZoneCompatibility.me(be)) {
                if(job.cell>=ZoneCompatibility.cellCount(be)){job.current=null;return 1;}
                if(budget.cells<=0)return 0;budget.cells--;var cell=ZoneCompatibility.cell(be,job.cell++);job.cells++;
                if(cell.unknown())job.unknownCells++;else for(var stock:cell.stock())job.stock(stock.item(),stock.amount());
                return 1;
            }
            var container=ZoneCompatibility.container(be);
            if(container.unknown()){job.unknownContainers++;job.current=null;return 1;}
            if(job.slot>=container.slots()){job.current=null;return 1;}
            if(budget.slots<=0)return 0;budget.slots--;
            var stack=container.inventory().getItem(job.slot++);job.slots++;job.stock(stack,stack.getCount());return 1;
        }
        if(!job.blocks.isEmpty()) {
            if(budget.bes<=0)return 0;budget.bes--;var pos=job.blocks.removeFirst();
            if(!level.hasChunkAt(pos)){job.missing++;return 1;}
            var be=level.getBlockEntity(pos);if(be==null)return 1;job.turret(ZoneCompatibility.turret(be));
            if(ZoneCompatibility.me(be)){job.current=be;job.cell=0;return 1;}
            var container=ZoneCompatibility.container(be);
            if(container.unknown())job.unknownContainers++;else if(container.inventory()!=null){job.containers++;job.current=be;job.slot=0;}
            return 1;
        }
        if(!job.entities.isEmpty()) {
            if(budget.entities<=0)return 0;budget.entities--;var entity=level.getEntity(job.entities.removeFirst());
            if(entity!=null&&job.zone.chunks().contains(entity.chunkPosition().toLong()))job.turret(ZoneCompatibility.turret(entity));return 1;
        }
        if(job.chunks.isEmpty())return 2;
        if(budget.chunks<=0)return 0;budget.chunks--;long key=job.chunks.removeFirst();int x=(int)key,z=(int)(key>>32);
        var chunk=level.getChunkSource().getChunkNow(x,z);if(chunk==null){job.missing++;return 1;}
        int n=0;for(var pos:chunk.getBlockEntities().keySet()){if(n++>=i(ZONE_MAX_BES)){job.truncated=true;break;}job.blocks.add(pos.immutable());}
        var sample=ZoneSentryIndex.sample(level,key,i(ZONE_MAX_ENTITIES));if(sample.truncated())job.truncated=true;
        for(var id:sample.ids())if(job.seenEntities.add(id))job.entities.add(id);
        return 1;
    }
}
