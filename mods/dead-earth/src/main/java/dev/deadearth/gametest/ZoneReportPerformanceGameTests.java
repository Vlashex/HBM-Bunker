package dev.deadearth.gametest;

import static dev.deadearth.config.DeadEarthConfig.*;
import dev.deadearth.zone.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.*;

/** Counts actual report publications, without timing assertions or changing scan contents. */
@GameTestHolder("dead_earth_zones_review") @PrefixGameTestTemplate(false)
public final class ZoneReportPerformanceGameTests {
    private static final class Reports extends LinkedHashMap<UUID,ZoneScanner.Report> {
        int writes;
        @Override public ZoneScanner.Report put(UUID id,ZoneScanner.Report report){writes++;return super.put(id,report);}
    }
    @GameTest(template="empty",batch="zone_report_perf")
    public static void onePublicationPerTouchedJobPreservesPartialAndFinishedFacts(GameTestHelper h) throws Exception {
        var level=h.getLevel();var pos=new BlockPos(2740008,230,2740008);level.getChunkAt(pos);
        level.setBlock(pos,Blocks.CHEST.defaultBlockState(),3);
        var chest=(ChestBlockEntity)level.getBlockEntity(pos);chest.setItem(0,new ItemStack(Items.DIAMOND,4));
        int previous=ZONE_SLOTS.get();
        try {
            var scanner=new ZoneScanner();var map=track(scanner);var zones=List.of(zone(level,pos));
            ZONE_SLOTS.set(1);scanner.tick(level.getServer(),zones);
            var partial=scanner.reports(0).getFirst();
            h.assertTrue(map.writes==2,"Expected initial + one tick report, got "+map.writes);
            h.assertTrue(!partial.finished()&&partial.inventorySlots()==1,"Budget boundary lost partial progress");
            map.writes=0;ZONE_SLOTS.set(64);scanner.tick(level.getServer(),zones);
            var complete=scanner.reports(0).getFirst();
            h.assertTrue(map.writes==1,"Expected one final report, got "+map.writes);
            h.assertTrue(complete.finished()&&complete.inventorySlots()==27&&complete.containers()==1,"Final facts changed");
            h.assertTrue(partial.inventorySlots()==1&&!partial.finished(),"Previously published snapshot mutated");
            h.assertTrue(chest.getItem(0).getCount()==4,"Scan consumed physical stock");
        } finally {ZONE_SLOTS.set(previous);level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}
        h.succeed();
    }
    @GameTest(template="empty",batch="zone_report_perf")
    public static void severalJobsFlushAtChunkBudgetAndFailureRemainsVisible(GameTestHelper h) throws Exception {
        var level=h.getLevel();var a=new BlockPos(2750008,230,2750008);var b=a.east(64);
        level.getChunkAt(a);level.getChunkAt(b);
        int previous=ZONE_CHUNKS.get();
        try {
            ZONE_CHUNKS.set(1);var scanner=new ZoneScanner();var reports=track(scanner);
            var zones=List.of(zone(level,a),zone(level,b));scanner.tick(level.getServer(),zones);
            var view=scanner.reports(0);
            h.assertTrue(view.get(0).finished()&&!view.get(1).finished(),"Chunk budget did not stop between jobs");
            h.assertTrue(reports.writes==3,"Expected two initial reports + one completion, got "+reports.writes);
            var jobs=ZoneScanner.class.getDeclaredField("jobs");jobs.setAccessible(true);
            var job=((ArrayDeque<?>)jobs.get(scanner)).getFirst();
            var chunks=job.getClass().getDeclaredField("chunks");chunks.setAccessible(true);
            var failure=new ArrayDeque<Long>(){@Override public Long removeFirst(){throw new IllegalStateException("Intentional zone report fault fixture");}};
            failure.addAll(zones.get(1).chunks());chunks.set(job,failure);
            scanner.tick(level.getServer(),zones);
            var failed=scanner.reports(0).get(1);
            h.assertTrue(failed.finished()&&!failed.complete()&&"scan_failed".equals(failed.fault()),"Scan failure was hidden or marked complete");
            int writes=reports.writes;scanner.request();scanner.tick(level.getServer(),zones);
            h.assertTrue(writes==reports.writes,"Faulted scanner unexpectedly resumed");
        } finally {ZONE_CHUNKS.set(previous);}
        h.succeed();
    }
    private static Reports track(ZoneScanner scanner) throws Exception {
        var field=ZoneScanner.class.getDeclaredField("published");field.setAccessible(true);
        var reports=new Reports();field.set(scanner,reports);return reports;
    }
    private static ZoneTopology.Zone zone(net.minecraft.server.level.ServerLevel level,BlockPos pos) {
        var loader=new ZoneTopology.Loader(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());
        return new ZoneTopology.Zone(UUID.randomUUID(),loader.dimension(),List.of(loader),Set.of(ZoneTopology.chunk(pos.getX()>>4,pos.getZ()>>4)));
    }
}
